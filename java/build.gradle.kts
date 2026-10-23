// Java support as a content module (codelanes.java): its own jar, loaded only where the Java plugin is,
// so IDEs without Java never see a class that needs it.
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
    }
    compileOnly(project(":"))
}
