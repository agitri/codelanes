package dev.codelanes.flow

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.NewExpression
import com.jetbrains.php.lang.psi.elements.PhpClass
import dev.codelanes.php.PhpBlockBuilder

/** One hop between two blocks of the entry file's canvas. [proven]: the code itself shows it, not only the AI. */
data class ResolvedHop(val fromId: String, val toId: String, val kind: String, val proven: Boolean)

/**
 * A flow checked against the code, as block ids on the canvas of [entryFile]. [added] are the `\Fqn::method` keys of
 * the methods that need a block of their own; [unknown] the references that don't exist (their hops are dropped).
 * With an [entryProblem] nothing can be shown.
 */
data class ResolvedFlow(
    val entryFile: VirtualFile?,
    val entryId: String?,
    val entryProblem: String?,
    val hops: List<ResolvedHop>,
    val added: Set<String>,
    val unknown: List<String>,
)

/**
 * Turns the AI's references (`App\Events\OrderPlaced::__construct`) into blocks and decides per hop whether the code
 * proves it. Needs a read action. Draws nothing that isn't in the code.
 */
object FlowResolver {
    fun resolve(project: Project, flow: Flow): ResolvedFlow {
        val unknown = mutableListOf<String>()
        fun lookUp(reference: String): Method? = when (val found = find(project, reference)) {
            is Found.Yes -> found.method
            is Found.No -> null.also { unknown += found.why }
        }
        val entry = when (val found = find(project, flow.entry.method)) {
            is Found.Yes -> found.method
            is Found.No -> return ResolvedFlow(null, null, found.why, emptyList(), emptySet(), emptyList())
        }
        val entryFile = entry.containingFile
        PhpBlockBuilder.unsupportedReason(entryFile)?.let { reason ->
            return ResolvedFlow(entryFile.virtualFile, null, "${entryFile.name} can't be shown as blocks: $reason", emptyList(), emptySet(), emptyList())
        }
        val openClass = entry.containingClass!!
        val added = linkedSetOf<String>()
        fun idOf(method: Method): String {
            val owner = method.containingClass!!
            if (owner.fqn == openClass.fqn) {
                return if (method.name.equals("__construct", ignoreCase = true)) "class:${owner.fqn}" else "method:${method.name}"
            }
            val key = "${owner.fqn}::${method.name}"
            added += key
            return "callee:$key"
        }
        val hops = flow.hops.mapNotNull { hop ->
            val from = lookUp(hop.from)
            val to = lookUp(hop.to)
            if (from == null || to == null) return@mapNotNull null
            ResolvedHop(idOf(from), idOf(to), hop.kind, proves(hop.kind, from, to))
        }
        return ResolvedFlow(entryFile.virtualFile, idOf(entry), null, hops.distinct(), added, unknown.distinct())
    }

    private sealed interface Found {
        data class Yes(val method: Method) : Found
        data class No(val why: String) : Found
    }

    /** `App\Foo::bar` or `\App\Foo::bar` → the method, normalised to the class that declares it. */
    private fun find(project: Project, reference: String): Found {
        val trimmed = reference.trim()
        if ("::" !in trimmed) return Found.No("$trimmed (not a Class::method reference)")
        val fqn = "\\" + trimmed.substringBefore("::").trimStart('\\')
        val name = trimmed.substringAfter("::").removeSuffix("()")
        val shown = trimmed.trimStart('\\')
        val index = PhpIndex.getInstance(project)
        val owners = index.getClassesByFQN(fqn) + index.getInterfacesByFQN(fqn) + index.getTraitsByFQN(fqn)
        if (owners.isEmpty()) return Found.No("$shown (no such class)")
        val method = owners.firstNotNullOfOrNull { it.findMethodByName(name) } ?: return Found.No("$shown (no such method)")
        return Found.Yes(method)
    }

    private fun proves(kind: String, from: Method, to: Method): Boolean = when (kind) {
        "calls", "dispatches", "middleware" -> callsOrCreates(from, to)
        "binds" -> to.name.equals(from.name, ignoreCase = true) && isSubtype(to.containingClass, from.containingClass)
        "listens" -> to.parameters.firstOrNull()?.declaredType?.types?.contains(from.containingClass?.fqn) == true
        else -> false // "routes": the route is the AI's (and the person's) word, nothing in a method proves it
    }

    /** [from] calls [to], or creates an instance of [to]'s class (`event(new OrderPlaced())`). */
    private fun callsOrCreates(from: Method, to: Method): Boolean {
        val calls = PsiTreeUtil.findChildrenOfType(from, MethodReference::class.java)
            .any { call -> call.multiResolve(false).any { it.element == to } }
        if (calls) return true
        val target = to.containingClass?.fqn ?: return false
        return PsiTreeUtil.findChildrenOfType(from, NewExpression::class.java).any { created ->
            when (val resolved = created.classReference?.resolve()) {
                is PhpClass -> resolved.fqn == target
                is Method -> resolved.containingClass?.fqn == target
                else -> false
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
