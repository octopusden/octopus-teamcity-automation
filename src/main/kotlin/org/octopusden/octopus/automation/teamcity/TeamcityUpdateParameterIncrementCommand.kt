package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterCommand.Companion.UpdateParameterConfig
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.slf4j.Logger

class TeamcityUpdateParameterIncrementCommand : CliktCommand(name = COMMAND) {
    private val context by requireObject<MutableMap<String, Any>>()

    private val current by option(
        CURRENT_OPTION,
        help = "Configures additional check, optional. If defined, incrementation is performed only if '--current' contains all components of current value of the parameter (for instance, if --current=1.2.7 and parameter value is 1.2, it will be incremented to 1.3)"
    ).convert { it.trim() }.default("")

    override fun run() {
        val config = context[TeamcityUpdateParameterCommand.CONFIG] as UpdateParameterConfig
        config.projectIds.forEach {
            increment(ConfigurationType.PROJECT, it, config.name)
        }
        config.buildTypeIds.forEach {
            increment(ConfigurationType.BUILD_TYPE, it, config.name)
        }
    }

    private fun increment(type: ConfigurationType, id: String, name: String) {
        val componentDelimiters = "[.-]".toRegex()
        val typeName = when (type) {
            ConfigurationType.PROJECT -> "project"
            ConfigurationType.BUILD_TYPE -> "build configuration"
        }
        val warn = "Skip incrementation of parameter $name for $typeName with id $id"
        val log = context[TeamcityCommand.LOG] as Logger
        val client = context[TeamcityCommand.CLIENT] as TeamcityClient
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

    companion object {
        const val COMMAND = "increment"
        const val CURRENT_OPTION = "--current"
    }
}