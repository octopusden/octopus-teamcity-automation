package org.octopusden.octopus.teamcityautomationservice

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.deleteBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildSteps
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildTypes
import org.octopusden.octopus.infrastructure.teamcity.client.getProject
import org.octopusden.octopus.teamcityautomationservice.TeamcityServer.Companion.ADMIN
import org.octopusden.octopus.teamcityautomationservice.TeamcityServer.Companion.CHECKLIST_TEMPLATE
import org.octopusden.octopus.teamcityautomationservice.TeamcityServer.Companion.GRADLE_COMPILE_TEMPLATE
import org.octopusden.octopus.teamcityautomationservice.TeamcityServer.Companion.MAVEN_COMPILE_TEMPLATE
import org.octopusden.octopus.teamcityautomationservice.TeamcityServer.Companion.PARENT_PROJECT
import org.octopusden.octopus.teamcityautomationservice.TeamcityServer.Companion.RC_TEMPLATE
import org.octopusden.octopus.teamcityautomationservice.TeamcityServer.Companion.RELEASE_TEMPLATE
import org.octopusden.octopus.teamcityautomationservice.TeamcityServer.Companion.TEST_USER
import org.octopusden.octopus.teamcityautomationservice.TeamcityServer.Companion.TEST_USER_2
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.BuildChainDTO
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.CreateBuildChainRequest
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.AlreadyExistsException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.NotFoundException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.PartiallyCreatedException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UnsupportedComponentException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.UpstreamException
import org.octopusden.octopus.teamcityautomationservice.client.common.exception.ValidationException
import org.octopusden.octopus.teamcityautomationservice.client.impl.ClassicTeamcityAutomationServiceClient
import org.octopusden.octopus.teamcityautomationservice.client.impl.TeamcityAutomationServiceClientParametersProvider
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * Calls the deployed service through its client against TeamCity 2022/2026 and the Components
 * Registry, the same component shapes the library's own tests cover, and reads back the result in TeamCity.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BuildChainFuncTest {
    private fun request(
        component: String,
        createChecklist: Boolean = true,
        createRcForce: Boolean = false,
        parentProjectId: String = PARENT_PROJECT,
    ) = CreateBuildChainRequest(
        parentProjectId = parentProjectId,
        component = component,
        minorVersion = "1.0",
        createChecklist = createChecklist,
        createRcForce = createRcForce,
    )

    @ParameterizedTest
    @MethodSource("servers")
    fun createsFullChainForEeComponent(server: TeamcityServer) {
        server.resetParentProject()

        val chain = server.service.createBuildChain(request("ee-component"))

        val projectId = "${PARENT_PROJECT}_EeComponent"
        Assertions.assertEquals(
            BuildChainDTO(
                projectId = projectId,
                compileBuildTypeId = "${projectId}_10CompileUtAuto",
                rcBuildTypeId = "${projectId}_20ReleaseCandidateManual",
                checklistBuildTypeId = "${projectId}_30ReleaseChecklistValidationManual",
                releaseBuildTypeId = "${projectId}_40ReleaseManual",
            ),
            chain,
        )
        val tc = server.client
        Assertions.assertEquals(PARENT_PROJECT, tc.getProject(projectId).parentProjectId)
        Assertions.assertEquals(4, tc.getBuildTypes(projectId).buildTypes.size)
        Assertions.assertEquals(MAVEN_COMPILE_TEMPLATE, server.templateOf(chain.compileBuildTypeId))
        Assertions.assertEquals(RC_TEMPLATE, server.templateOf(chain.rcBuildTypeId))
        Assertions.assertEquals(CHECKLIST_TEMPLATE, server.templateOf(chain.checklistBuildTypeId))
        Assertions.assertEquals(RELEASE_TEMPLATE, server.templateOf(chain.releaseBuildTypeId))
        Assertions.assertEquals(chain.compileBuildTypeId, server.dependencyOf(chain.rcBuildTypeId))
        Assertions.assertEquals(chain.rcBuildTypeId, server.dependencyOf(chain.checklistBuildTypeId))
        Assertions.assertEquals(chain.rcBuildTypeId, server.dependencyOf(chain.releaseBuildTypeId))
        Assertions.assertTrue(
            tc
                .getBuildSteps(chain.releaseBuildTypeId)
                .steps
                .single { it.name == "IncrementTeamCityBuildConfigurationParameter" }
                .disabled!!,
        )
        Assertions.assertEquals(
            chain.compileBuildTypeId,
            tc.getParameter(ConfigurationType.BUILD_TYPE, chain.releaseBuildTypeId, "BASE_CONFIGURATION_ID"),
        )
        Assertions.assertEquals("ee-component", tc.getParameter(ConfigurationType.PROJECT, projectId, "COMPONENT_NAME"))
        Assertions.assertEquals("1.0", tc.getParameter(ConfigurationType.PROJECT, projectId, "PROJECT_VERSION"))
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun omitsChecklistWhenNotRequested(server: TeamcityServer) {
        server.resetParentProject()

        val chain = server.service.createBuildChain(request("ee-component", createChecklist = false))

        Assertions.assertNull(chain.checklistBuildTypeId)
        Assertions.assertEquals("${PARENT_PROJECT}_EeComponent_30ReleaseManual", chain.releaseBuildTypeId)
        Assertions.assertEquals(
            3,
            server.client
                .getBuildTypes(chain.projectId)
                .buildTypes.size,
        )
        Assertions.assertEquals(chain.rcBuildTypeId, server.dependencyOf(chain.releaseBuildTypeId))
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun createsCompileAndReleaseOnlyForNonEeComponent(server: TeamcityServer) {
        server.resetParentProject()

        listOf("ie-component", "ei-component", "ii-component").forEach { component ->
            val chain = server.service.createBuildChain(request(component))

            Assertions.assertNull(chain.rcBuildTypeId, component)
            Assertions.assertNull(chain.checklistBuildTypeId, component)
            Assertions.assertEquals(
                2,
                server.client
                    .getBuildTypes(chain.projectId)
                    .buildTypes.size,
                component,
            )
            Assertions.assertEquals(chain.compileBuildTypeId, server.dependencyOf(chain.releaseBuildTypeId), component)
        }
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun createsRcForNonEeComponentWhenForced(server: TeamcityServer) {
        server.resetParentProject()

        val chain = server.service.createBuildChain(request("ie-component", createChecklist = false, createRcForce = true))

        Assertions.assertEquals("${PARENT_PROJECT}_IeComponent_20ReleaseCandidateManual", chain.rcBuildTypeId)
        Assertions.assertNull(chain.checklistBuildTypeId)
        Assertions.assertEquals(chain.rcBuildTypeId, server.dependencyOf(chain.releaseBuildTypeId))
    }

    /** ee-component: owner `testuser`, release managers `testuser,testuser2` — each granted once. */
    @ParameterizedTest
    @MethodSource("servers")
    fun grantsProjectAdminToOwnerAndReleaseManagers(server: TeamcityServer) {
        server.resetParentProject()

        val chain = server.service.createBuildChain(request("ee-component"))

        listOf(TEST_USER, TEST_USER_2).forEach { username ->
            val projectAdminScopes = server.userRoles(username).filter { it.first == "PROJECT_ADMIN" }.map { it.second }
            Assertions.assertEquals(listOf("p:${chain.projectId}"), projectAdminScopes, username)
        }
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun createsChainWhenOwnerIsNotATeamcityUser(server: TeamcityServer) {
        server.resetParentProject()

        val chain = server.service.createBuildChain(request("nonexistent-user-component"))

        Assertions.assertEquals("${PARENT_PROJECT}_NonexistentUserComponent", chain.projectId)
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun overridesJdkVersionOnCompileOnlyWhenItDiffersFromParent(server: TeamcityServer) {
        server.resetParentProject()

        val defaultJdk = server.service.createBuildChain(request("default-jdk-component"))
        val customJdk = server.service.createBuildChain(request("custom-jdk-component"))

        val tc = server.client
        Assertions.assertEquals("1.8", tc.getParameter(ConfigurationType.BUILD_TYPE, defaultJdk.compileBuildTypeId, "JDK_VERSION"))
        Assertions.assertEquals("11", tc.getParameter(ConfigurationType.BUILD_TYPE, customJdk.compileBuildTypeId, "JDK_VERSION"))
        Assertions.assertEquals("1.8", tc.getParameter(ConfigurationType.BUILD_TYPE, customJdk.releaseBuildTypeId, "JDK_VERSION"))
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun selectsCompileTemplateByBuildSystem(server: TeamcityServer) {
        server.resetParentProject()

        mapOf(
            "maven-component" to MAVEN_COMPILE_TEMPLATE,
            "gradle-component" to GRADLE_COMPILE_TEMPLATE,
            "provided-component" to GRADLE_COMPILE_TEMPLATE,
            "in-container-component" to GRADLE_COMPILE_TEMPLATE,
        ).forEach { (component, template) ->
            val chain = server.service.createBuildChain(request(component))

            Assertions.assertEquals(template, server.templateOf(chain.compileBuildTypeId), component)
        }
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun rejectsSecondCallWithoutChangingAnything(server: TeamcityServer) {
        server.resetParentProject()
        val first = server.service.createBuildChain(request("ee-component"))

        val e = Assertions.assertThrows(AlreadyExistsException::class.java) {
            server.service.createBuildChain(request("ee-component"))
        }

        Assertions.assertEquals(first.projectId, e.projectId)
        Assertions.assertEquals(
            4,
            server.client
                .getBuildTypes(first.projectId)
                .buildTypes.size,
        )
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun rejectsUnsupportedBuildSystemBeforeCreatingAnything(server: TeamcityServer) {
        server.resetParentProject()

        val e = Assertions.assertThrows(UnsupportedComponentException::class.java) {
            server.service.createBuildChain(request("not-supported-component"))
        }

        Assertions.assertTrue(e.message!!.startsWith("Unsupported build system: "), e.message)
        server.assertNoProject("${PARENT_PROJECT}_NotSupportedComponent")
    }

    /** two-vcs-root-component has two roots without a Checkout Directory. */
    @ParameterizedTest
    @MethodSource("servers")
    fun rejectsUnplaceableVcsRootsBeforeCreatingAnything(server: TeamcityServer) {
        server.resetParentProject()

        val e = Assertions.assertThrows(UnsupportedComponentException::class.java) {
            server.service.createBuildChain(request("two-vcs-root-component"))
        }

        Assertions.assertTrue(e.message!!.contains("more than one VCS root has no Checkout Directory"), e.message)
        server.assertNoProject("${PARENT_PROJECT}_TwoVcsRootComponent")
    }

    /** The service instance with a wrong password: TeamCity's 401 is the service's problem, not the caller's. */
    @Test
    fun answersUpstreamErrorWhenTeamcityRejectsTheServiceAccount() {
        val server = servers().first()
        server.resetParentProject()

        val e = Assertions.assertThrows(UpstreamException::class.java) {
            wrongAccountClient.createBuildChain(request("ee-component"))
        }

        Assertions.assertEquals("The service's TeamCity account was rejected by TeamCity", e.message)
        Assertions.assertFalse(e.message!!.contains(WRONG_PASSWORD))
        server.assertNoProject("${PARENT_PROJECT}_EeComponent")
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun answersNotFoundForUnknownComponent(server: TeamcityServer) {
        server.resetParentProject()

        Assertions.assertThrows(NotFoundException::class.java) {
            server.service.createBuildChain(request("unknown-component"))
        }
        server.assertNoProject("${PARENT_PROJECT}_UnknownComponent")
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun answersNotFoundForUnknownParent(server: TeamcityServer) {
        server.resetParentProject()

        val e = Assertions.assertThrows(NotFoundException::class.java) {
            server.service.createBuildChain(request("ee-component", parentProjectId = "UnknownParent"))
        }

        Assertions.assertEquals("TeamCity project 'UnknownParent' not found", e.message)
    }

    /** Without the release template, creation fails after the project and the first configurations exist. */
    @ParameterizedTest
    @MethodSource("servers")
    fun namesWhatWasCreatedWhenFailingHalfway(server: TeamcityServer) {
        server.resetParentProject()
        server.client.deleteBuildType(RELEASE_TEMPLATE)

        val e = Assertions.assertThrows(PartiallyCreatedException::class.java) {
            server.service.createBuildChain(request("ee-component"))
        }

        val projectId = "${PARENT_PROJECT}_EeComponent"
        Assertions.assertEquals(projectId, e.projectId)
        Assertions.assertEquals(
            server.client
                .getBuildTypes(projectId)
                .buildTypes
                .map { it.id }
                .sorted(),
            e.buildTypeIds.sorted(),
        )
        Assertions.assertTrue(e.buildTypeIds.contains("${projectId}_10CompileUtAuto"), e.buildTypeIds.toString())
    }

    @Test
    fun rejectsInvalidRequest() {
        val server = servers().first()

        val e = Assertions.assertThrows(ValidationException::class.java) {
            server.service.createBuildChain(request("ee-component").copy(minorVersion = " "))
        }

        Assertions.assertEquals("minorVersion: must not be blank", e.message)
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun isHealthy(server: TeamcityServer) {
        val response = get(server.serviceHost, "/actuator/health")

        Assertions.assertEquals(200, response.statusCode(), response.body())
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun reportsTeamcityUpInReadiness(server: TeamcityServer) {
        val response = get(server.serviceHost, "/actuator/health/readiness/teamcity")

        Assertions.assertEquals(200, response.statusCode(), response.body())
        Assertions.assertEquals("""{"status":"UP"}""", response.body())
    }

    @Test
    fun reportsTeamcityDownWhenTheServiceAccountIsWrong() {
        val teamcity = get(wrongAccountHost, "/actuator/health/readiness/teamcity")
        val readiness = get(wrongAccountHost, "/actuator/health/readiness")
        val liveness = get(wrongAccountHost, "/actuator/health/liveness")

        Assertions.assertEquals(503, teamcity.statusCode(), teamcity.body())
        Assertions.assertEquals("""{"status":"DOWN"}""", teamcity.body())
        Assertions.assertEquals(503, readiness.statusCode(), readiness.body())
        Assertions.assertEquals(200, liveness.statusCode(), liveness.body())
    }

    private fun get(
        host: String,
        path: String,
    ) = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("http://$host$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /**
     * Runs last, after every other call has gone through the service. The service logs the TeamCity and
     * registry clients at DEBUG in ft, where Feign would write the Basic credentials. Every instance is
     * checked for its own account; the password `admin` is too common a word to search for alone, so the
     * Basic header stands for it.
     */
    @Test
    @Order(Int.MAX_VALUE)
    fun neverLogsTheCredentials() {
        servers().forEach { server ->
            val log = serviceLog(server.serviceHost)

            Assertions.assertTrue(
                log.contains("Create build chain for 'ee-component'"),
                "the log of ${server.name} is not the expected one",
            )
            Assertions.assertFalse(log.contains(basic(ADMIN, ADMIN)), "the log of ${server.name} contains a Basic header")
        }
        val log = serviceLog(wrongAccountHost)

        Assertions.assertTrue(log.contains("TeamCity health check failed"), "the log of the wrong-account instance is not the expected one")
        Assertions.assertFalse(log.contains(WRONG_PASSWORD), "the log contains a password")
        Assertions.assertFalse(log.contains(basic(ADMIN, WRONG_PASSWORD)), "the log contains a Basic header")
    }

    private fun serviceLog(host: String): String {
        val response = get(host, "/actuator/logfile")
        Assertions.assertEquals(200, response.statusCode(), "the service log is not readable")
        return response.body()
    }

    private fun basic(
        user: String,
        password: String,
    ) = Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    companion object {
        private val WRONG_PASSWORD = systemProperty("test.wrong-account-password")

        private fun systemProperty(name: String) = checkNotNull(System.getProperty(name)) { "System property '$name' must be defined" }

        private val wrongAccountHost = systemProperty("test.service-wrong-account-host")

        private fun clientFor(host: String) =
            ClassicTeamcityAutomationServiceClient(
                object : TeamcityAutomationServiceClientParametersProvider {
                    override fun getApiUrl() = "http://$host"

                    override fun getConnectTimeoutInMillis() = 10_000

                    override fun getReadTimeoutInMillis() = 120_000
                },
            )

        private val wrongAccountClient = clientFor(wrongAccountHost)

        @JvmStatic
        fun servers() =
            listOf(
                TeamcityServer(
                    "v22",
                    "http://${systemProperty("test.teamcity-2022-host")}",
                    systemProperty("test.service-2022-host"),
                ),
                TeamcityServer(
                    "v26",
                    "http://${systemProperty("test.teamcity-2026-host")}",
                    systemProperty("test.service-2026-host"),
                ),
            )
    }
}
