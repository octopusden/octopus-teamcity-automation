package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterCommand.Companion.UpdateParameterConfig
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.slf4j.Logger

class TeamcityUpdateParameterSetCommand : CliktCommand(name = COMMAND) {
    private val value by option(VALUE_OPTION, help = "TeamCity parameter value").convert { it.trim() }.required()
        .check("$VALUE_OPTION is empty") { it.isNotEmpty() }

    private val context by requireObject<MutableMap<String, Any>>()

    override fun run() {
        val log = context[TeamcityCommand.LOG] as Logger
        val client = context[TeamcityCommand.CLIENT] as TeamcityClient
        val config = context[TeamcityUpdateParameterCommand.CONFIG] as UpdateParameterConfig
        config.projectIds.forEach {
            log.info("Set parameter ${config.name} = $value for project with id $it")
            client.setParameter(ConfigurationType.PROJECT, it, config.name, value)
        }
        config.buildTypeIds.forEach {
            log.info("Set parameter ${config.name} value $value for build configuration with id $it")
            client.setParameter(ConfigurationType.BUILD_TYPE, it, config.name, value)
        }
    }

    companion object {
        const val COMMAND = "set"
        const val VALUE_OPTION = "--value"
    }
}