// PHP support as a content module (codelanes.php): its own jar, loaded only where the PHP plugin is,
// so IDEs without PHP never see a class that needs it.
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
        compatiblePlugin("com.jetbrains.php")
    }
    compileOnly(project(":"))
}
