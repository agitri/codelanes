// PythonCore exposes its PSI names through the experimental PyAst* interfaces; there is no stable alternative.
@file:Suppress("UnstableApiUsage")

package dev.codelanes.python

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyArgumentList
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyDecorator
import com.jetbrains.python.psi.PyDecoratorList
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyListLiteralExpression
import com.jetbrains.python.psi.PyParameterList
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyTupleExpression
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.types.PyClassType
import com.jetbrains.python.psi.types.TypeEvalContext
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport

/**
 * Python flow references: `shop.orders.views::create_order` for a module function, `::OrderService.place` for a
 * method, `::OrderService` for a class and `::OrderService.__init__` for its constructor (which stands for the class
 * block). The part before `::` is the dotted module.
 */
object PythonFlowLookup {
    /**
     * Null when the reference is PHP's (a backslash). A module part that isn't a project `.py` module is Missing
     * without [FlowLookup.Missing.ownerFound]: `com.acme.Foo::bar` still goes to Java, whose Found wins.
     */
    fun find(project: Project, reference: String, support: LanguageSupport): FlowLookup? {
        val trimmed = reference.trim()
        if ("::" !in trimmed) return null
        val modulePart = trimmed.substringBefore("::")
        if ('\\' in modulePart) return null
        val module = PythonBlockBuilder.moduleByName(project, modulePart)
            ?: return FlowLookup.Missing("$trimmed (no such module)")
        val name = trimmed.substringAfter("::").removeSuffix("()")
        fun missing(why: String) = FlowLookup.Missing("$trimmed ($why)", ownerFound = true)
        if ('.' !in name) {
            module.statements.filterIsInstance<PyFunction>().firstOrNull { it.name == name }
                ?.let { return FlowLookup.Found(target(module, null, it, support)) }
            val cls = topLevelClass(module, name) ?: return missing("no such function")
            return FlowLookup.Found(target(module, cls, null, support))
        }
        val cls = topLevelClass(module, name.substringBefore('.')) ?: return missing("no such class")
        val member = name.substringAfter('.')
        if (member == "__init__") return FlowLookup.Found(constructor(module, cls, support))
        val method = PythonBlockBuilder.ownMethods(cls).firstOrNull { it.name == member }
            ?: cls.findMethodByName(member, true, TypeEvalContexts.codeAnalysis(project, module))
            ?: return missing("no such method")
        if (!PythonBlockBuilder.isCallee(method)) return missing("not in a block of the project's own code")
        return FlowLookup.Found(target(module, cls, method, support))
    }

    private fun topLevelClass(module: PyFile, name: String): PyClass? =
        module.statements.filterIsInstance<PyClass>().firstOrNull { it.name == name }

    /** A function or method; for a class, [method] is null. Keys follow the defining file (an inherited method's base). */
    private fun target(module: PyFile, cls: PyClass?, method: PyFunction?, support: LanguageSupport): FlowTarget {
        val declaringFile = method?.containingFile ?: cls?.containingFile ?: module
        return FlowTarget(
            classFqn = PythonBlockBuilder.moduleName(module),
            declaringFqn = PythonBlockBuilder.moduleName(declaringFile),
            methodKey = method?.let(PythonBlockBuilder::keyOf),
            isConstructor = false,
            file = declaringFile,
            classElement = cls ?: module,
            methodElement = method,
            support = support,
        )
    }

    /** `Class.__init__`, written or not: the class block, with a method key so it can be an entry. */
    private fun constructor(module: PyFile, cls: PyClass, support: LanguageSupport): FlowTarget {
        val name = PythonBlockBuilder.moduleName(module)
        return FlowTarget(
            classFqn = name,
            declaringFqn = name,
            methodKey = "${cls.name}.__init__",
            isConstructor = true,
            file = module,
            classElement = cls,
            methodElement = PythonBlockBuilder.ownMethods(cls).firstOrNull { it.name == "__init__" },
            support = support,
        )
    }

    fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean {
        val fromFunction = from.methodElement.takeUnless { from.isConstructor } as? PyFunction
        val toFunction = to.methodElement.takeUnless { to.isConstructor } as? PyFunction
        val toClass = classOf(to)
        val context = TypeEvalContexts.codeAnalysis(from.file.project, from.file)
        return when (kind) {
            "calls", "middleware" -> fromFunction != null && callsOrCreates(fromFunction, toFunction, toClass, context)
            "dispatches" -> fromFunction != null &&
                (callsOrCreates(fromFunction, toFunction, toClass, context) || toFunction != null && sameSignal(fromFunction, toFunction))
            "binds" -> fromFunction != null && toFunction != null && overrides(toFunction, fromFunction, context)
            "listens" -> {
                val event = classOf(from)
                toFunction != null &&
                    (event != null && takes(toFunction, event, context) || fromFunction != null && sameSignal(fromFunction, toFunction))
            }
            else -> false // "routes": nothing in a function proves a route
        }
    }

