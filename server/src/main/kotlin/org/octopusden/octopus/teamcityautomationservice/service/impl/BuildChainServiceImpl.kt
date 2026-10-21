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
import org.octopusden.octopus.teamcityautomationservice.config.AllowedServersProperties
import org.octopusden.octopus.teamcityautomationservice.config.BuildChainClientFactory
import org.octopusden.octopus.teamcityautomationservice.service.BuildChainService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException as RegistryNotFoundException

/**
 * Creates the chain with the library's BuildChainCreator; this class only adds what a service
 * caller needs on top of it: a check of the servers named, a check that nothing exists yet, and an
 * account of what exists after a failure.
 */
@Service
class BuildChainServiceImpl(
    private val clientFactory: BuildChainClientFactory,
    allowedServers: AllowedServersProperties,
) : BuildChainService {
    private val allowedTeamcityUrls = allowedServers.allowedTeamcityUrls.toBaseUrls(TEAMCITY)
    private val allowedComponentsRegistryUrls = allowedServers.allowedComponentsRegistryUrls.toBaseUrls(COMPONENTS_REGISTRY)

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
        val call = Call(
            request,
            requireAllowed(request.teamcity.url, allowedTeamcityUrls, "teamcity.url", TEAMCITY),
            requireAllowed(request.componentsRegistryUrl, allowedComponentsRegistryUrls, "componentsRegistryUrl", COMPONENTS_REGISTRY),
        )
        val teamcityClient = clientFactory.createTeamcityClient(request.teamcity)
        val componentsRegistryClient = clientFactory.createComponentsRegistryClient(request.componentsRegistryUrl)

        // Before any mutation: proves the credentials and the parent, and refuses a re-run.
        val parentProject = getParentProject(teamcityClient, call)
        parentProject.findChild(request.component)?.let { existing ->
            throw AlreadyExistsException(
                "Project '${request.component}' already exists under '${request.parentProjectId}' as '${existing.id}'. " +
                    "Delete it in TeamCity to create the chain again",
                existing.id,
            )
        }

        var createdProject: TeamcityProject? = null
        val result = try {
            clientFactory
                .createBuildChainCreator(teamcityClient, componentsRegistryClient)
                .create(buildChainRequest, parentProject) { createdProject = it }
        } catch (e: BuildChainException) {
            throw UnsupportedComponentException(e.message.orEmpty(), e)
        } catch (e: RegistryNotFoundException) {
            throw NotFoundException(e.message.orEmpty(), e)
        } catch (e: Exception) {
            // Only the project this call created is reported: another request may have created one under the same name.
            throw createdProject?.let { partiallyCreated(teamcityClient, call, it.id, e) }
                ?: createdConcurrently(teamcityClient, call, e)
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

    private fun requireAllowed(
        url: String,
        allowed: List<BaseUrl>,
        field: String,
        server: String,
    ): BaseUrl {
        val base = BaseUrl.parse(url)
        if (base == null || allowed.none { it.contains(url) }) {
            throw ValidationException("$field: '$url' is not an allowed $server server")
        }
        return base
    }

    private fun getParentProject(
        teamcityClient: TeamcityClient,
        call: Call,
    ): TeamcityProject =
        try {
            teamcityClient.getProject(call.request.parentProjectId)
        } catch (e: FeignException.NotFound) {
            throw NotFoundException("TeamCity project '${call.request.parentProjectId}' not found", e)
        } catch (e: FeignException) {
            throw toServiceException(e, call)
        }

    private fun TeamcityProject.findChild(name: String) = projects?.projects?.find { it.name == name }

    private fun partiallyCreated(
        teamcityClient: TeamcityClient,
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
        teamcityClient: TeamcityClient,
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
        val user = call.request.teamcity.user
        return when {
            server == TEAMCITY && exception is FeignException.Unauthorized ->
                UnauthorizedException("TeamCity rejected the credentials of user '$user'", exception)
            server == TEAMCITY && exception is FeignException.Forbidden ->
                ForbiddenException("TeamCity user '$user' has no permission for this operation", exception)
            else ->
                UpstreamException(if (status > 0) "$server answered HTTP $status" else "$server is not reachable", exception)
        }
    }

    private fun BuildChainResult.toDTO() =
        BuildChainDTO(projectId, compileBuildTypeId, rcBuildTypeId, checklistBuildTypeId, releaseBuildTypeId)

    /** The request with the servers it names; the more specific base wins when one URL is under both. */
    private class Call(
        val request: CreateBuildChainRequest,
        private val teamcity: BaseUrl,
        private val componentsRegistry: BaseUrl,
    ) {
        fun serverOf(exception: FeignException): String {
            val url = exception.request()?.url() ?: return TEAMCITY
            val inRegistry = componentsRegistry.contains(url)
            val inTeamcity = teamcity.contains(url)
            return if (inRegistry && (!inTeamcity || componentsRegistry.depth > teamcity.depth)) COMPONENTS_REGISTRY else TEAMCITY
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BuildChainServiceImpl::class.java)
        private const val TEAMCITY = "TeamCity"
        private const val COMPONENTS_REGISTRY = "Components Registry"

        private fun List<String>.toBaseUrls(server: String): List<BaseUrl> {
            val bases = mapNotNull { url ->
                BaseUrl.parse(url).also {
                    if (it == null) {
                        log.warn("Ignoring allowed {} URL '{}'", server, url)
                    }
                }
            }
            if (bases.isEmpty()) {
                log.warn("No allowed {} URLs are configured: every request will be refused", server)
            }
            return bases
        }
    }
}
