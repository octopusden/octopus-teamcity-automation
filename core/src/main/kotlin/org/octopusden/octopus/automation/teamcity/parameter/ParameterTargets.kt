package org.octopusden.octopus.automation.teamcity.parameter

/** A parameter [name] on a set of projects and build configurations. */
data class ParameterTargets(
    val name: String,
    val projectIds: Set<String> = emptySet(),
    val buildTypeIds: Set<String> = emptySet(),
) {
    init {
        require(name.isNotBlank()) { "name is blank" }
        require(projectIds.isNotEmpty() || buildTypeIds.isNotEmpty()) { "Both projectIds and buildTypeIds are empty" }
    }
}
