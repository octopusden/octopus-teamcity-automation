package org.octopusden.octopus.automation.teamcity.parameter

import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType

/** One project or build configuration whose parameter is updated. */
data class ParameterTarget(
    val type: ConfigurationType,
    val id: String,
)
