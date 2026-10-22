package dev.codelanes.php

import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.NewExpression
import com.jetbrains.php.lang.psi.elements.PhpClass
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport

/** PHP flow references (`App\Foo::bar`, `\App\Foo::bar`, `App\Foo::class`) and the proofs for PHP hops. */
object PhpFlowLookup {
    /** Null when the class part is written with dots: that's Java's syntax, not PHP's. */
    fun find(project: Project, reference: String, support: LanguageSupport): FlowLookup? {
        val trimmed = reference.trim()
        val classPart = trimmed.substringBefore("::")
        if ('.' in classPart) return null
        val fqn = "\\" + classPart.trimStart('\\')
        val name = trimmed.substringAfter("::").removeSuffix("()")
        val shown = trimmed.trimStart('\\')
        val index = PhpIndex.getInstance(project)
        val owners = index.getClassesByFQN(fqn) + index.getInterfacesByFQN(fqn) + index.getTraitsByFQN(fqn)
        val named = owners.firstOrNull() ?: return FlowLookup.Missing("$shown (no such class)")
        if (name == "class") return FlowLookup.Found(target(named, null, support))
        if (name.equals("__construct", ignoreCase = true)) {
            // An inherited or missing constructor stands for the class that was named, not its parent.
            return FlowLookup.Found(target(named, named.ownConstructor, support))
        }
        val method = owners.firstNotNullOfOrNull { it.findMethodByName(name) } ?: return FlowLookup.Missing("$shown (no such method)")
        return FlowLookup.Found(target(named, method, support))
    }

    private fun target(named: PhpClass, method: Method?, support: LanguageSupport): FlowTarget {
        val declaring = method?.containingClass ?: named
        return FlowTarget(
            classFqn = named.fqn,
            declaringFqn = declaring.fqn,
            methodKey = method?.name,
            isConstructor = method?.name.equals("__construct", ignoreCase = true),
            file = declaring.containingFile,
            classElement = named,
            methodElement = method,
            support = support,
        )
    }

    fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean {
        val fromMethod = from.methodElement as? Method
        val toMethod = to.methodElement as? Method
        return when (kind) {
            "calls", "dispatches", "middleware" -> fromMethod?.let { callsOrCreates(it, to.classElement as PhpClass, toMethod) } == true
            "binds" -> fromMethod != null && toMethod != null && toMethod.name.equals(fromMethod.name, ignoreCase = true) &&
                isSubtype(toMethod.containingClass, fromMethod.containingClass)
            "listens" -> toMethod?.parameters?.firstOrNull()?.declaredType?.types?.contains((from.classElement as PhpClass).fqn) == true
            else -> false // "routes": the route is the AI's (and the person's) word, nothing in a method proves it
        }
    }

    /** [from] calls [toMethod], or creates an instance of [toClass] (`event(new OrderPlaced())`). */
    private fun callsOrCreates(from: Method, toClass: PhpClass, toMethod: Method?): Boolean {
        val calls = toMethod != null && PsiTreeUtil.findChildrenOfType(from, MethodReference::class.java)
            .any { call -> call.multiResolve(false).any { it.element == toMethod } }
        if (calls) return true
        if (toMethod != null && !toMethod.name.equals("__construct", ignoreCase = true)) return false
        val target = toClass.fqn
        return PsiTreeUtil.findChildrenOfType(from, NewExpression::class.java).any { created ->
            when (val resolved = created.classReference?.resolve()) {
                is PhpClass -> resolved.fqn == target
                is Method -> created.classReference?.fqn == target || resolved.containingClass?.fqn == target
                else -> created.classReference?.fqn == target
            }
        }
    }

    private fun isSubtype(cls: PhpClass?, of: PhpClass?): Boolean {
        if (cls == null || of == null) return false
        val seen = mutableSetOf<String>()
        fun visit(c: PhpClass): Boolean {
            if (!seen.add(c.fqn)) return false
            if (c != cls && c.fqn == of.fqn) return true
            return (c.supers.toList() + c.implementedInterfaces.toList()).any(::visit)
        }
        return visit(cls)
    }
}
