package dev.codelanes.java

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import dev.codelanes.lang.EntryPoint

/**
 * Spring MVC routes: `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping` and
 * `@RequestMapping` (its `method = …`, else ANY) on methods, with a `@RequestMapping` prefix on the class. Several
 * paths give several routes; constant paths are evaluated, anything else is shown as `?`. Annotations match by
 * short name, so a project whose Spring jar isn't indexed yet still gets its routes.
 */
object JavaRouteFinder {
    private val VERB_MAPPINGS = mapOf(
        "GetMapping" to "GET", "PostMapping" to "POST", "PutMapping" to "PUT",
        "DeleteMapping" to "DELETE", "PatchMapping" to "PATCH",
    )
    private const val REQUEST_MAPPING = "RequestMapping"
    private val VERBS = Regex("""\b(GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS|TRACE)\b""")

    private data class Mapping(val paths: List<String>, val verbs: List<String>)

    /** Needs indexes and a read action; checks for cancellation. Sorted by path, then label. */
    fun find(project: Project): List<EntryPoint> {
        val files = ProjectFileIndex.getInstance(project)
        val candidates = linkedSetOf<PsiFile>()
        // Only files that mention a mapping annotation: no need to parse the rest of the project.
        val search = PsiSearchHelper.getInstance(project)
        for (word in VERB_MAPPINGS.keys + REQUEST_MAPPING) {
            search.processAllFilesWithWord(word, GlobalSearchScope.projectScope(project), { candidates += it; true }, true)
        }
        val found = mutableListOf<Pair<String, EntryPoint>>()
        for (file in candidates) {
            ProgressManager.checkCanceled()
            if (file !is PsiJavaFile) continue
            val virtualFile = file.virtualFile ?: continue
            if (files.isInLibrary(virtualFile)) continue
            for (cls in file.classes) {
                if (cls.isInterface || cls.isAnnotationType || cls.hasModifierProperty(PsiModifier.ABSTRACT)) continue // mapped on implementations
                val prefixes = annotation(cls, REQUEST_MAPPING)?.let(::pathsOf) ?: listOf("")
                for (method in JavaBlockBuilder.ownMethods(cls)) {
                    val mapping = mappingOf(method) ?: continue
                    val verbs = mapping.verbs.ifEmpty { listOf("ANY") }.joinToString("|")
                    for (prefix in prefixes) for (path in mapping.paths) {
                        val full = join(prefix, path)
                        found += full to EntryPoint("$verbs $full → ${cls.name}::${method.name}", virtualFile.path, JavaBlockBuilder.methodKey(method))
                    }
                }
            }
        }
        return found.distinctBy { it.second.label }.sortedWith(compareBy({ it.first }, { it.second.label })).map { it.second }
    }

    private fun mappingOf(method: PsiModifierListOwner): Mapping? {
        for (candidate in method.annotations) {
            val name = shortName(candidate)
            VERB_MAPPINGS[name]?.let { return Mapping(pathsOf(candidate), listOf(it)) }
            if (name == REQUEST_MAPPING) {
                val verbs = candidate.findDeclaredAttributeValue("method")?.text?.let { text -> VERBS.findAll(text).map { it.value }.distinct().toList() }
                return Mapping(pathsOf(candidate), verbs.orEmpty())
            }
        }
        return null
    }

    private fun annotation(owner: PsiModifierListOwner, name: String): PsiAnnotation? = owner.annotations.firstOrNull { shortName(it) == name }

    private fun shortName(annotation: PsiAnnotation): String? =
        (annotation.qualifiedName ?: annotation.nameReferenceElement?.referenceName)?.substringAfterLast('.')

    /** `value` or `path`: one string, an array of them, or none (""). */
    private fun pathsOf(annotation: PsiAnnotation): List<String> {
        val value = annotation.findDeclaredAttributeValue("value") ?: annotation.findDeclaredAttributeValue("path") ?: return listOf("")
        val values = if (value is PsiArrayInitializerMemberValue) value.initializers.toList() else listOf(value)
        return values.map(::stringOf).ifEmpty { listOf("") }
    }

    private fun stringOf(value: PsiAnnotationMemberValue): String {
        val expression = value as? PsiExpression ?: return "?"
        return JavaPsiFacade.getInstance(value.project).constantEvaluationHelper.computeConstantExpression(expression) as? String ?: "?"
    }

    private fun join(prefix: String, path: String): String {
        if (prefix == "?" || path == "?") return "?"
        return "/" + listOf(prefix, path).map { it.trim('/') }.filter { it.isNotEmpty() }.joinToString("/")
    }
}
