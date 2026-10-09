package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterCommand.Companion.UpdateParameterConfig
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterCommand.Companion.typeName
import org.octopusden.octopus.automation.teamcity.parameter.IncrementResult
import org.octopusden.octopus.automation.teamcity.parameter.ParameterUpdater
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.slf4j.Logger

class TeamcityUpdateParameterIncrementCommand : CliktCommand(name = COMMAND) {
    private val context by requireObject<MutableMap<String, Any>>()

    private val current by option(
        CURRENT_OPTION,
        help = "Configures additional check, optional. If defined, incrementation is performed only if '--current' contains " +
            "all components of current value of the parameter (for instance, if --current=1.2.7 and parameter value is 1.2, " +
            "it will be incremented to 1.3)",
    ).convert { it.trim() }.default("")

    override fun run() {
        val log = context[TeamcityCommand.LOG] as Logger
        val client = context[TeamcityCommand.CLIENT] as TeamcityClient
        val config = context[TeamcityUpdateParameterCommand.CONFIG] as UpdateParameterConfig
        ParameterUpdater(client).increment(config.targets(), current) { result ->
            val target = "${result.target.typeName()} with id ${result.target.id}"
            when (result) {
                is IncrementResult.Incremented ->
                    log.info("Increment value ${result.from} of parameter ${config.name} to ${result.to} for $target")
                is IncrementResult.Skipped ->
                    log.warn("Skip incrementation of parameter ${config.name} for $target. ${result.reason}", result.cause)
            }
        }
    }

    companion object {
        const val COMMAND = "increment"
        const val CURRENT_OPTION = "--current"
    }
}
