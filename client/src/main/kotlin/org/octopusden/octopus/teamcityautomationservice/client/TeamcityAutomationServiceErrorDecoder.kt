package org.octopusden.octopus.teamcityautomationservice.client

import com.fasterxml.jackson.databind.ObjectMapper
import feign.Response
import feign.codec.ErrorDecoder
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.ErrorResponse
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.TeamcityAutomationServiceErrorCode
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.AlreadyExistsException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.ForbiddenException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.NotFoundException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.PartiallyCreatedException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UnauthorizedException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UnsupportedComponentException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UpstreamException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.ValidationException
import java.io.IOException

/** Turns the service's [ErrorResponse] back into the exception the server mapped it from. */
class TeamcityAutomationServiceErrorDecoder(
    private val objectMapper: ObjectMapper,
) : ErrorDecoder.Default() {
    override fun decode(
        methodKey: String?,
        response: Response?,
    ): Exception = getErrorResponse(response)?.toException() ?: super.decode(methodKey, response)

    // A body that is not an ErrorResponse is left to Feign's default decoding, which reports the status.
    @Suppress("SwallowedException")
    private fun getErrorResponse(response: Response?): ErrorResponse? =
        response?.let { res ->
            res
                .headers()["content-type"]
                ?.find { it.contains("application/json") }
                ?.let {
                    try {
                        res
                            .body()
                            ?.asInputStream()
                            .use { inputStream -> objectMapper.readValue(inputStream, ErrorResponse::class.java) }
                    } catch (e: IOException) {
                        null
                    }
                }
        }

    private fun ErrorResponse.toException(): Exception =
        when (errorCode) {
            TeamcityAutomationServiceErrorCode.VALIDATION -> ValidationException(errorMessage)
            TeamcityAutomationServiceErrorCode.UNAUTHORIZED -> UnauthorizedException(errorMessage)
            TeamcityAutomationServiceErrorCode.FORBIDDEN -> ForbiddenException(errorMessage)
            TeamcityAutomationServiceErrorCode.NOT_FOUND -> NotFoundException(errorMessage)
            TeamcityAutomationServiceErrorCode.ALREADY_EXISTS -> AlreadyExistsException(errorMessage, projectId.orEmpty())
            TeamcityAutomationServiceErrorCode.UNSUPPORTED_COMPONENT -> UnsupportedComponentException(errorMessage)
            TeamcityAutomationServiceErrorCode.PARTIALLY_CREATED ->
                PartiallyCreatedException(errorMessage, projectId.orEmpty(), buildTypeIds.orEmpty())
            TeamcityAutomationServiceErrorCode.UPSTREAM_ERROR -> UpstreamException(errorMessage)
            TeamcityAutomationServiceErrorCode.OTHER -> IllegalStateException(errorMessage)
        }
}
