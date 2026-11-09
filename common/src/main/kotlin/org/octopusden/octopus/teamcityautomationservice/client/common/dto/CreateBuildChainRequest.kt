package org.octopusden.octopus.teamcityautomationservice.client.common.dto

import jakarta.validation.constraints.NotBlank

/** The inputs of the CLI's `create-build-chain` command. The servers and the account come from the service's configuration. */
data class CreateBuildChainRequest(
    @field:NotBlank
    val parentProjectId: String,
    @field:NotBlank
    val component: String,
    @field:NotBlank
    val minorVersion: String,
    /** Adds the Release Checklist Validation configuration; applies only when an RC is created. */
    val createChecklist: Boolean = true,
    /** Creates the RC configuration even for a component that is not explicitly and externally distributed. */
    val createRcForce: Boolean = false,
)
