package org.octopusden.octopus.teamcityautomationservice.service.impl

import feign.FeignException
import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainException
import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainRequest
import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainResult
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildTypes
import org.octopusden.octopus.infrastructure.teamcity.client.getProject
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.BuildChainDTO
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.CreateBuildChainRequest
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.AlreadyExistsException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.ForbiddenException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.NotFoundException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.PartiallyCreatedException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UnauthorizedException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UnsupportedComponentException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UpstreamException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.ValidationException
import org.octopusden.octopus.teamcityautomationservice.config.BuildChainClientFactory
import org.octopusden.octopus.teamcityautomationservice.service.BuildChainService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException as RegistryNotFoundException

/**
 * Creates the chain with the library's BuildChainCreator; this class only adds what a service
 * caller needs on top of it: a check that nothing exists yet, and an account of what exists after a failure.
 */
@Service
class BuildChainServiceImpl(
    private val clientFactory: BuildChainClientFactory,
) : BuildChainService {
    // Whatever fails after the pre-flight, the caller must learn what is left in TeamCity.
    @Suppress("TooGenericExceptionCaught")
    override fun createBuildChain(request: CreateBuildChainRequest): BuildChainDTO {
        log.info(
            "Create build chain for '{}' {} under '{}' on {} as '{}'",
            request.component,
            request.minorVersion,
            request.parentProjectId,
            request.teamcity.url,
            request.teamcity.user,
        )
        val buildChainRequest = request.toBuildChainRequest()
        val teamcityClient = clientFactory.createTeamcityClient(request.teamcity)
        val componentsRegistryClient = clientFactory.createComponentsRegistryClient(request.componentsRegistryUrl)

        // Before any mutation: proves the credentials and the parent, and refuses a re-run.
        val parentProject = getParentProject(teamcityClient, request)
        parentProject.findChild(request.component)?.let { existing ->
            throw AlreadyExistsException(
                "Project '${request.component}' already exists under '${request.parentProjectId}' as '${existing.id}'. " +
                    "Delete it in TeamCity to create the chain again",
                existing.id,
            )
        }

        val result = try {
            clientFactory.createBuildChainCreator(teamcityClient, componentsRegistryClient).create(buildChainRequest)
        } catch (e: BuildChainException) {
            throw UnsupportedComponentException(e.message.orEmpty(), e)
        } catch (e: RegistryNotFoundException) {
            throw NotFoundException(e.message.orEmpty(), e)
        } catch (e: Exception) {
            // The project did not exist before, so whatever exists under the component's name now was created by this call.
            throw findCreated(teamcityClient, request, e) ?: toServiceException(e, request)
        }
        log.info("Created build chain for '{}': {}", request.component, result)
        return result.toDTO()
    }

    private fun CreateBuildChainRequest.toBuildChainRequest() =
        try {
            BuildChainRequest(parentProjectId, component, minorVersion, createChecklist, createRcForce)
        } catch (e: IllegalArgumentException) {
            throw ValidationException(e.message.orEmpty(), e)
        }

    private fun getParentProject(
        teamcityClient: TeamcityClient,
        request: CreateBuildChainRequest,
    ): TeamcityProject =
        try {
            teamcityClient.getProject(request.parentProjectId)
        } catch (e: FeignException.NotFound) {
            throw NotFoundException("TeamCity project '${request.parentProjectId}' not found", e)
        } catch (e: FeignException) {
            throw toServiceException(e, request)
        }

    private fun TeamcityProject.findChild(name: String) = projects?.projects?.find { it.name == name }

    private fun findCreated(
        teamcityClient: TeamcityClient,
        request: CreateBuildChainRequest,
        cause: Exception,
    ): PartiallyCreatedException? =
        runCatching {
            teamcityClient.getProject(request.parentProjectId).findChild(request.component)?.let { project ->
                val buildTypeIds = teamcityClient.getBuildTypes(project.id).buildTypes.map { it.id }
                log.error("Build chain for '{}' failed after creating {} with {}", request.component, project.id, buildTypeIds, cause)
                PartiallyCreatedException(
                    "Creating the build chain for '${request.component}' failed after creating project '${project.id}' " +
                        "with build configurations $buildTypeIds: ${toServiceException(cause, request).message}. " +
                        "Delete the project in TeamCity before trying again",
                    project.id,
                    buildTypeIds,
                    cause,
                )
            }
        }.onFailure {
            log.error("Cannot tell what was created for '{}' after a failure", request.component, it)
        }.getOrNull()

    private fun toServiceException(
        exception: Exception,
        request: CreateBuildChainRequest,
    ): Exception {
        if (exception !is FeignException) {
            return exception
        }
        val server = if (exception.request()?.url()?.startsWith(request.componentsRegistryUrl) == true) {
            "Components Registry"
        } else {
            "TeamCity"
        }
        val status = exception.status()
        return when (exception) {
            is FeignException.Unauthorized ->
                UnauthorizedException("TeamCity rejected the credentials of user '${request.teamcity.user}'", exception)
            is FeignException.Forbidden ->
                ForbiddenException("TeamCity user '${request.teamcity.user}' has no permission for this operation", exception)
            else ->
                UpstreamException(if (status > 0) "$server answered HTTP $status" else "$server is not reachable", exception)
        }
    }

    private fun BuildChainResult.toDTO() =
        BuildChainDTO(projectId, compileBuildTypeId, rcBuildTypeId, checklistBuildTypeId, releaseBuildTypeId)

    companion object {
        private val log = LoggerFactory.getLogger(BuildChainServiceImpl::class.java)
    }
}
