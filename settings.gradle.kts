pluginManagement {
    plugins {
        id("org.octopusden.octopus-release-management") version (extra["octopus-release-management.version"] as String)
        id("org.jetbrains.kotlin.jvm") version (extra["kotlin.version"] as String)
    }
}

rootProject.name = extra["projectName"] as String

