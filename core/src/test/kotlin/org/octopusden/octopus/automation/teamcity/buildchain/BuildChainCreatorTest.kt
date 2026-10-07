package org.octopusden.octopus.automation.teamcity.buildchain

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import feign.FeignException
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.createBuildStep
import org.octopusden.octopus.infrastructure.teamcity.client.deleteProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityStep
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildSteps
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildTypes
import org.octopusden.octopus.infrastructure.teamcity.client.getProject
import org.octopusden.octopus.infrastructure.teamcity.client.getSnapshotDependencies
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * Calls [BuildChainCreator] in-process — no CLI — against the same TeamCity 2022/2026 servers and
 * Components Registry the CLI tests use. Runs after them: both reset the same parent project and
 * TeamCity template ids are global.
 */
@Tag("integration")
class BuildChainCreatorTest {
    data class Server(
        val name: String,
        val host: String,
    ) {
        override fun toString() = name
    }

    private fun teamcityClient(server: Server) =
        TeamcityClassicClient(object : ClientParametersProvider {
            override fun getApiUrl() = server.host

            override fun getAuth() = StandardBasicCredCredentialProvider(TEAMCITY_USER, TEAMCITY_PASSWORD)
        })

    private fun registryClient(url: String = "http://$registryHost"): ComponentsRegistryServiceClient =
        ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl() = url
            },
        )

    private fun creator(
        client: TeamcityClassicClient,
        config: BuildChainConfig = BuildChainConfig(),
        registry: ComponentsRegistryServiceClient = registryClient(),
    ) = BuildChainCreator(client, registry, config)

    private fun request(
        componentName: String,
        createChecklist: Boolean = true,
        createRcForce: Boolean = false,
    ) = BuildChainRequest(PARENT_PROJECT, componentName, "1.0", createChecklist, createRcForce)

    private fun TeamcityClassicClient.templateOf(buildTypeId: String?) =
        getBuildType(requireNotNull(buildTypeId))
            .templates
            ?.buildTypes
            ?.single()
            ?.id

    private fun TeamcityClassicClient.dependencyOf(buildTypeId: String?) =
        getSnapshotDependencies(requireNotNull(buildTypeId))
            .snapshotDependencies
            .single()
            .sourceBuildType.id

    @ParameterizedTest
    @MethodSource("servers")
    fun createsFullChainForEeComponent(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)

        val result = creator(client).create(request("ee-component"))

        val projectId = "${PARENT_PROJECT}_EeComponent"
        Assertions.assertEquals(
            BuildChainResult(
                projectId = projectId,
                compileBuildTypeId = "${projectId}_10CompileUtAuto",
                rcBuildTypeId = "${projectId}_20ReleaseCandidateManual",
                checklistBuildTypeId = "${projectId}_30ReleaseChecklistValidationManual",
                releaseBuildTypeId = "${projectId}_40ReleaseManual",
            ),
            result,
        )
        Assertions.assertEquals(PARENT_PROJECT, client.getProject(projectId).parentProjectId)
        Assertions.assertEquals(4, client.getBuildTypes(projectId).buildTypes.size)
        Assertions.assertEquals(BuildChainConfig.DEFAULT_RC_TEMPLATE, client.templateOf(result.rcBuildTypeId))
        Assertions.assertEquals(BuildChainConfig.DEFAULT_CHECKLIST_TEMPLATE, client.templateOf(result.checklistBuildTypeId))
        Assertions.assertEquals(BuildChainConfig.DEFAULT_RELEASE_TEMPLATE, client.templateOf(result.releaseBuildTypeId))
        Assertions.assertEquals(result.compileBuildTypeId, client.dependencyOf(result.rcBuildTypeId))
        Assertions.assertEquals(result.rcBuildTypeId, client.dependencyOf(result.checklistBuildTypeId))
        Assertions.assertEquals(result.rcBuildTypeId, client.dependencyOf(result.releaseBuildTypeId))
        Assertions.assertTrue(
            client
                .getBuildSteps(result.releaseBuildTypeId)
                .steps
                .single { it.name == "IncrementTeamCityBuildConfigurationParameter" }
                .disabled!!,
        )
        Assertions.assertEquals(
            result.compileBuildTypeId,
            client.getParameter(ConfigurationType.BUILD_TYPE, result.releaseBuildTypeId, "BASE_CONFIGURATION_ID"),
        )
        Assertions.assertEquals("ee-component", client.getParameter(ConfigurationType.PROJECT, projectId, "COMPONENT_NAME"))
        Assertions.assertEquals("1.0", client.getParameter(ConfigurationType.PROJECT, projectId, "PROJECT_VERSION"))
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun omitsChecklistWhenNotRequested(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)

        val result = creator(client).create(request("ee-component", createChecklist = false))

        Assertions.assertNull(result.checklistBuildTypeId)
        Assertions.assertEquals("${PARENT_PROJECT}_EeComponent_30ReleaseManual", result.releaseBuildTypeId)
        Assertions.assertEquals(3, client.getBuildTypes(result.projectId).buildTypes.size)
        Assertions.assertEquals(result.rcBuildTypeId, client.dependencyOf(result.releaseBuildTypeId))
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun createsCompileAndReleaseOnlyForNonEeComponent(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)

        listOf("ie-component", "ei-component", "ii-component").forEach { componentName ->
            val result = creator(client).create(request(componentName))

            Assertions.assertNull(result.rcBuildTypeId, componentName)
            Assertions.assertNull(result.checklistBuildTypeId, componentName)
            Assertions.assertEquals(2, client.getBuildTypes(result.projectId).buildTypes.size, componentName)
            Assertions.assertEquals(result.compileBuildTypeId, client.dependencyOf(result.releaseBuildTypeId), componentName)
        }
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun createsRcForNonEeComponentWhenForced(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)

        val result = creator(client).create(request("ie-component", createChecklist = false, createRcForce = true))

        Assertions.assertEquals("${PARENT_PROJECT}_IeComponent_20ReleaseCandidateManual", result.rcBuildTypeId)
        Assertions.assertNull(result.checklistBuildTypeId)
        Assertions.assertEquals(result.rcBuildTypeId, client.dependencyOf(result.releaseBuildTypeId))
    }

    /** ee-component: owner `testuser`, release managers `testuser,testuser2` — each granted once. */
    @ParameterizedTest
    @MethodSource("servers")
    fun grantsProjectAdminToOwnerAndReleaseManagers(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)

        val result = creator(client).create(request("ee-component"))

        listOf(TEST_USER, TEST_USER_2).forEach { username ->
            val projectAdminScopes = userRoles(server, username).filter { it.first == "PROJECT_ADMIN" }.map { it.second }
            Assertions.assertEquals(listOf("p:${result.projectId}"), projectAdminScopes, username)
        }
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun createsChainWhenOwnerIsNotATeamcityUser(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)

        val result = creator(client).create(request("nonexistent-user-component"))

        Assertions.assertEquals("${PARENT_PROJECT}_NonexistentUserComponent", result.projectId)
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun overridesJdkVersionOnCompileOnlyWhenItDiffersFromParent(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)

        val defaultJdk = creator(client).create(request("default-jdk-component"))
        val customJdk = creator(client).create(request("custom-jdk-component"))

        Assertions.assertEquals("1.8", client.getParameter(ConfigurationType.BUILD_TYPE, defaultJdk.compileBuildTypeId, "JDK_VERSION"))
        Assertions.assertEquals("11", client.getParameter(ConfigurationType.BUILD_TYPE, customJdk.compileBuildTypeId, "JDK_VERSION"))
        Assertions.assertEquals("1.8", client.getParameter(ConfigurationType.BUILD_TYPE, customJdk.releaseBuildTypeId, "JDK_VERSION"))
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun readsAndWritesConfiguredJdkVersionParameter(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)
        client.setParameter(ConfigurationType.PROJECT, PARENT_PROJECT, "CUSTOM_JDK", "1.8")

        val result = creator(client, BuildChainConfig(jdkVersionParameter = "CUSTOM_JDK")).create(request("custom-jdk-component"))

        Assertions.assertEquals("11", client.getParameter(ConfigurationType.BUILD_TYPE, result.compileBuildTypeId, "CUSTOM_JDK"))
        Assertions.assertEquals("1.8", client.getParameter(ConfigurationType.BUILD_TYPE, result.compileBuildTypeId, "JDK_VERSION"))
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun selectsCompileTemplateByBuildSystem(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)

        mapOf(
            "maven-component" to BuildChainConfig.DEFAULT_MAVEN_COMPILE_TEMPLATE,
            "gradle-component" to BuildChainConfig.DEFAULT_GRADLE_COMPILE_TEMPLATE,
            "provided-component" to BuildChainConfig.DEFAULT_GRADLE_COMPILE_TEMPLATE,
            "in-container-component" to BuildChainConfig.DEFAULT_GRADLE_COMPILE_TEMPLATE,
        ).forEach { (componentName, template) ->
            val result = creator(client).create(request(componentName))
            Assertions.assertEquals(template, client.templateOf(result.compileBuildTypeId), componentName)
        }
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun createsChainFromConfiguredTemplates(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)
        val config = BuildChainConfig(
            gradleCompileTemplate = "CustomGradleBuild",
            mavenCompileTemplate = "CustomMavenBuild",
            rcTemplate = "CustomReleaseCandidate",
            checklistTemplate = "CustomChecklist",
            releaseTemplate = "CustomRelease",
        )
        createTemplates(client, config)

        val maven = creator(client, config).create(request("ee-component"))
        val gradle = creator(client, config).create(request("gradle-component"))

        Assertions.assertEquals("CustomMavenBuild", client.templateOf(maven.compileBuildTypeId))
        Assertions.assertEquals("CustomReleaseCandidate", client.templateOf(maven.rcBuildTypeId))
        Assertions.assertEquals("CustomChecklist", client.templateOf(maven.checklistBuildTypeId))
        Assertions.assertEquals("CustomRelease", client.templateOf(maven.releaseBuildTypeId))
        Assertions.assertEquals("CustomGradleBuild", client.templateOf(gradle.compileBuildTypeId))
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun rejectsUnsupportedBuildSystemBeforeCreatingAnything(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)

        val e = Assertions.assertThrows(UnsupportedBuildSystemException::class.java) {
            creator(client).create(request("not-supported-component"))
        }

        Assertions.assertEquals("Unsupported build system: ${e.buildSystem.name}", e.message)
        assertNoProject(client, "${PARENT_PROJECT}_NotSupportedComponent")
    }

    @ParameterizedTest
    @MethodSource("servers")
    fun rejectsNonGitRootBeforeCreatingAnything(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)
        val stub = StubComponentsRegistry("non-git-component", "MERCURIAL")
        try {
            val e = Assertions.assertThrows(UnsupportedVcsTypeException::class.java) {
                creator(client, registry = registryClient(stub.url)).create(request("non-git-component"))
            }

            Assertions.assertTrue(e.message!!.contains("unsupported type MERCURIAL"), e.message)
            assertNoProject(client, "${PARENT_PROJECT}_NonGitComponent")
        } finally {
            stub.stop()
        }
    }

    /** two-vcs-root-component has two roots without a Checkout Directory. */
    @ParameterizedTest
    @MethodSource("servers")
    fun rejectsUnplaceableVcsRootsBeforeCreatingAnything(server: Server) {
        val client = teamcityClient(server)
        resetParentProject(client, server)

        val e = Assertions.assertThrows(UnsupportedVcsRootLayoutException::class.java) {
            creator(client).create(request("two-vcs-root-component"))
        }

        Assertions.assertTrue(e.message!!.contains("more than one VCS root has no Checkout Directory"), e.message)
        assertNoProject(client, "${PARENT_PROJECT}_TwoVcsRootComponent")
    }

    private fun assertNoProject(
        client: TeamcityClassicClient,
        projectId: String,
    ) {
        Assertions.assertThrows(FeignException.NotFound::class.java) { client.getProject(projectId) }
    }

    /** The parent project with the default templates, the release steps the chain expects and `JDK_VERSION` 1.8. */
    private fun resetParentProject(
        client: TeamcityClassicClient,
        server: Server,
    ) {
        // Absent on the first run against a server.
        runCatching { client.deleteProject(PARENT_PROJECT) }
        createUser(server, TEST_USER)
        createUser(server, TEST_USER_2)
        client.createProject(TeamcityCreateProject(PARENT_PROJECT, PARENT_PROJECT, TeamcityLinkProject("RDDepartment")))
        client.setParameter(ConfigurationType.PROJECT, PARENT_PROJECT, "JDK_VERSION", "1.8")
        createTemplates(client, BuildChainConfig())
    }

    private fun createTemplates(
        client: TeamcityClassicClient,
        config: BuildChainConfig,
    ) {
        listOf(
            config.gradleCompileTemplate,
            config.mavenCompileTemplate,
            config.rcTemplate,
            config.checklistTemplate,
            config.releaseTemplate,
        ).forEach {
            client.createBuildType(TeamcityCreateBuildType(it, it, project = TeamcityLinkProject(PARENT_PROJECT), templateFlag = true))
        }
        listOf("IncrementTeamCityBuildConfigurationParameter", "Deploy to Share").forEach { stepName ->
            client.createBuildStep(
                config.releaseTemplate,
                step = TeamcityStep(
                    stepName,
                    stepName,
                    stepName,
                    disabled = false,
                    properties = TeamcityProperties(listOf(TeamcityProperty("property", ""))),
                ),
            )
        }
    }

    private fun adminRequest(uri: String) =
        HttpRequest
            .newBuilder()
            .uri(URI(uri))
            .header("Accept", "application/json")
            .header("Authorization", "Basic ${Base64.getEncoder().encodeToString("$TEAMCITY_USER:$TEAMCITY_PASSWORD".toByteArray())}")

    private fun createUser(
        server: Server,
        username: String,
    ) {
        val response = HttpClient.newHttpClient().send(
            adminRequest("${server.host}/app/rest/users")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"username":"$username","password":"$username"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        // 400: the user already exists
        Assertions.assertTrue(response.statusCode() in 200..299 || response.statusCode() == 400, response.body())
    }

    /** (roleId, scope) pairs. */
    private fun userRoles(
        server: Server,
        username: String,
    ): List<Pair<String, String>> {
        val response = HttpClient.newHttpClient().send(
            adminRequest("${server.host}/app/rest/users/username:$username/roles").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        Assertions.assertEquals(200, response.statusCode(), response.body())
        return ObjectMapper().readTree(response.body())["role"]?.map { it["roleId"].asText() to it["scope"].asText() } ?: emptyList()
    }

    /**
     * A v2 `getDetailedComponent` endpoint serving one component with a single root of [vcsType]: a
     * shape the registry container's Groovy DSL cannot produce.
     */
    private class StubComponentsRegistry(
        componentKey: String,
        vcsType: String,
    ) {
        private val server = HttpServer.create(InetSocketAddress(0), 0).also { it.start() }
        val url = "http://localhost:${server.address.port}"

        init {
            val body = detailedComponentJson(componentKey, vcsType).toByteArray()
            server.createContext("/rest/api/2/components/$componentKey/versions/1.0") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }

        fun stop() = server.stop(0)

        private fun detailedComponentJson(
            componentKey: String,
            vcsType: String,
        ) = // language=JSON
            """
            {
              "id": "$componentKey",
              "name": "$componentKey",
              "componentOwner": "$TEST_USER",
              "buildSystem": "MAVEN",
              "vcsSettings": {
                "versionControlSystemRoots": [
                  {"name": "root-1", "vcsPath": "svn://example.test/proj/repo", "type": "$vcsType", "tag": "stub-tag", "branch": "master"}
                ],
                "externalRegistry": null
              },
              "jiraComponentVersion": {
                "name": "$componentKey",
                "version": "1.0",
                "component": {
                  "projectKey": "BUILDSYS",
                  "displayName": null,
                  "componentVersionFormat": {
                    "majorVersionFormat": "${'$'}major.${'$'}minor",
                    "releaseVersionFormat": "${'$'}major.${'$'}minor.${'$'}service",
                    "buildVersionFormat": "${'$'}major.${'$'}minor.${'$'}service",
                    "lineVersionFormat": "${'$'}major.${'$'}minor",
                    "hotfixVersionFormat": ""
                  },
                  "componentInfo": {"versionPrefix": "stub", "versionFormat": "${'$'}versionPrefix-${'$'}baseVersionFormat"},
                  "technical": false
                }
              },
              "detailedComponentVersion": {
                "component": "$componentKey",
                "minorVersion": {"type": "MINOR", "version": "1.0", "jiraVersion": "stub-1.0"},
                "lineVersion": {"type": "LINE", "version": "1.0", "jiraVersion": "stub-1.0"},
                "buildVersion": {"type": "BUILD", "version": "1.0.0", "jiraVersion": "stub-1.0.0"},
                "rcVersion": {"type": "RC", "version": "1.0.0_RC", "jiraVersion": "stub-1.0.0_RC"},
                "releaseVersion": {"type": "RELEASE", "version": "1.0.0", "jiraVersion": "stub-1.0.0"}
              },
              "deprecated": false,
              "buildFilePath": null,
              "system": ["NONE"],
              "clientCode": null,
              "releasesInDefaultBranch": null,
              "solution": null,
              "parentComponent": null,
              "securityChampion": null,
              "releaseManager": null,
              "distribution": null,
              "archived": false,
              "doc": null,
              "escrow": null,
              "copyright": null,
              "labels": [],
              "buildParameters": {
                "javaVersion": "1.8",
                "mavenVersion": "3.6.3",
                "gradleVersion": "LATEST",
                "requiredProject": false,
                "projectVersion": null,
                "systemProperties": null,
                "buildTasks": null,
                "tools": [],
                "buildTools": []
              }
            }
            """.trimIndent()
    }

    companion object {
        // The CLI tests' parent project, so both suites see the registry fixtures under the same ids.
        const val PARENT_PROJECT = "TestTeamcityAutomation"
        const val TEAMCITY_USER = "admin"
        const val TEAMCITY_PASSWORD = "admin"
        const val TEST_USER = "testuser"
        const val TEST_USER_2 = "testuser2"

        private val registryHost = systemProperty("test.components-registry-host")

        private fun systemProperty(name: String) = checkNotNull(System.getProperty(name)) { "System property '$name' must be defined" }

        @JvmStatic
        fun servers() =
            listOf(
                Server("v22", "http://${systemProperty("test.teamcity-2022-host")}"),
                Server("v26", "http://${systemProperty("test.teamcity-2026-host")}"),
            )
    }
}
