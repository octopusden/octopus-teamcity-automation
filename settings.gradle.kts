pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version (extra["kotlin.version"] as String)
    }
}

rootProject.name = extra["projectName"] as String

