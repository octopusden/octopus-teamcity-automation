package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.automation.teamcity.agent.AgentRequirementRow
import org.octopusden.octopus.automation.teamcity.agent.AgentRequirementsReport
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.slf4j.Logger
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.Writer

/**
 * Command to get agent requirements for build types in TeamCity.
 */
class TeamcityGetBuildTypesAgentRequirementsCommand : CliktCommand(name = COMMAND) {
    private val context by requireObject<MutableMap<String, Any>>()
    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }
    private val log by lazy { context[TeamcityCommand.LOG] as Logger }
    private val file by option(FILE, help = "File to save the agent requirements")
        .required()
        .check("File must be a valid path") { it.isNotBlank() }
    private val archived by option(ARCHIVED, help = "Include archived projects").flag(default = false)

    override fun run() {
        log.info("Getting agent requirements for build types")
        // Everything is fetched before the file is opened, so a TeamCity failure leaves an existing report intact.
        val rows = AgentRequirementsReport(client).collect(archived)
        BufferedWriter(FileWriter(file)).use { writeCsv(it, rows) }
    }

    private fun writeCsv(
        writer: Writer,
        rows: List<AgentRequirementRow>,
    ) {
        writer.write(
            "Project ID;Project Name;Build Type ID;Build Type Name;Agent Requirement Type;Agent Requirement Name;" +
                "Agent Requirement Value;Disabled;Paused;Archived;\n",
        )
        rows.forEach { row ->
            writer.write(
                "${row.projectId};${row.projectName};${row.buildTypeId};${row.buildTypeName};${row.type};" +
                    "${row.name};${row.value};${row.disabled};${row.paused};${row.archived};\n",
            )
        }
    }

    companion object {
        const val COMMAND = "get-build-agent-req"
        const val FILE = "--file"
        const val ARCHIVED = "--archived"
    }
}
