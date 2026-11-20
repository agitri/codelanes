// Kotlin support as a content module (codelanes.kotlin): its own jar, loaded only where the Kotlin plugin is,
// so IDEs without Kotlin never see a class that needs it.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

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
        intellijIdea("2026.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }
    compileOnly(project(":"))
    compileOnly(project(":java"))
}
