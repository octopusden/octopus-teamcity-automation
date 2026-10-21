package org.octopusden.octopus.teamcityautomationservice.client.common.dto

enum class TeamcityAutomationServiceErrorCode(
    val simpleMessage: String,
) {
    OTHER("Internal server error"),
    VALIDATION("Invalid request"),
    UNAUTHORIZED("TeamCity rejected the credentials"),
    FORBIDDEN("TeamCity denied access"),
    NOT_FOUND("Not found"),
    ALREADY_EXISTS("Already exists"),
    UNSUPPORTED_COMPONENT("Unsupported component"),
    PARTIALLY_CREATED("Build chain partially created"),
    UPSTREAM_ERROR("Upstream request failed"),
}
