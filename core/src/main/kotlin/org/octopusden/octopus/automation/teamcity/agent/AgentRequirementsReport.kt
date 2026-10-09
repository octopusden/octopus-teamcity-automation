package org.octopusden.octopus.automation.teamcity.agent

import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.getAgentRequirements

/** Collects every build configuration's agent requirements. */
class AgentRequirementsReport(
    private val client: TeamcityClient,
) {
    /** Build configurations of archived projects are left out unless [includeArchived]. */
    fun collect(includeArchived: Boolean = false): List<AgentRequirementRow> =
        client
            .getBuildTypesWithFields("buildType(id,projectId,projectName,name,href,paused,project(id,name,archived,href,webUrl))")
            .buildTypes
            .filter { buildType -> includeArchived || buildType.project?.archived != true }
            .flatMap { buildType ->
                client.getAgentRequirements(buildType.id).agentRequirements.map { requirement ->
                    val properties = requirement.properties.properties
                    AgentRequirementRow(
                        projectId = buildType.projectId,
                        projectName = buildType.projectName,
                        buildTypeId = buildType.id,
                        buildTypeName = buildType.name,
                        type = requirement.type,
                        name = properties.lastOrNull { it.name == "property-name" }?.value ?: "",
                        value = properties.lastOrNull { it.name == "property-value" }?.value ?: "",
                        disabled = requirement.disabled,
                        paused = buildType.paused,
                        archived = buildType.project?.archived,
                    )
                }
            }
}