    /** The class a class (or constructor) target stands for; null for a function or method. */
    private fun classOf(target: FlowTarget): PyClass? =
        (target.classElement as? PyClass)?.takeIf { target.methodKey == null || target.isConstructor }

    private fun calls(function: PyFunction): List<PyCallExpression> =
        PsiTreeUtil.findChildrenOfType(function, PyCallExpression::class.java).filter { it !is PyDecorator }

    /** [from] calls [toFunction], or (for a class target) instantiates [toClass]. */
    private fun callsOrCreates(from: PyFunction, toFunction: PyFunction?, toClass: PyClass?, context: TypeEvalContext): Boolean {
        if (toFunction != null) {
            val resolveContext = PyResolveContext.defaultContext(context)
            return calls(from).any { call -> toFunction in call.multiResolveCalleeFunction(resolveContext) }
        }
        if (toClass == null) return false
        return calls(from).any { call ->
            val type = calleeOf(call)?.let(context::getType) as? PyClassType
            type != null && type.isDefinition && type.pyClass == toClass
        }
    }

    /**
     * A Django signal hop: [sender] calls `S.send(...)`/`S.send_robust(...)` and [handler] is decorated `@receiver(S)`
     * (or a list or tuple with S), both naming the same module-level object. Holds with Signal as library code.
     */
    private fun sameSignal(sender: PyFunction, handler: PyFunction): Boolean {
        val received = receivedSignals(handler)
        return received.isNotEmpty() && sentSignals(sender).any { it in received }
    }

    private fun sentSignals(function: PyFunction): List<PyTargetExpression> =
        calls(function).mapNotNull { call ->
            val callee = calleeOf(call) as? PyReferenceExpression ?: return@mapNotNull null
            if (callee.text.substringAfterLast('.').trim() !in setOf("send", "send_robust")) return@mapNotNull null
            PsiTreeUtil.getChildOfType(callee, PyExpression::class.java)?.let(::moduleObject)
        }

    private fun receivedSignals(handler: PyFunction): Set<PyTargetExpression> {
        val decorators = PsiTreeUtil.getChildOfType(handler, PyDecoratorList::class.java)
            ?.let { PsiTreeUtil.getChildrenOfTypeAsList(it, PyDecorator::class.java) }.orEmpty()
        return decorators
            .filter { it.text.removePrefix("@").substringBefore('(').trim().substringAfterLast('.') == "receiver" }
            .flatMap { decorator ->
                // The arguments sit in a call inside the decorator (`receiver(order_placed)`).
                val arguments = PsiTreeUtil.findChildOfType(decorator, PyArgumentList::class.java) ?: return@flatMap emptyList()
                val signal = PsiTreeUtil.getChildrenOfTypeAsList(arguments, PyExpression::class.java)
                    .firstOrNull { it !is PyKeywordArgument } ?: return@flatMap emptyList()
                val signals = if (signal is PyListLiteralExpression || signal is PyTupleExpression) {
                    PsiTreeUtil.getChildrenOfTypeAsList(signal, PyExpression::class.java)
                } else {
                    listOf(signal)
                }
                signals.mapNotNull(::moduleObject)
            }
            .toSet()
    }

    /** The module-level name [expression] refers to (`order_placed`, `events.order_placed`), through imports. */
    private fun moduleObject(expression: PyExpression): PyTargetExpression? {
        val reference = expression as? PyReferenceExpression ?: return null
        // With a type context, so a qualified `events.order_placed` resolves through the imported module.
        val context = TypeEvalContexts.codeAnalysis(reference.project, reference.containingFile)
        val target = reference.getReference(PyResolveContext.defaultContext(context)).resolve() as? PyTargetExpression ?: return null
        return target.takeIf { it.parent?.parent is PyFile }
    }

    /** [to] overrides [from]: same name, in a subclass of [from]'s class. */
    private fun overrides(to: PyFunction, from: PyFunction, context: TypeEvalContext): Boolean {
        if (to == from || to.name != from.name) return false
        val toClass = to.containingClass ?: return false
        val fromClass = from.containingClass ?: return false
        return toClass != fromClass && toClass.isSubclass(fromClass, context)
    }

    /** [handler]'s first parameter after `self`/`cls` is annotated with [event] (`def on(self, event: OrderPlaced)`). */
    private fun takes(handler: PyFunction, event: PyClass, context: TypeEvalContext): Boolean {
        val parameters = PsiTreeUtil.getChildOfType(handler, PyParameterList::class.java)?.parameters.orEmpty().toList()
        val isStatic = "staticmethod" in PythonBlockBuilder.decoratorNames(handler)
        val first = (if (handler.containingClass != null && !isStatic) parameters.drop(1) else parameters).firstOrNull() ?: return false
        val annotation = PythonBlockBuilder.annotationOf(first as PsiElement) ?: return false
        return event in PythonBlockBuilder.annotatedClasses(annotation, context)
    }

    private fun calleeOf(call: PyCallExpression): PyExpression? = PsiTreeUtil.getChildOfType(call, PyExpression::class.java)
}
