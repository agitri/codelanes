import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.codelanes"
version = "0.3.0"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA with the PHP plugin: one sandbox and one test run cover both languages.
        intellijIdea("2026.1")
        bundledPlugin("com.intellij.java") // the tests need Java; the product loads it via the :java content module
        compatiblePlugin("com.jetbrains.php") // the tests need PHP; the product loads it via the :php content module
        compatiblePlugin("PythonCore") // the tests need Python; the product loads it via the :python content module
        bundledPlugin("com.intellij.mcpServer") // optional at runtime, see codelanes-mcp.xml
        pluginModule(project(":php"))
        pluginModule(project(":java"))
        pluginModule(project(":python"))
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation(project(":php"))
    testImplementation(project(":java"))
    testImplementation(project(":python"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252" // 2025.2: the first IDE versions with the MCP server that flows use
            untilBuild = provider { null } // no upper bound: new PhpStorm versions shouldn't lock the plugin out
        }
        changeNotes = provider { file("CHANGELOG.md").readText().substringAfter("## ${project.version}").substringBefore("\n## ").let(::markdownToHtml) }
    }

    // Signing and publishing read secrets from the environment only, never from files in this repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // `./gradlew verifyPlugin` checks API compatibility with the supported PhpStorm, IntelliJ IDEA and PyCharm versions.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.PhpStorm, "2025.2")
            create(IntelliJPlatformType.PhpStorm, "2025.3")
            create(IntelliJPlatformType.PhpStorm, "2026.1")
            create(IntelliJPlatformType.PhpStorm, "2026.2")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.2") // the last split Ultimate/Community release
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2") // no PHP: the case Task 2 makes installable
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
            create(IntelliJPlatformType.PyCharm, "2025.2")
            create(IntelliJPlatformType.PyCharm, "2025.3")
            create(IntelliJPlatformType.PyCharm, "2026.1")
            create(IntelliJPlatformType.PyCharm, "2026.2")
        }
    }
}

// `./gradlew runIde` starts IntelliJ IDEA with Java and Python (its PHP plugin needs an Ultimate licence, which the
// sandbox lacks). `./gradlew runPhpStorm` starts PhpStorm, to try the PHP side; `./gradlew runPyCharm` starts PyCharm.
intellijPlatformTesting {
    runIde {
        register("runPhpStorm") {
            type = IntelliJPlatformType.PhpStorm
            version = "2026.1"
        }
        register("runPyCharm") {
            type = IntelliJPlatformType.PyCharm
            version = "2026.1"
        }
    }
}

/** Minimal Markdown → HTML for the Marketplace change notes (bullets and paragraphs). */
fun markdownToHtml(markdown: String): String {
    val html = StringBuilder()
    var inList = false
    // Indented lines continue the bullet or paragraph above them.
    val lines = markdown.trim().lines().fold(mutableListOf<String>()) { acc, raw ->
        if (raw.startsWith(" ") && acc.isNotEmpty() && acc.last().isNotBlank()) acc[acc.size - 1] = acc.last() + " " + raw.trim() else acc += raw
        acc
    }
    for (raw in lines) {
        val line = raw.trim()
        val text = line.removePrefix("- ").replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>").replace(Regex("`(.+?)`"), "<code>$1</code>")
        when {
            line.startsWith("- ") -> {
                if (!inList) html.append("<ul>")
                inList = true
                html.append("<li>").append(text).append("</li>")
            }
            line.isBlank() -> if (inList) { html.append("</ul>"); inList = false }
            else -> html.append("<p>").append(text).append("</p>")
        }
    }
    if (inList) html.append("</ul>")
    return html.toString()
}
