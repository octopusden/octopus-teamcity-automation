package org.octopusden.octopus.teamcityautomationservice.client.common.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.TeamcityAutomationServiceErrorCode

class TeamcityAutomationServiceExceptionTest {
    @ParameterizedTest
    @MethodSource("exceptions")
    fun carriesItsErrorCode(
        exception: TeamcityAutomationServiceException,
        errorCode: TeamcityAutomationServiceErrorCode,
    ) {
        assertEquals(errorCode, exception.errorCode)
        assertEquals("message", exception.message)
    }

    companion object {
        @JvmStatic
        fun exceptions(): List<Arguments> =
            listOf(
                Arguments.of(ValidationException("message"), TeamcityAutomationServiceErrorCode.VALIDATION),
                Arguments.of(UnauthorizedException("message"), TeamcityAutomationServiceErrorCode.UNAUTHORIZED),
                Arguments.of(ForbiddenException("message"), TeamcityAutomationServiceErrorCode.FORBIDDEN),
                Arguments.of(NotFoundException("message"), TeamcityAutomationServiceErrorCode.NOT_FOUND),
                Arguments.of(AlreadyExistsException("message", "P_C"), TeamcityAutomationServiceErrorCode.ALREADY_EXISTS),
                Arguments.of(UnsupportedComponentException("message"), TeamcityAutomationServiceErrorCode.UNSUPPORTED_COMPONENT),
                Arguments.of(
                    PartiallyCreatedException("message", "P_C", emptyList()),
                    TeamcityAutomationServiceErrorCode.PARTIALLY_CREATED,
                ),
                Arguments.of(UpstreamException("message"), TeamcityAutomationServiceErrorCode.UPSTREAM_ERROR),
            )
    }
}
