package org.octopusden.octopus.automation.teamcity.buildchain

internal enum class DependencyFailureAction(
    val value: String,
) {
    CANCEL("CANCEL"),
}
