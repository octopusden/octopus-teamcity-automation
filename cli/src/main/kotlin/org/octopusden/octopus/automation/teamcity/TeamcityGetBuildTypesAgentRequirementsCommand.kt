package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.automation.teamcity.agent.AgentRequirementsReport
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import java.io.BufferedWriter
import java.io.FileWriter

/**
 * Command to get agent requirements for build types in TeamCity.
 */
class TeamcityGetBuildTypesAgentRequirementsCommand : CliktCommand(name = COMMAND) {
    private val context by requireObject<MutableMap<String, Any>>()
    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }
    private val file by option(FILE, help = "File to save the agent requirements")
        .required()
        .check("File must be a valid path") { it.isNotBlank() }
    private val archived by option(ARCHIVED, help = "Include archived projects").flag(default = false)

    override fun run() {
        BufferedWriter(FileWriter(file)).use { AgentRequirementsReport(client).write(it, archived) }
    }

    companion object {
        const val COMMAND = "get-build-agent-req"
        const val FILE = "--file"
        const val ARCHIVED = "--archived"
    }
}
