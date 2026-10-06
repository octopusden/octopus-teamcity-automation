package org.octopusden.octopus.automation.teamcity.buildchain

/** Ids of the created project and build configurations; the RC and checklist ids are null when they were not created. */
data class BuildChainResult(
    val projectId: String,
    val compileBuildTypeId: String,
    val rcBuildTypeId: String?,
    val checklistBuildTypeId: String?,
    val releaseBuildTypeId: String,
)
