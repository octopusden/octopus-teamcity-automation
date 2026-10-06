package org.octopusden.octopus.automation.teamcity.parameter

import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.slf4j.LoggerFactory

/** Sets or increments a TeamCity parameter on projects and build configurations. */
class ParameterUpdater(
    private val client: TeamcityClient,
) {
    private val log = LoggerFactory.getLogger(ParameterUpdater::class.java)

    fun set(
        targets: ParameterTargets,
        value: String,
    ) {
        targets.projectIds.forEach {
            log.info("Set parameter ${targets.name} value $value for project with id $it")
            client.setParameter(ConfigurationType.PROJECT, it, targets.name, value)
        }
        targets.buildTypeIds.forEach {
            log.info("Set parameter ${targets.name} value $value for build configuration with id $it")
            client.setParameter(ConfigurationType.BUILD_TYPE, it, targets.name, value)
        }
    }

    /**
     * Increments the last numeric component of the parameter's value (`1.2` -> `1.3`, `1.2-7` -> `1.2-8`).
     * With a non-empty [current], increments only where [current] starts with all components of the
     * value (`current = 1.2.7` increments `1.2`, not `1.3`). A target whose value cannot be read or
     * incremented is skipped with a warning.
     */
    fun increment(
        targets: ParameterTargets,
        current: String = "",
    ) {
        targets.projectIds.forEach {
            increment(ConfigurationType.PROJECT, it, targets.name, current)
        }
        targets.buildTypeIds.forEach {
            increment(ConfigurationType.BUILD_TYPE, it, targets.name, current)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun increment(
        type: ConfigurationType,
        id: String,
        name: String,
        current: String,
    ) {
        val componentDelimiters = "[.-]".toRegex()
        val typeName = when (type) {
            ConfigurationType.PROJECT -> "project"
            ConfigurationType.BUILD_TYPE -> "build configuration"
        }
        val warn = "Skip incrementation of parameter $name for $typeName with id $id"
        val value = try {
            client.getParameter(type, id, name)
        } catch (e: Exception) {
            log.warn("$warn. Unable to retrieve value", e)
            return
        }
        val valueComponents = value.split(componentDelimiters)
        if (current.isNotEmpty() && valueComponents != current.split(componentDelimiters).take(valueComponents.size)) {
            log.warn("$warn. $current does not contain components of $value")
            return
        }
        val lastComponent = valueComponents.last()
        val incrementedLastComponent = try {
            lastComponent.toLong().inc().toString()
        } catch (e: Exception) {
            log.warn("$warn. Unable to increment last component $lastComponent of $value", e)
            return
        }
        val incrementedValue = value.removeSuffix(lastComponent) + incrementedLastComponent
        log.info("Increment value $value of parameter $name to $incrementedValue for $typeName with id $id")
        client.setParameter(type, id, name, incrementedValue)
    }
}
