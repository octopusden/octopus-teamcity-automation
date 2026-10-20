package org.octopusden.octopus.teamcityautomationservice.service.impl

import feign.FeignException
import feign.Request
import feign.Response
import feign.RetryableException
import feign.Util
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainCreator
import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainRequest
import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainResult
import org.octopusden.octopus.automation.teamcity.buildchain.UnsupportedBuildSystemException
import org.octopusden.octopus.automation.teamcity.buildchain.UnsupportedVcsTypeException
import org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildTypes
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProjects
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.BuildChainDTO
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.CreateBuildChainRequest
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.TeamcityConnection
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.AlreadyExistsException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.ForbiddenException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.NotFoundException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.PartiallyCreatedException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UnauthorizedException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UnsupportedComponentException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UpstreamException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.ValidationException
import org.octopusden.octopus.teamcityautomationservice.config.BuildChainClientFactory
import java.util.UUID
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException as RegistryNotFoundException

class BuildChainServiceImplTest {
    private val teamcityClient = mock<TeamcityClient>()
    private val componentsRegistryClient = mock<ComponentsRegistryServiceClient>()
    private val creator = mock<BuildChainCreator>()
    private val clientFactory = mock<BuildChainClientFactory> {
        on { createTeamcityClient(any()) } doReturn teamcityClient
        on { createComponentsRegistryClient(any()) } doReturn componentsRegistryClient
        on { createBuildChainCreator(teamcityClient, componentsRegistryClient) } doReturn creator
    }
    private val service = BuildChainServiceImpl(clientFactory)

    @Test
    fun createsChainWithTheRequestInputs() {
        givenParent()
        whenever(creator.create(any())) doReturn RESULT

        val dto = service.createBuildChain(REQUEST)

        assertEquals(
            BuildChainDTO(PROJECT_ID, "${PROJECT_ID}_10", "${PROJECT_ID}_20", null, "${PROJECT_ID}_40"),
            dto,
        )
        verify(creator).create(BuildChainRequest(PARENT, COMPONENT, "1.0", createChecklist = false, createRcForce = true))
        verify(clientFactory).createTeamcityClient(REQUEST.teamcity)
        verify(clientFactory).createComponentsRegistryClient(REGISTRY_URL)
    }

    @Test
    fun refusesExistingProjectBeforeCreatingAnything() {
        givenParent(TeamcityProject(PROJECT_ID, COMPONENT, href = "", webUrl = ""))

        val e = assertThrows<AlreadyExistsException> { service.createBuildChain(REQUEST) }

        assertEquals(PROJECT_ID, e.projectId)
        assertTrue(e.message!!.contains("Delete it in TeamCity"))
        verify(creator, never()).create(any())
    }

    @Test
    fun rejectsUntrimmedValueBeforeCallingTeamcity() {
        val e = assertThrows<ValidationException> { service.createBuildChain(REQUEST.copy(component = "component ")) }

        assertEquals("componentName has leading or trailing whitespace: 'component '", e.message)
        verify(clientFactory, never()).createTeamcityClient(any())
    }

    @Test
    fun mapsMissingParentToNotFound() {
        whenever(teamcityClient.getProject(any<ProjectLocator>())) doThrow feignException(404)

        val e = assertThrows<NotFoundException> { service.createBuildChain(REQUEST) }

        assertEquals("TeamCity project '$PARENT' not found", e.message)
    }

    @Test
    fun mapsRejectedCredentialsToUnauthorizedWithoutThePassword() {
        whenever(teamcityClient.getProject(any<ProjectLocator>())) doThrow feignException(401)

        val e = assertThrows<UnauthorizedException> { service.createBuildChain(REQUEST) }

        assertEquals("TeamCity rejected the credentials of user 'user'", e.message)
        assertTrue(!e.message!!.contains(PASSWORD))
    }

    @Test
    fun mapsMissingPermissionToForbidden() {
        whenever(teamcityClient.getProject(any<ProjectLocator>())) doThrow feignException(403)

        assertThrows<ForbiddenException> { service.createBuildChain(REQUEST) }
    }

    @Test
    fun mapsUnreachableTeamcityToUpstream() {
        whenever(teamcityClient.getProject(any<ProjectLocator>())) doThrow
            RetryableException(-1, "Connection refused", Request.HttpMethod.GET, null as Long?, REQUEST_TO_TEAMCITY)

        val e = assertThrows<UpstreamException> { service.createBuildChain(REQUEST) }

        assertEquals("TeamCity is not reachable", e.message)
    }

    @Test
    fun mapsUnsupportedComponentTo422Exception() {
        givenParent()
        whenever(creator.create(any())) doThrow UnsupportedBuildSystemException(BuildSystem.NOT_SUPPORTED)

        val e = assertThrows<UnsupportedComponentException> { service.createBuildChain(REQUEST) }

        assertEquals("Unsupported build system: NOT_SUPPORTED", e.message)
    }

