pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version ("1.8.0")
        id("io.github.gradle-nexus.publish-plugin") version ("1.1.0") apply (false)
    }
}

// TODO: +Project name
rootProject.name = "octopus-teamcity-automation"

include("teamcity-meta-runner")
