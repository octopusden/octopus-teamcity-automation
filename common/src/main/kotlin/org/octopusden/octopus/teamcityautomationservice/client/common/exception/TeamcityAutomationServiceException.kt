package org.octopusden.octopus.teamcityautomationservice.client.common.exception

import org.octopusden.octopus.teamcityautomationservice.client.common.dto.TeamcityAutomationServiceErrorCode

abstract class TeamcityAutomationServiceException(
    message: String,
    val errorCode: TeamcityAutomationServiceErrorCode,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ValidationException(
    message: String,
    cause: Throwable? = null,
) : TeamcityAutomationServiceException(message, TeamcityAutomationServiceErrorCode.VALIDATION, cause)

class UnauthorizedException(
    message: String,
    cause: Throwable? = null,
) : TeamcityAutomationServiceException(message, TeamcityAutomationServiceErrorCode.UNAUTHORIZED, cause)

class ForbiddenException(
    message: String,
    cause: Throwable? = null,
) : TeamcityAutomationServiceException(message, TeamcityAutomationServiceErrorCode.FORBIDDEN, cause)

class NotFoundException(
    message: String,
    cause: Throwable? = null,
) : TeamcityAutomationServiceException(message, TeamcityAutomationServiceErrorCode.NOT_FOUND, cause)

/** The component's project already exists under the parent; nothing was changed. */
class AlreadyExistsException(
    message: String,
    val projectId: String,
) : TeamcityAutomationServiceException(message, TeamcityAutomationServiceErrorCode.ALREADY_EXISTS)

/** The component's build system or VCS roots cannot be turned into a chain; nothing was created. */
class UnsupportedComponentException(
    message: String,
    cause: Throwable? = null,
) : TeamcityAutomationServiceException(message, TeamcityAutomationServiceErrorCode.UNSUPPORTED_COMPONENT, cause)

/** Creation failed after the project was created; [projectId] and [buildTypeIds] are what exists now. */
class PartiallyCreatedException(
    message: String,
    val projectId: String,
    val buildTypeIds: List<String>,
    cause: Throwable? = null,
) : TeamcityAutomationServiceException(message, TeamcityAutomationServiceErrorCode.PARTIALLY_CREATED, cause)

/** TeamCity or the Components Registry answered with an error the service does not map otherwise. */
class UpstreamException(
    message: String,
    cause: Throwable? = null,
) : TeamcityAutomationServiceException(message, TeamcityAutomationServiceErrorCode.UPSTREAM_ERROR, cause)
