// Python support as a content module (codelanes.python): its own jar, loaded only where the Python plugin (PythonCore) is,
// so IDEs without Python never see a class that needs it.
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
        compatiblePlugin("PythonCore")
    }
    compileOnly(project(":"))
}
