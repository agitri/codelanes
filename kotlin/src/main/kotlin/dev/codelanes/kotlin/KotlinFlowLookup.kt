package dev.codelanes.kotlin

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import dev.codelanes.java.JavaBlockBuilder
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Kotlin flow references: `acme.NoteViewModel::save` (a member, `Companion.create` for a companion function), the
 * module (package plus file name) for a top-level function, `acme.notes.NoteScreen::NoteScreen`; `::class`, `::<init>`
 * or the class's own name stand for the class.
 */
object KotlinFlowLookup {
    /**
     * Null when the class part has a backslash (PHP's syntax), or when nothing matches in a project without `.kt`
     * files, so another language's reason is shown. A class wins over a file of the same fqn if it declares the name.
     */
    fun find(project: Project, reference: String, support: LanguageSupport): FlowLookup? {
        val trimmed = reference.trim()
        val owner = trimmed.substringBefore("::")
        if ('\\' in owner) return null
        val name = trimmed.substringAfter("::")
        val cls = kotlinClass(project, owner)
        if (cls != null) {
            if (cls.parent !is KtFile) return FlowLookup.Missing("$trimmed (nested classes aren't shown as blocks)", ownerFound = true)
            if (name == "class" || name == "<init>" || name.substringBefore('(') == cls.name) return FlowLookup.Found(classTarget(cls, support))
            memberOf(cls, name)?.let { (declaring, function) -> return FlowLookup.Found(memberTarget(cls, declaring, function, support)) }
        }
        val module = modules(project, owner)
        topLevel(module, name)?.let { (file, function) -> return FlowLookup.Found(topLevelTarget(file, function, support)) }
        if (cls != null || module.isNotEmpty()) return FlowLookup.Missing("$trimmed (no such method)", ownerFound = true)
        return if (hasKotlinFiles(project)) FlowLookup.Missing("$trimmed (no such class)") else null
    }

    private fun hasKotlinFiles(project: Project) =
        !FileTypeIndex.processFiles(KotlinFileType.INSTANCE, { false }, GlobalSearchScope.projectScope(project))

