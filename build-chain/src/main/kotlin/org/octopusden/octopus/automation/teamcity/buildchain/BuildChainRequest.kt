package org.octopusden.octopus.automation.teamcity.buildchain

data class BuildChainRequest(
    val parentProjectId: String,
    val componentName: String,
    val minorVersion: String,
    /** Adds the Release Checklist Validation configuration; applies only when an RC is created. */
    val createChecklist: Boolean = true,
    /** Creates the RC configuration even for a component that is not explicitly and externally distributed. */
    val createRcForce: Boolean = false,
) {
    init {
        require(parentProjectId.isNotBlank()) { "parentProjectId is blank" }
        require(componentName.isNotBlank()) { "componentName is blank" }
        require(minorVersion.isNotBlank()) { "minorVersion is blank" }
    }
}
