package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.BufferedWriter
import java.io.FileWriter
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.getAgentRequirements
import org.slf4j.Logger

/**
 * Command to get agent requirements for build types in TeamCity.
 */
class TeamcityGetBuildTypesAgentRequirementsCommand :
    CliktCommand(name = COMMAND) {

    private val context by requireObject<MutableMap<String, Any>>()
    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }
    private val log by lazy { context[TeamcityCommand.LOG] as Logger }
    private val file by option(FILE, help = "File to save the agent requirements").required()
        .check("File must be a valid path") { it.isNotBlank() }

    override fun run() {
        log.info("Getting agent requirements for build types")
        val buildTypes = client.getBuildTypes()
        val bufferWriter = BufferedWriter(FileWriter(file))
        //Header
        bufferWriter.write("Project ID;")
        bufferWriter.write("Project Name;")
        bufferWriter.write("Build Type ID;")
        bufferWriter.write("Build Type Name;")
        bufferWriter.write("Agent Requirement Type;")
        bufferWriter.write("Agent Requirement Name;")
        bufferWriter.write("Agent Requirement Value;\n")
        //Data
        bufferWriter.use { bufferWriter ->
            buildTypes.buildTypes
                .forEach() { buildType ->
                    val agentRequirements = client.getAgentRequirements(buildType.id)
                    agentRequirements.agentRequirements.forEach { ar ->
                        bufferWriter.write("${buildType.projectId};")
                        bufferWriter.write("${buildType.projectName};")
                        bufferWriter.write("${buildType.id};")
                        bufferWriter.write("${buildType.name};")
                        bufferWriter.write("${ar.type};")
                        val props = arrayOf("", "")
                        ar.properties.properties.forEach() { arProperty ->
                            if ("property-name" == arProperty.name) {
                                props[0] = arProperty.value ?: ""
                            }
                            if ("property-value" == arProperty.name) {
                                props[1] = arProperty.value ?: ""
                            }
                        }
                        bufferWriter.write("${props[0]};")
                        bufferWriter.write("${props[1]};\n")
                    }
                }
        }
    }

    companion object {
        const val COMMAND = "get-build-agent-req"
        const val FILE = "--file"
    }
}