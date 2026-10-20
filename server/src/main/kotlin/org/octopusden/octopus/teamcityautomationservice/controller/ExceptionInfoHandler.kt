package org.octopusden.octopus.teamcityautomationservice.controller

import com.fasterxml.jackson.databind.JsonMappingException
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.ErrorResponse
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.TeamcityAutomationServiceErrorCode
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.AlreadyExistsException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.PartiallyCreatedException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.TeamcityAutomationServiceException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Every error body is built from the service's own messages. Messages of foreign exceptions are not
 * returned: they can quote request content, and the request carries a password.
 */
@RestControllerAdvice
class ExceptionInfoHandler {
    @ExceptionHandler(TeamcityAutomationServiceException::class)
    fun handleServiceException(exception: TeamcityAutomationServiceException): ResponseEntity<ErrorResponse> {
        val status = exception.errorCode.status()
        if (status.is5xxServerError) {
            log.error(exception.message, exception)
        } else {
            log.warn(exception.message)
        }
        val body = when (exception) {
            is AlreadyExistsException ->
                ErrorResponse(exception.errorCode, exception.message.orEmpty(), projectId = exception.projectId)
            is PartiallyCreatedException ->
                ErrorResponse(
                    exception.errorCode,
                    exception.message.orEmpty(),
                    projectId = exception.projectId,
                    buildTypeIds = exception.buildTypeIds,
                )
            else -> ErrorResponse(exception.errorCode, exception.message.orEmpty())
        }
        return ResponseEntity.status(status).body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMethodArgumentNotValid(exception: MethodArgumentNotValidException): ErrorResponse {
        // Field and constraint only: the rejected value may be the password.
        val message = exception.bindingResult.fieldErrors
            .joinToString("; ") { fieldError -> "${fieldError.field}: ${fieldError.defaultMessage}" }
        log.warn("Invalid request: {}", message)
        return ErrorResponse(TeamcityAutomationServiceErrorCode.VALIDATION, message.ifBlank { "Validation failed" })
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMessageNotReadable(exception: HttpMessageNotReadableException): ErrorResponse {
        val field = (exception.cause as? JsonMappingException)
            ?.path
            ?.joinToString(".") { it.fieldName ?: "[${it.index}]" }
            ?.takeIf { it.isNotEmpty() }
        val message = field?.let { "$it: missing or of the wrong type" } ?: "Malformed request body"
        log.warn("Invalid request: {}", message)
        return ErrorResponse(TeamcityAutomationServiceErrorCode.VALIDATION, message)
    }

    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleException(exception: Exception): ErrorResponse {
        log.error(exception.message ?: "Unexpected error", exception)
        return ErrorResponse(
            TeamcityAutomationServiceErrorCode.OTHER,
            TeamcityAutomationServiceErrorCode.OTHER.simpleMessage,
        )
    }

    private fun TeamcityAutomationServiceErrorCode.status() =
        when (this) {
            TeamcityAutomationServiceErrorCode.VALIDATION -> HttpStatus.BAD_REQUEST
            TeamcityAutomationServiceErrorCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
            TeamcityAutomationServiceErrorCode.FORBIDDEN -> HttpStatus.FORBIDDEN
            TeamcityAutomationServiceErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND
            TeamcityAutomationServiceErrorCode.ALREADY_EXISTS -> HttpStatus.CONFLICT
            TeamcityAutomationServiceErrorCode.UNSUPPORTED_COMPONENT -> HttpStatus.UNPROCESSABLE_ENTITY
            TeamcityAutomationServiceErrorCode.PARTIALLY_CREATED -> HttpStatus.BAD_GATEWAY
            TeamcityAutomationServiceErrorCode.UPSTREAM_ERROR -> HttpStatus.BAD_GATEWAY
            TeamcityAutomationServiceErrorCode.OTHER -> HttpStatus.INTERNAL_SERVER_ERROR
        }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ExceptionInfoHandler::class.java)
    }
}
