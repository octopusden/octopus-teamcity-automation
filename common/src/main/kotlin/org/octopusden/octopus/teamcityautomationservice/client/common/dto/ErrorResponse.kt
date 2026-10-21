package org.octopusden.octopus.teamcityautomationservice.client.common.dto

/**
 * [projectId] names the existing project for [TeamcityAutomationServiceErrorCode.ALREADY_EXISTS], and the
 * created one for [TeamcityAutomationServiceErrorCode.PARTIALLY_CREATED] together with [buildTypeIds].
 */
data class ErrorResponse(
    val errorCode: TeamcityAutomationServiceErrorCode,
    val errorMessage: String,
    val projectId: String? = null,
    val buildTypeIds: List<String>? = null,
)
