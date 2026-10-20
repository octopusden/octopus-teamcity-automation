package org.octopusden.octopus.teamcityautomationservice.client.common.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/** The inputs of the CLI's `create-build-chain` command, plus the servers it talks to. */
data class CreateBuildChainRequest(
    @field:Valid
    val teamcity: TeamcityConnection,
    @field:NotBlank
    @field:Pattern(regexp = URL_PATTERN, message = URL_MESSAGE)
    val componentsRegistryUrl: String,
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
