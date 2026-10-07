package org.octopusden.octopus.automation.teamcity.agent

/**
 * One agent requirement of one build configuration. [name] and [value] are the requirement's
 * `property-name` and `property-value`, empty when absent; the other fields are as TeamCity reports them.
 */
data class AgentRequirementRow(
    val projectId: String?,
    val projectName: String?,
    val buildTypeId: String,
    val buildTypeName: String?,
    val type: String?,
    val name: String,
    val value: String,
    val disabled: Boolean?,
    val paused: Boolean?,
    val archived: Boolean?,
)
