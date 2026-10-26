package dev.codelanes.java

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.psi.util.PsiTreeUtil
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport

/**
 * Java flow references: `com.acme.OrderService::place`, `::place(Order)` for an overload, `::class` for the class,
 * `::<init>` (or the class's own name) for a constructor, which stands for the class block.
 */
object JavaFlowLookup {
    /**
     * Null when the class part has a backslash (PHP's syntax), or when the class isn't found in a project without
     * Java files, so another language's reason is shown. A missing member of a found class is
     * [FlowLookup.Missing.ownerFound], so it outranks other languages whatever order they load in.
     */
    fun find(project: Project, reference: String, support: LanguageSupport): FlowLookup? {
        val trimmed = reference.trim()
        val classPart = trimmed.substringBefore("::")
        if ('\\' in classPart) return null
        val name = trimmed.substringAfter("::")
        val cls = JavaPsiFacade.getInstance(project).findClass(classPart, GlobalSearchScope.allScope(project))
            ?: return if (hasJavaFiles(project)) FlowLookup.Missing("$trimmed (no such class)") else null
        val nested = FlowLookup.Missing("$trimmed (nested classes aren't shown as blocks)", ownerFound = true)
        if (cls.containingClass != null) return nested
        if (name == "class" || name == "<init>" || name.substringBefore('(') == cls.name) return FlowLookup.Found(target(cls, null, support))
        val method = JavaBlockBuilder.findOwnMethod(cls, name) ?: inherited(cls, name)
            ?: return FlowLookup.Missing("$trimmed (no such method)", ownerFound = true)
        if (method.containingClass?.containingClass != null) return nested // inherited from a nested class
        return FlowLookup.Found(target(cls, method, support))
    }

    private fun hasJavaFiles(project: Project) =
        FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project)).isNotEmpty()

    /** An inherited method, found in the nearest superclass or interface that declares it. */
    private fun inherited(cls: PsiClass, name: String): PsiMethod? {
        val seen = mutableSetOf<PsiClass>()
        val queue = ArrayDeque(cls.supers.toList())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            JavaBlockBuilder.findOwnMethod(next, name)?.let { return it }
            queue += next.supers
        }
        return null
    }

    private fun target(named: PsiClass, method: PsiMethod?, support: LanguageSupport): FlowTarget {
        val declaring = method?.containingClass ?: named
        val source = declaring.navigationElement as? PsiClass ?: declaring
        return FlowTarget(
            classFqn = JavaBlockBuilder.fqn(named),
            declaringFqn = JavaBlockBuilder.fqn(declaring),
            methodKey = method?.let(JavaBlockBuilder::methodKey),
            isConstructor = false,
            file = source.containingFile,
            classElement = named,
            methodElement = method,
            support = support,
        )
    }

    fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean {
        val fromMethod = from.methodElement as? PsiMethod
        val toMethod = to.methodElement as? PsiMethod
        return when (kind) {
            "calls", "dispatches", "middleware" -> fromMethod != null && callsOrCreates(fromMethod, to.classElement as PsiClass, toMethod)
            "binds" -> fromMethod != null && toMethod != null && fromMethod != toMethod && MethodSignatureUtil.isSuperMethod(fromMethod, toMethod)
            "listens" -> {
                val event = (toMethod?.parameterList?.parameters?.firstOrNull()?.type as? PsiClassType)?.resolve()
                event != null && JavaBlockBuilder.fqn(event) == JavaBlockBuilder.fqn(from.classElement as PsiClass)
            }
            else -> false // "routes": nothing in a method proves a route
        }
    }

    /** [from] calls [toMethod], or (for a class target) creates an instance of [toClass] (`publishEvent(new OrderPlaced(o))`). */
    private fun callsOrCreates(from: PsiMethod, toClass: PsiClass, toMethod: PsiMethod?): Boolean {
        if (toMethod != null) return JavaBlockBuilder.calledMethods(from).any { it == toMethod }
        val target = JavaBlockBuilder.fqn(toClass)
        return PsiTreeUtil.findChildrenOfType(from, PsiNewExpression::class.java).any { created ->
            created.classReference?.resolve()?.let { it is PsiClass && JavaBlockBuilder.fqn(it) == target } == true
        }
    }
}
