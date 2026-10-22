package dev.codelanes.php

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import dev.codelanes.lang.EntryPoint
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.php.PhpIndex
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One route from a Symfony route file: `path`, HTTP verbs and `Fqcn::method` (or `Fqcn` for `__invoke`). */
data class RouteDef(val path: String, val verbs: List<String>, val controller: String)

/**
 * Routes from Symfony's `config/routes.yaml` and the `.yaml`, `.yml` and `.xml` files under `config/routes/`.
 * Imports (`resource:`) are skipped; their prefixes aren't applied.
 */
object RouteFiles {
    fun parseYaml(text: String): List<RouteDef> {
        val routes = mutableListOf<RouteDef>()
        var path: String? = null
        var controller: String? = null
        var verbs = emptyList<String>()
        fun flush() {
            val p = path
            val c = controller
            if (p != null && c != null) routes += RouteDef(p, verbs, c)
            path = null
            controller = null
            verbs = emptyList()
        }
        for (raw in text.lines()) {
            val line = raw.substringBefore(" #").trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (!line.first().isWhitespace()) {
                flush() // a new top-level route name
                continue
            }
            val key = line.trim().substringBefore(':').trim()
            val value = unquote(line.trim().substringAfter(':', "").trim())
            when (key) {
                "path" -> path = value
                "controller", "_controller" -> controller = value
                "methods" -> verbs = verbsOf(value)
                "defaults" -> Regex("""_controller\s*:\s*([^,}]+)""").find(value)?.let { controller = unquote(it.groupValues[1].trim()) }
            }
        }
        flush()
        return routes
    }

    fun parseXml(text: String): List<RouteDef> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Route files come from the project: never resolve external entities or a DTD.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = runCatching { factory.newDocumentBuilder().parse(text.trim().byteInputStream()) }.getOrNull() ?: return emptyList()
        val nodes = document.getElementsByTagNameNS("*", "route")
        return (0 until nodes.length).mapNotNull { i ->
            val route = nodes.item(i) as? Element ?: return@mapNotNull null
            val path = route.getAttribute("path").ifEmpty { return@mapNotNull null }
            val defaults = route.getElementsByTagNameNS("*", "default")
            val fromDefault = (0 until defaults.length).map { defaults.item(it) as Element }
                .firstOrNull { it.getAttribute("key") == "_controller" }?.textContent?.trim()
            val controller = route.getAttribute("controller").ifEmpty { fromDefault ?: return@mapNotNull null }
            RouteDef(path, verbsOf(route.getAttribute("methods")), controller)
        }
    }

    /** Entry points (with their path, for sorting) for every route file route whose controller method exists. */
    internal fun find(project: Project): List<Pair<String, EntryPoint>> {
        val scope = GlobalSearchScope.projectScope(project)
        val files = ProjectFileIndex.getInstance(project)
        val index = PhpIndex.getInstance(project)
        val result = mutableListOf<Pair<String, EntryPoint>>()
        for (ext in listOf("yaml", "yml", "xml")) {
            for (file in FilenameIndex.getAllFilesByExt(project, ext, scope)) {
                ProgressManager.checkCanceled()
                if (!file.path.contains("/config/routes") || files.isInLibrary(file)) continue
                val text = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: continue
                val routes = if (ext == "xml") parseXml(text) else parseYaml(text)
                for (route in routes) {
                    val fqn = "\\" + route.controller.substringBefore("::").trimStart('\\')
                    val methodName = route.controller.substringAfter("::", "__invoke")
                    val phpClass = index.getClassesByFQN(fqn).firstOrNull { it.findOwnMethodByName(methodName) != null } ?: continue
                    val target = phpClass.containingFile?.virtualFile ?: continue
                    val verbs = route.verbs.ifEmpty { listOf("ANY") }.joinToString("|")
                    result += route.path to EntryPoint("$verbs ${route.path} → ${phpClass.name}::$methodName", target.path, methodName)
                }
            }
        }
        return result
    }

    private fun verbsOf(value: String): List<String> =
        Regex("[A-Za-z]+").findAll(value).map { it.value.uppercase() }.distinct().toList()

    private fun unquote(value: String): String = value.trim().removeSurrounding("'").removeSurrounding("\"")
}
