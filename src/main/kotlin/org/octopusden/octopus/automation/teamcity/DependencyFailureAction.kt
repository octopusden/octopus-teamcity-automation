package org.octopusden.octopus.automation.teamcity

enum class DependencyFailureAction(val value: String) {
    MAKE_FAILED_TO_START("MAKE_FAILED_TO_START"),
    CANCEL("CANCEL")
}