    @Test
    fun mapsUnsupportedVcsToUnsupportedComponent() {
        givenParent()
        whenever(creator.create(any())) doThrow UnsupportedVcsTypeException("MERCURIAL is not supported")

        assertThrows<UnsupportedComponentException> { service.createBuildChain(REQUEST) }
    }

    @Test
    fun mapsMissingComponentToNotFound() {
        givenParent()
        whenever(creator.create(any())) doThrow RegistryNotFoundException("Component 'component' is not found")

        val e = assertThrows<NotFoundException> { service.createBuildChain(REQUEST) }

        assertEquals("Component 'component' is not found", e.message)
    }

    @Test
    fun namesTheServerThatFailedBeforeCreation() {
        givenParent()
        whenever(creator.create(any())) doThrow feignException(500, "$REGISTRY_URL/rest/api/2/components/component")

        val e = assertThrows<UpstreamException> { service.createBuildChain(REQUEST) }

        assertEquals("Components Registry answered HTTP 500", e.message)
    }

    @Test
    fun reportsWhatWasCreatedWhenFailingAfterCreation() {
        val failure = feignException(404)
        whenever(teamcityClient.getProject(any<ProjectLocator>())).thenReturn(
            parent(),
            parent(TeamcityProject(PROJECT_ID, COMPONENT, href = "", webUrl = "")),
        )
        whenever(creator.create(any())) doThrow failure
        whenever(teamcityClient.getBuildTypes(ProjectLocatorMatcher.withId(PROJECT_ID))) doReturn
            TeamcityBuildTypes(listOf(TeamcityBuildType("${PROJECT_ID}_10")))

        val e = assertThrows<PartiallyCreatedException> { service.createBuildChain(REQUEST) }

        assertEquals(PROJECT_ID, e.projectId)
        assertEquals(listOf("${PROJECT_ID}_10"), e.buildTypeIds)
        assertSame(failure, e.cause)
        assertTrue(e.message!!.contains("TeamCity answered HTTP 404"))
    }

    @Test
    fun keepsTheFailureWhenTheLookupAfterItFails() {
        val failure = IllegalStateException("boom")
        whenever(teamcityClient.getProject(any<ProjectLocator>()))
            .thenReturn(parent())
            .thenThrow(feignException(500))
        whenever(creator.create(any())) doThrow failure

        val e = assertThrows<IllegalStateException> { service.createBuildChain(REQUEST) }

        assertSame(failure, e)
    }

    private fun givenParent(vararg children: TeamcityProject) {
        whenever(teamcityClient.getProject(any<ProjectLocator>())) doReturn parent(*children)
    }

    private fun parent(vararg children: TeamcityProject) =
        TeamcityProject(PARENT, PARENT, href = "", webUrl = "", projects = TeamcityProjects(projects = children.toList()))

    private fun feignException(
        status: Int,
        url: String = "$TEAMCITY_URL/app/rest/projects/id:$PARENT",
    ) = FeignException.errorStatus(
        "TeamcityClient#getProject(ProjectLocator)",
        Response
            .builder()
            .status(status)
            .reason("status $status")
            .headers(emptyMap())
            .request(Request.create(Request.HttpMethod.GET, url, emptyMap(), null, Util.UTF_8, null))
            .build(),
    )

    /** ProjectLocator has no equals, so match the locator by its id. */
    private object ProjectLocatorMatcher {
        fun withId(id: String): ProjectLocator = org.mockito.kotlin.argThat { this.id == id }
    }

    companion object {
        private const val TEAMCITY_URL = "http://teamcity:8111"
        private const val REGISTRY_URL = "http://components-registry:4567"
        private const val PARENT = "Parent"
        private const val COMPONENT = "component"
        private const val PROJECT_ID = "Parent_Component"

        // Generated per run: no credential, not even a dummy one, is written in the sources.
        private val PASSWORD = UUID.randomUUID().toString()

        private val REQUEST = CreateBuildChainRequest(
            teamcity = TeamcityConnection(TEAMCITY_URL, "user", PASSWORD),
            componentsRegistryUrl = REGISTRY_URL,
            parentProjectId = PARENT,
            component = COMPONENT,
            minorVersion = "1.0",
            createChecklist = false,
            createRcForce = true,
        )
        private val RESULT = BuildChainResult(PROJECT_ID, "${PROJECT_ID}_10", "${PROJECT_ID}_20", null, "${PROJECT_ID}_40")
        private val REQUEST_TO_TEAMCITY =
            Request.create(Request.HttpMethod.GET, "$TEAMCITY_URL/app/rest/projects", emptyMap(), null, Util.UTF_8, null)
    }
}
