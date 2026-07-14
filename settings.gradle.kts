import java.net.InetAddress
import java.util.zip.CRC32

pluginManagement {
    val releaseManagementVersion = extra["octopus-release-management.version"] as String
    val ocTemplatePluginVersion = extra["octopus-oc-template.version"] as String

    plugins {
        id("org.jetbrains.kotlin.jvm") version ("1.9.20")
        id("com.gradleup.shadow") version ("8.3.6")
        id("com.avast.gradle.docker-compose") version ("0.16.9")
        id("io.github.gradle-nexus.publish-plugin") version ("1.1.0")
        id("org.octopusden.octopus-release-management") version (releaseManagementVersion)
        id("org.octopusden.octopus.oc-template") version (ocTemplatePluginVersion)
        id("io.gitlab.arturbosch.detekt") version (extra["detekt.version"] as String)
        id("org.jlleitschuh.gradle.ktlint") version (extra["ktlint-gradle.version"] as String)
        id("org.octopusden.octopus-quality") version "2.4.0"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "octopus-teamcity-automation"

gradle.beforeProject {
    project.version = gradle.startParameter.projectProperties["version"] ?: with(CRC32()) {
        update(InetAddress.getLocalHost().hostName.toByteArray())
        "$value-SNAPSHOT"
    }
}
