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
        requireTrimmed("parentProjectId", parentProjectId)
        requireTrimmed("componentName", componentName)
        requireTrimmed("minorVersion", minorVersion)
    }

    // The component name becomes the TeamCity project name, so surrounding whitespace would end up in it.
    private fun requireTrimmed(
        name: String,
        value: String,
    ) {
        require(value.isNotBlank()) { "$name is blank" }
        require(value == value.trim()) { "$name has leading or trailing whitespace: '$value'" }
    }
}
