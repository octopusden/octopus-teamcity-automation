pluginManagement {
    val releaseManagementVersion = extra["octopus-release-management.version"] as String

    plugins {
        id("org.jetbrains.kotlin.jvm") version ("1.9.20")
        id("com.github.johnrengelman.shadow") version ("8.1.1")
        id("com.avast.gradle.docker-compose") version ("0.16.9")
        id("io.github.gradle-nexus.publish-plugin") version ("1.1.0")
        id("org.octopusden.octopus-release-management") version(releaseManagementVersion)
    }
}

rootProject.name = "octopus-teamcity-automation"
