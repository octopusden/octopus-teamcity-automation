package org.octopusden.octopus.teamcityautomationservice.service.impl

import feign.FeignException
import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainCreator
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
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UnsupportedComponentException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UpstreamException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.ValidationException
import org.octopusden.octopus.teamcityautomationservice.config.ComponentsRegistryProperties
import org.octopusden.octopus.teamcityautomationservice.config.TeamcityProperties
import org.octopusden.octopus.teamcityautomationservice.service.BuildChainService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException as RegistryNotFoundException

/**
 * Creates the chain with the library's BuildChainCreator; this class only adds what a service
 * caller needs on top of it: a check that nothing exists yet, and an account of what exists after a
 * failure. The servers and the TeamCity account are the configured ones, not the caller's.
 */
@Service
class BuildChainServiceImpl(
    private val teamcityClient: TeamcityClient,
    private val buildChainCreator: BuildChainCreator,
    teamcityProperties: TeamcityProperties,
    componentsRegistryProperties: ComponentsRegistryProperties,
) : BuildChainService {
    private val servers = Servers(
        baseUrlOf(teamcityProperties.url, "teamcity.url"),
        baseUrlOf(componentsRegistryProperties.url, "components-registry.url"),
    )

    // Whatever fails after the pre-flight, the caller must learn what is left in TeamCity.
    @Suppress("TooGenericExceptionCaught")
    override fun createBuildChain(request: CreateBuildChainRequest): BuildChainDTO {
        log.info(
            "Create build chain for '{}' {} under '{}'",
            request.component,
            request.minorVersion,
            request.parentProjectId,
        )
        val buildChainRequest = request.toBuildChainRequest()
        val call = Call(request, servers)

        // Before any mutation: proves the credentials and the parent, and refuses a re-run.
        val parentProject = getParentProject(call)
        parentProject.findChild(request.component)?.let { existing ->
            throw AlreadyExistsException(
                "Project '${request.component}' already exists under '${request.parentProjectId}' as '${existing.id}'. " +
                    "Delete it in TeamCity to create the chain again",
                existing.id,
            )
        }

        var createdProject: TeamcityProject? = null
        val result = try {
            buildChainCreator.create(buildChainRequest, parentProject) { createdProject = it }
        } catch (e: BuildChainException) {
            throw UnsupportedComponentException(e.message.orEmpty(), e)
        } catch (e: RegistryNotFoundException) {
            throw NotFoundException(e.message.orEmpty(), e)
        } catch (e: Exception) {
            // Only the project this call created is reported: another request may have created one under the same name.
            throw createdProject?.let { partiallyCreated(call, it.id, e) }
                ?: createdConcurrently(call, e)
                ?: toServiceException(e, call)
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

    private fun getParentProject(call: Call): TeamcityProject =
        try {
            teamcityClient.getProject(call.request.parentProjectId)
        } catch (e: FeignException.NotFound) {
            throw NotFoundException("TeamCity project '${call.request.parentProjectId}' not found", e)
        } catch (e: FeignException) {
            throw toServiceException(e, call)
        }

    private fun TeamcityProject.findChild(name: String) = projects?.projects?.find { it.name == name }

    private fun partiallyCreated(
        call: Call,
        projectId: String,
        cause: Exception,
    ): PartiallyCreatedException {
        val component = call.request.component
        val buildTypeIds = runCatching { teamcityClient.getBuildTypes(projectId).buildTypes.map { it.id } }
            .onFailure { log.error("Cannot list the build configurations created for '{}' in {}", component, projectId, it) }
            .getOrNull()
        log.error("Build chain for '{}' failed after creating {} with {}", component, projectId, buildTypeIds, cause)
        val created = buildTypeIds?.let { "with build configurations $it" } ?: "whose build configurations could not be listed"
        return PartiallyCreatedException(
            "Creating the build chain for '$component' failed after creating project '$projectId' $created: " +
                "${toServiceException(cause, call).message}. Delete the project in TeamCity before trying again",
            projectId,
            buildTypeIds.orEmpty(),
            cause,
        )
    }

    /** A TeamCity failure before this call created anything, while another request created the same project. */
    private fun createdConcurrently(
        call: Call,
        cause: Exception,
    ): AlreadyExistsException? {
        if (cause !is FeignException || call.serverOf(cause) != TEAMCITY) {
            return null
        }
        return runCatching { teamcityClient.getProject(call.request.parentProjectId).findChild(call.request.component) }
            .onFailure { log.error("Cannot tell whether '{}' was created by another request", call.request.component, it) }
            .getOrNull()
            ?.let { existing ->
                AlreadyExistsException(
                    "Project '${call.request.component}' was created under '${call.request.parentProjectId}' as " +
                        "'${existing.id}' by another request while this one ran",
                    existing.id,
                )
            }
    }

    private fun toServiceException(
        exception: Exception,
        call: Call,
    ): Exception {
        if (exception !is FeignException) {
            return exception
        }
        val server = call.serverOf(exception)
        val status = exception.status()
        return when {
            // The account is the service's own, so the caller cannot fix a 401: it is an upstream failure.
            server == TEAMCITY && exception is FeignException.Unauthorized ->
                UpstreamException("The service's TeamCity account was rejected by TeamCity", exception)
            server == TEAMCITY && exception is FeignException.Forbidden ->
                ForbiddenException(
                    "The service's TeamCity account has no permission for this operation under project " +
                        "'${call.request.parentProjectId}'",
                    exception,
                )
            else ->
                UpstreamException(if (status > 0) "$server answered HTTP $status" else "$server is not reachable", exception)
        }
    }

    private fun BuildChainResult.toDTO() =
        BuildChainDTO(projectId, compileBuildTypeId, rcBuildTypeId, checklistBuildTypeId, releaseBuildTypeId)

    /** The servers' base URLs; the more specific one wins when a URL is under both. */
    private class Servers(
        val teamcity: BaseUrl,
        val componentsRegistry: BaseUrl,
    )

    private class Call(
        val request: CreateBuildChainRequest,
        private val servers: Servers,
    ) {
        fun serverOf(exception: FeignException): String {
            val url = exception.request()?.url() ?: return TEAMCITY
            val inRegistry = servers.componentsRegistry.contains(url)
            val inTeamcity = servers.teamcity.contains(url)
            return if (inRegistry && (!inTeamcity || servers.componentsRegistry.depth > servers.teamcity.depth)) {
                COMPONENTS_REGISTRY
            } else {
                TEAMCITY
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BuildChainServiceImpl::class.java)
        private const val TEAMCITY = "TeamCity"
        private const val COMPONENTS_REGISTRY = "Components Registry"

        private fun baseUrlOf(
            url: String,
            property: String,
        ) = checkNotNull(BaseUrl.parse(url)) { "$property is not a valid URL" }
    }
}
