package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterCommand.Companion.UpdateParameterConfig
import org.octopusden.octopus.automation.teamcity.parameter.ParameterUpdater
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient

class TeamcityUpdateParameterIncrementCommand : CliktCommand(name = COMMAND) {
    private val context by requireObject<MutableMap<String, Any>>()

    private val current by option(
        CURRENT_OPTION,
        help = "Configures additional check, optional. If defined, incrementation is performed only if '--current' contains " +
            "all components of current value of the parameter (for instance, if --current=1.2.7 and parameter value is 1.2, " +
            "it will be incremented to 1.3)",
    ).convert { it.trim() }.default("")

    override fun run() {
        val client = context[TeamcityCommand.CLIENT] as TeamcityClient
        val config = context[TeamcityUpdateParameterCommand.CONFIG] as UpdateParameterConfig
        ParameterUpdater(client).increment(config.targets(), current)
    }

    companion object {
        const val COMMAND = "increment"
        const val CURRENT_OPTION = "--current"
    }
}
