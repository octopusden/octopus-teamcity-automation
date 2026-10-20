package org.octopusden.octopus.teamcityautomationservice.client.common.dto

/** Ids of the created project and build configurations; the RC and checklist ids are null when they were not created. */
data class BuildChainDTO(
    val projectId: String,
    val compileBuildTypeId: String,
    val rcBuildTypeId: String?,
    val checklistBuildTypeId: String?,
    val releaseBuildTypeId: String,
)
