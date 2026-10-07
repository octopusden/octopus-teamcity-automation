package org.octopusden.octopus.automation.teamcity.agent

import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.getAgentRequirements
import org.slf4j.LoggerFactory
import java.io.Writer

/** Writes every build configuration's agent requirements as `;`-separated rows with a header. */
class AgentRequirementsReport(
    private val client: TeamcityClient,
) {
    private val log = LoggerFactory.getLogger(AgentRequirementsReport::class.java)

    /** Build configurations of archived projects are left out unless [includeArchived]. The caller closes [writer]. */
    fun write(
        writer: Writer,
        includeArchived: Boolean = false,
    ) {
        log.info("Getting agent requirements for build types")
        val buildTypes =
            client.getBuildTypesWithFields("buildType(id,projectId,projectName,name,href,paused,project(id,name,archived,href,webUrl))")
        // Header
        writer.write("Project ID;")
        writer.write("Project Name;")
        writer.write("Build Type ID;")
        writer.write("Build Type Name;")
        writer.write("Agent Requirement Type;")
        writer.write("Agent Requirement Name;")
        writer.write("Agent Requirement Value;")
        writer.write("Disabled;")
        writer.write("Paused;")
        writer.write("Archived;\n")
        // Data
        buildTypes.buildTypes
            .filter { buildType ->
                includeArchived || buildType.project?.archived != true
            }.forEach { buildType ->
                val agentRequirements = client.getAgentRequirements(buildType.id)
                agentRequirements.agentRequirements.forEach { ar ->
                    writer.write("${buildType.projectId};")
                    writer.write("${buildType.projectName};")
                    writer.write("${buildType.id};")
                    writer.write("${buildType.name};")
                    writer.write("${ar.type};")
                    val props = arrayOf("", "")
                    ar.properties.properties.forEach { arProperty ->
                        if ("property-name" == arProperty.name) {
                            props[0] = arProperty.value ?: ""
                        }
                        if ("property-value" == arProperty.name) {
                            props[1] = arProperty.value ?: ""
                        }
                    }
                    writer.write("${props[0]};")
                    writer.write("${props[1]};")
                    writer.write("${ar.disabled};")
                    writer.write("${buildType.paused};")
                    writer.write("${buildType.project?.archived};\n")
                }
            }
    }
}