    private fun kotlinClass(project: Project, fqn: String): KtClassOrObject? =
        com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))?.navigationElement as? KtClassOrObject

    /** The project's `.kt` files whose package plus name is [fqn]. */
    private fun modules(project: Project, fqn: String): List<KtFile> {
        val file = fqn.substringAfterLast('.')
        return FilenameIndex.getVirtualFilesByName("$file.kt", GlobalSearchScope.projectScope(project))
            .mapNotNull { PsiManager.getInstance(project).findFile(it) as? KtFile }
            .filter { KotlinBlockBuilder.moduleFqn(it) == fqn }
    }

    private fun topLevel(files: List<KtFile>, name: String): Pair<KtFile, KtNamedFunction>? =
        files.firstNotNullOfOrNull { file ->
            KotlinBlockBuilder.canvasFunctions(file).filter { it.parent is KtFile }
                .firstOrNull { matches(it, name) }?.let { file to it }
        }

    /** `save`, `Companion.create` or `save(Note)`-less name of a function: its key, else its plain name. */
    private fun matches(function: KtNamedFunction, name: String): Boolean {
        val bare = name.substringBefore('(')
        return KotlinBlockBuilder.functionKey(function).substringAfterLast("::") == bare ||
            KotlinBlockBuilder.functionKey(function).substringAfter('.', "").ifEmpty { KotlinBlockBuilder.functionKey(function) } == bare ||
            function.name == bare
    }

    /** The function of [cls] (or of the nearest Kotlin supertype that declares it) with its declaring class. */
    private fun memberOf(cls: KtClassOrObject, name: String): Pair<KtClassOrObject, KtNamedFunction>? {
        val seen = mutableSetOf<KtClassOrObject>()
        val queue = ArrayDeque(listOf(cls))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            val declared = KotlinBlockBuilder.declaredFunctions(next)
            (declared.firstOrNull { KotlinBlockBuilder.functionKey(it).substringAfter('.') == name.substringBefore('(') } ?: declared.firstOrNull { matches(it, name) })
                ?.let { return next to it }
            queue += next.toLightClass()?.supers.orEmpty().mapNotNull { it.navigationElement as? KtClassOrObject }
        }
        return null
    }

    private fun classTarget(cls: KtClassOrObject, support: LanguageSupport): FlowTarget {
        val fqn = cls.fqName?.asString() ?: cls.name.orEmpty()
        return FlowTarget(fqn, fqn, null, false, cls.containingKtFile, cls, null, support)
    }

    private fun memberTarget(named: KtClassOrObject, declaring: KtClassOrObject, function: KtNamedFunction, support: LanguageSupport): FlowTarget {
        val outer = generateSequence<KtClassOrObject>(declaring) { it.parent?.parent as? KtClassOrObject }.last()
        return FlowTarget(
            classFqn = named.fqName?.asString().orEmpty(),
            declaringFqn = outer.fqName?.asString().orEmpty(),
            methodKey = KotlinBlockBuilder.functionKey(function),
            isConstructor = false,
            file = declaring.containingKtFile,
            classElement = outer,
            methodElement = function,
            support = support,
        )
    }

    private fun topLevelTarget(file: KtFile, function: KtNamedFunction, support: LanguageSupport): FlowTarget {
        val module = KotlinBlockBuilder.moduleFqn(file)
        return FlowTarget(module, module, KotlinBlockBuilder.functionKey(function), false, file, file, function, support)
    }

    fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean {
        val fromFunction = from.methodElement as? KtNamedFunction
        return when (kind) {
            "calls", "dispatches", "middleware" -> fromFunction != null && callsOrCreates(fromFunction, to)
            "binds" -> fromFunction != null && to.methodElement != null && fromFunction != to.methodElement &&
                DefinitionsScopedSearch.search(fromFunction, GlobalSearchScope.projectScope(fromFunction.project)).findAll()
                    .any { it.navigationElement == to.methodElement?.navigationElement }
            "listens" -> eventParameter(to.methodElement)?.let { KotlinBlockBuilder.fqnOf(it) == from.classFqn } == true
            else -> false // "routes": nothing in a function proves a route
        }
    }

    private fun eventParameter(method: PsiElement?): PsiElement? = when (method) {
        is KtNamedFunction -> KotlinBlockBuilder.resolveType(method.valueParameters.firstOrNull()?.typeReference)
        is com.intellij.psi.PsiMethod -> (method.parameterList.parameters.firstOrNull()?.type as? PsiClassType)?.resolve()
        else -> null
    }

    /** [from]'s body (lambdas included) calls the function of [to] (Kotlin or Java, by navigation element) or constructs its class. */
    private fun callsOrCreates(from: KtNamedFunction, to: FlowTarget): Boolean {
        val references = PsiTreeUtil.findChildrenOfType(from, KtCallExpression::class.java).mapNotNull { it.calleeExpression } +
            PsiTreeUtil.findChildrenOfType(from, KtCallableReferenceExpression::class.java).map { it.callableReference }
        val targets = references.mapNotNull { e -> e.references.firstNotNullOfOrNull { it.resolve() } }
        val toElement = to.methodElement
        if (toElement != null) {
            val source = toElement.navigationElement
            return targets.any { it == toElement || it.navigationElement == source }
        }
        return targets.any { classOf(it) == to.classFqn }
    }

    /** The fqn of the class a call target constructs or names, or null. */
    private fun classOf(target: PsiElement): String? = when (target) {
        is KtConstructor<*> -> target.getContainingClassOrObject().fqName?.asString()
        is KtClassOrObject -> target.fqName?.asString()
        is com.intellij.psi.PsiMethod -> target.takeIf { it.isConstructor }?.containingClass?.let(JavaBlockBuilder::fqn)
        is PsiClass -> JavaBlockBuilder.fqn(target)
        else -> null
    }
}
