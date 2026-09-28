import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import it.skrape.core.htmlDocument
import it.skrape.matchers.toBe
import it.skrape.selects.html5.tr
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.automation.teamcity.DependencyFailureAction
import org.octopusden.octopus.automation.teamcity.TeamcityCommand
import org.octopusden.octopus.automation.teamcity.TeamcityCreateBuildChainCommand
import org.octopusden.octopus.automation.teamcity.TeamcityGetBuildTypesAgentRequirementsCommand
import org.octopusden.octopus.automation.teamcity.TeamcityPostGithubStatusCommand
import org.octopusden.octopus.automation.teamcity.TeamcityReplaceVcsRootCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterIncrementCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterSetCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUploadMetarunnersCommand
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.createBuildStep
import org.octopusden.octopus.infrastructure.teamcity.client.deleteProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityAgentRequirement
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRootEntry
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityStep
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.BuildTypeLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.VcsRootLocator
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildSteps
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildTypeVcsRootEntries
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildTypes
import org.octopusden.octopus.infrastructure.teamcity.client.getProject
import org.octopusden.octopus.infrastructure.teamcity.client.getSnapshotDependencies
import org.octopusden.octopus.infrastructure.teamcity.client.getVcsRoot
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.stream.Stream

class ApplicationTest {
    private val jar = System.getProperty("jar") ?: throw IllegalStateException("System property 'jar' must be provided")
    private val javaBin = "${System.getProperty("java.home")}/bin/java"
    private lateinit var testInfo: TestInfo

    private fun execute(
        name: String,
        vararg command: String,
    ) = ProcessBuilder(javaBin, "-jar", jar, *command)
        .redirectErrorStream(true)
        .redirectOutput(
            File("")
                .resolve("build")
                .resolve("logs")
                .resolve("$name.log")
                .also { it.parentFile.mkdirs() },
        ).start()
        .waitFor()

    private fun executeForCreateBuildChainCommand(
        config: TeamcityTestConfiguration,
        testMethodName: String,
        componentName: String,
        minorVersion: String? = "1.0",
        createChecklist: Boolean = true,
        createRcForce: Boolean = false,
        registryUrl: String = "http://$hostComponentsRegistry",
    ): Int =
        execute(
            testMethodName,
            *getTeamcityOptions(config),
            TeamcityCreateBuildChainCommand.COMMAND,
            "${TeamcityCreateBuildChainCommand.PARENT}=$TEST_PROJECT",
            "${TeamcityCreateBuildChainCommand.COMPONENT}=$componentName",
            "${TeamcityCreateBuildChainCommand.VERSION}=$minorVersion",
            "${TeamcityCreateBuildChainCommand.CR}=$registryUrl",
            "${TeamcityCreateBuildChainCommand.CREATE_CHECKLIST}=$createChecklist",
            "${TeamcityCreateBuildChainCommand.CREATE_RC_FORCE}=$createRcForce",
        )

    private fun logContent(testMethodName: String): String =
        File("")
            .resolve("build")
            .resolve("logs")
            .resolve("$testMethodName.log")
            .readText()

    private fun TeamcityVcsRoot.property(name: String): String? =
        requireNotNull(properties).properties.associate { it.name to it.value }[name]

    /**
     * One registry VCS root for [StubComponentsRegistry]'s v2 `getDetailedComponent` body: the
     * placement fields (`checkoutDirectory`, `sourcePath`) the docker registry container's Groovy
     * DSL cannot produce (proposal.md, Impact).
     */
    private data class StubVcsRoot(
        val name: String,
        val vcsPath: String,
        val type: String = "GIT",
        val branch: String = "master",
        val checkoutDirectory: String? = null,
        val sourcePath: String? = null,
    )

    /**
     * A minimal v2 `getDetailedComponent` HTTP endpoint (`GET rest/api/2/components/{key}/versions/{version}`),
     * for the placement scenarios the docker registry container cannot serve. No new test dependency:
     * `com.sun.net.httpserver.HttpServer` ships with the JDK.
     */
    private class StubComponentsRegistry {
        private val server = HttpServer.create(InetSocketAddress(0), 0).also { it.start() }
        val url: String = "http://localhost:${server.address.port}"

        fun serve(
            componentKey: String,
            version: String,
            roots: List<StubVcsRoot>,
            buildWorkingDirectory: String? = null,
            distribution: Boolean = false,
        ) {
            val json = detailedComponentJson(componentKey, version, roots, buildWorkingDirectory, distribution)
            server.createContext("/rest/api/2/components/$componentKey/versions/$version") { exchange ->
                val body = json.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }

        fun stop() = server.stop(0)

        private fun detailedComponentJson(
            componentKey: String,
            version: String,
            roots: List<StubVcsRoot>,
            buildWorkingDirectory: String?,
            distribution: Boolean,
        ): String {
            val rootsJson = roots.joinToString(",") { root ->
                buildString {
                    append("{\"name\":\"${root.name}\",\"vcsPath\":\"${root.vcsPath}\",\"type\":\"${root.type}\",")
                    append("\"tag\":\"stub-tag\",\"branch\":\"${root.branch}\"")
                    root.checkoutDirectory?.let { append(",\"checkoutDirectory\":\"$it\"") }
                    root.sourcePath?.let { append(",\"sourcePath\":\"$it\"") }
                    append("}")
                }
            }
            val buildWorkingDirectoryField = buildWorkingDirectory?.let { ",\"buildWorkingDirectory\":\"$it\"" } ?: ""
            // language=JSON
            return """
                {
                  "id": "$componentKey",
                  "name": "$componentKey",
                  "componentOwner": "$TEST_USER",
                  "buildSystem": "MAVEN",
                  "vcsSettings": {
                    "versionControlSystemRoots": [$rootsJson],
                    "externalRegistry": null$buildWorkingDirectoryField
                  },
                  "jiraComponentVersion": {
                    "name": "$componentKey",
                    "version": "$version",
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
                      "componentInfo": {
                        "versionPrefix": "stub",
                        "versionFormat": "${'$'}versionPrefix-${'$'}baseVersionFormat"
                      },
                      "technical": false
                    }
                  },
                  "detailedComponentVersion": {
                    "component": "$componentKey",
                    "minorVersion": {"type": "MINOR", "version": "$version", "jiraVersion": "stub-$version"},
                    "lineVersion": {"type": "LINE", "version": "$version", "jiraVersion": "stub-$version"},
                    "buildVersion": {"type": "BUILD", "version": "$version.0", "jiraVersion": "stub-$version.0"},
                    "rcVersion": {"type": "RC", "version": "$version.0_RC", "jiraVersion": "stub-$version.0_RC"},
                    "releaseVersion": {"type": "RELEASE", "version": "$version.0", "jiraVersion": "stub-$version.0"}
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
                  "distribution": ${if (distribution) """{"explicit": true, "external": true, "securityGroups": {"read": []}}""" else "null"},
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
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityUpdateParameterSet(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val parameter = "TEST_PARAMETER"
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter, "OLD")
        Assertions.assertEquals(
            0,
            execute(
                testInfo.testMethod.get().name,
                *getTeamcityOptions(config),
                TeamcityUpdateParameterCommand.COMMAND,
                "${TeamcityUpdateParameterCommand.NAME_OPTION}=$parameter",
                "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}=$TEST_SUBPROJECT_2;$TEST_PROJECT",
                "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=$TEST_BUILD_1,$TEST_SUBPROJECT_1_BUILD_1",
                TeamcityUpdateParameterSetCommand.COMMAND,
                "${TeamcityUpdateParameterSetCommand.VALUE_OPTION}=NEW",
            ),
        )
        Assertions.assertEquals(
            "NEW",
            teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_PROJECT, parameter),
        )
        Assertions.assertEquals(
            "NEW",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter),
        )
        Assertions.assertEquals(
            "OLD",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter),
        )
        Assertions.assertEquals(
            "OLD",
            teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter),
        )
        Assertions.assertEquals(
            "NEW",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_1, parameter),
        )
        Assertions.assertEquals(
            "OLD",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_2, parameter),
        )
        Assertions.assertEquals(
            "NEW",
            teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_2, parameter),
        )
        Assertions.assertEquals(
            "OLD",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter),
        )
        Assertions.assertEquals(
            "NEW",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_2, parameter),
        )
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityUpdateParameterIncrement(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val parameter = "TEST_PARAMETER"
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter, "1.0")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter, "1.1")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter, "1.2")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_2, parameter, "INVALID")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter, "1.3")
        Assertions.assertEquals(
            0,
            execute(
                testInfo.testMethod.get().name,
                *getTeamcityOptions(config),
                TeamcityUpdateParameterCommand.COMMAND,
                "${TeamcityUpdateParameterCommand.NAME_OPTION}=$parameter",
                "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}=$TEST_SUBPROJECT_2,$TEST_PROJECT",
                "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=$TEST_BUILD_1;$TEST_SUBPROJECT_1_BUILD_1;$TEST_SUBPROJECT_1_BUILD_1",
                TeamcityUpdateParameterIncrementCommand.COMMAND,
            ),
        )
        Assertions.assertThrows(feign.FeignException.NotFound::class.java) {
            teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_PROJECT, parameter)
        }
        Assertions.assertEquals(
            "1.1",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter),
        )
        Assertions.assertEquals(
            "1.1",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter),
        )
        Assertions.assertEquals(
            "1.2",
            teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter),
        )
        Assertions.assertEquals(
            "1.3",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_1, parameter),
        )
        Assertions.assertEquals(
            "1.2",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_2, parameter),
        )
        Assertions.assertEquals(
            "INVALID",
            teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_2, parameter),
        )
        Assertions.assertEquals(
            "1.3",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter),
        )
        Assertions.assertEquals(
            "INVALID",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_2, parameter),
        )
    }

    private fun TestInfo.methodName() = testMethod.get().name

    /**
     * Tests CreateTeamCityBuildChain for explicit & external components.
     * Verifies:
     * - Project creation under specified parent project
     * - Presence of all build configurations (compile, RC, checklist, release)
     * - Template-based creation of build configurations
     * - Build configuration dependencies
     * - Disabled build step
     * - Parameter assignments
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainForEEComponent(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val minorVersion = "1.0"
        val componentName = "ee-component"

        val projectId = "TestTeamcityAutomation_EeComponent"
        Assertions.assertEquals(
            0,
            executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName, minorVersion),
        )

        Assertions.assertEquals(TEST_PROJECT, teamcityClient.getProject(projectId).parentProjectId)
        val buildTypes = teamcityClient.getBuildTypes(projectId).buildTypes
        Assertions.assertEquals(4, buildTypes.size)

        val compileConfigId = "${projectId}_10CompileUtAuto"
        val rcConfigId = "${projectId}_20ReleaseCandidateManual"
        val checklistConfigId = "${projectId}_30ReleaseChecklistValidationManual"
        val releaseConfigId = "${projectId}_40ReleaseManual"

        validateBuildTypeTemplate(teamcityClient, rcConfigId, TeamcityCreateBuildChainCommand.TEMPLATE_RC)
        validateBuildTypeTemplate(teamcityClient, checklistConfigId, TeamcityCreateBuildChainCommand.TEMPLATE_CHECKLIST)
        validateBuildTypeTemplate(teamcityClient, releaseConfigId, TeamcityCreateBuildChainCommand.TEMPLATE_RELEASE)

        val buildTypesIdAndDependencyId = mapOf(
            compileConfigId to null,
            rcConfigId to compileConfigId,
            checklistConfigId to rcConfigId,
            releaseConfigId to rcConfigId,
        )

        buildTypesIdAndDependencyId.forEach { (buildTypesId, dependencyId) ->
            Assertions.assertNotNull(buildTypes.find { it.id == buildTypesId })
            Assertions.assertEquals(1, teamcityClient.getBuildTypeVcsRootEntries(buildTypesId).entries.size)
            dependencyId?.let {
                val snapshotDependencies = teamcityClient.getSnapshotDependencies(buildTypesId).snapshotDependencies
                Assertions.assertEquals(1, snapshotDependencies.size)
                Assertions.assertEquals(dependencyId, snapshotDependencies.get(0).sourceBuildType.id)
            }
        }

        validateSnapshotDependencyFailureAction(teamcityClient, rcConfigId, DependencyFailureAction.CANCEL)
        validateSnapshotDependencyFailureAction(teamcityClient, checklistConfigId, DependencyFailureAction.CANCEL)
        validateSnapshotDependencyFailureAction(teamcityClient, releaseConfigId, DependencyFailureAction.CANCEL)

        val buildSteps = teamcityClient.getBuildSteps(releaseConfigId).steps
        Assertions.assertEquals(2, buildSteps.size)
        Assertions.assertTrue(buildSteps.find { it.name == "IncrementTeamCityBuildConfigurationParameter" }?.disabled!!)

        val compileBuildVersion = "%dep.$compileConfigId.BUILD_VERSION%"
        Assertions.assertEquals(compileBuildVersion, teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, rcConfigId, "BUILD_VERSION"))
        Assertions.assertEquals(
            compileBuildVersion,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, checklistConfigId, "BUILD_VERSION"),
        )
        Assertions.assertEquals(
            compileBuildVersion,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BUILD_VERSION"),
        )

        Assertions.assertEquals(
            compileConfigId,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BASE_CONFIGURATION_ID"),
        )

        Assertions.assertEquals(componentName, teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "COMPONENT_NAME"))
        Assertions.assertEquals(minorVersion, teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "PROJECT_VERSION"))
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainGrantsProjectAdminRole(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val componentName = "ee-component"
        val projectId = "TestTeamcityAutomation_EeComponent"

        Assertions.assertEquals(
            0,
            executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName),
        )

        listOf(TEST_USER, TEST_USER_2).forEach { username ->
            val roles = getUserRoles(config.host, username)
            val hasProjectAdmin = roles.any { it.roleId == "PROJECT_ADMIN" && it.scope == "p:$projectId" }
            Assertions.assertTrue(hasProjectAdmin, "User '$username' should have PROJECT_ADMIN role on project '$projectId'")
        }
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainWithNonExistentUser(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val componentName = "nonexistent-user-component"
        val projectId = "TestTeamcityAutomation_NonexistentUserComponent"

        Assertions.assertEquals(
            0,
            executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName),
        )
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainForEEComponentWithoutCheckList(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val minorVersion = "1.0"
        val componentName = "ee-component"

        val projectId = "TestTeamcityAutomation_EeComponent"
        Assertions.assertEquals(
            0,
            executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName, minorVersion, false),
        )

        Assertions.assertEquals(TEST_PROJECT, teamcityClient.getProject(projectId).parentProjectId)
        val buildTypes = teamcityClient.getBuildTypes(projectId).buildTypes
        Assertions.assertEquals(3, buildTypes.size)

        val compileConfigId = "${projectId}_10CompileUtAuto"
        val rcConfigId = "${projectId}_20ReleaseCandidateManual"
        val releaseConfigId = "${projectId}_30ReleaseManual"

        validateBuildTypeTemplate(teamcityClient, rcConfigId, TeamcityCreateBuildChainCommand.TEMPLATE_RC)
        validateBuildTypeTemplate(teamcityClient, releaseConfigId, TeamcityCreateBuildChainCommand.TEMPLATE_RELEASE)

        val buildTypesIdAndDependencyId = mapOf(
            compileConfigId to null,
            rcConfigId to compileConfigId,
            releaseConfigId to rcConfigId,
        )

        buildTypesIdAndDependencyId.forEach { (buildTypesId, dependencyId) ->
            Assertions.assertNotNull(buildTypes.find { it.id == buildTypesId })
            Assertions.assertEquals(1, teamcityClient.getBuildTypeVcsRootEntries(buildTypesId).entries.size)
            dependencyId?.let {
                val snapshotDependencies = teamcityClient.getSnapshotDependencies(buildTypesId).snapshotDependencies
                Assertions.assertEquals(1, snapshotDependencies.size)
                Assertions.assertEquals(dependencyId, snapshotDependencies.get(0).sourceBuildType.id)
            }
        }

        validateSnapshotDependencyFailureAction(teamcityClient, rcConfigId, DependencyFailureAction.CANCEL)
        validateSnapshotDependencyFailureAction(teamcityClient, releaseConfigId, DependencyFailureAction.CANCEL)

        val buildSteps = teamcityClient.getBuildSteps(releaseConfigId).steps
        Assertions.assertEquals(2, buildSteps.size)
        Assertions.assertTrue(buildSteps.find { it.name == "IncrementTeamCityBuildConfigurationParameter" }?.disabled!!)

        val compileBuildVersion = "%dep.$compileConfigId.BUILD_VERSION%"
        Assertions.assertEquals(
            compileBuildVersion,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, rcConfigId, "BUILD_VERSION"),
        )
        Assertions.assertEquals(
            compileBuildVersion,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BUILD_VERSION"),
        )

        Assertions.assertEquals(
            compileConfigId,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BASE_CONFIGURATION_ID"),
        )

        Assertions.assertEquals(
            componentName,
            teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "COMPONENT_NAME"),
        )
        Assertions.assertEquals(
            minorVersion,
            teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "PROJECT_VERSION"),
        )
    }

    /**
     * Tests CreateTeamCityBuildChain for non explicit & external components.
     * Verifies:
     * - Project creation under specified parent project
     * - Presence of compile & release build configurations
     * - Template-based creation of build configurations
     * - Build configuration dependencies
     * - Disabled build step
     * - Parameter assignments
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainForNonEEComponent(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val minorVersion = "1.0"
        val componentNamesToProjectId = mapOf(
            "ie-component" to "TestTeamcityAutomation_IeComponent",
            "ei-component" to "TestTeamcityAutomation_EiComponent",
            "ii-component" to "TestTeamcityAutomation_IiComponent",
        )

        componentNamesToProjectId.forEach { (componentName, projectId) ->
            Assertions.assertEquals(
                0,
                executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName, minorVersion),
            )

            Assertions.assertEquals(TEST_PROJECT, teamcityClient.getProject(projectId).parentProjectId)
            val buildTypes = teamcityClient.getBuildTypes(projectId).buildTypes
            Assertions.assertEquals(2, buildTypes.size)

            val compileConfigId = "${projectId}_10CompileUtAuto"
            val releaseConfigId = "${projectId}_20ReleaseManual"

            validateBuildTypeTemplate(teamcityClient, releaseConfigId, TeamcityCreateBuildChainCommand.TEMPLATE_RELEASE)

            val buildTypesIdAndDependencyId = mapOf(
                compileConfigId to null,
                releaseConfigId to compileConfigId,
            )

            buildTypesIdAndDependencyId.forEach { (buildTypesId, dependencyId) ->
                Assertions.assertNotNull(buildTypes.find { it.id == buildTypesId })
                Assertions.assertEquals(1, teamcityClient.getBuildTypeVcsRootEntries(buildTypesId).entries.size)
                dependencyId?.let {
                    val snapshotDependencies = teamcityClient.getSnapshotDependencies(buildTypesId).snapshotDependencies
                    Assertions.assertEquals(1, snapshotDependencies.size)
                    Assertions.assertEquals(dependencyId, snapshotDependencies.get(0).sourceBuildType.id)
                }
            }

            validateSnapshotDependencyFailureAction(teamcityClient, releaseConfigId, DependencyFailureAction.CANCEL)

            val buildSteps = teamcityClient.getBuildSteps(releaseConfigId).steps
            Assertions.assertEquals(2, buildSteps.size)
            Assertions.assertTrue(buildSteps.find { it.name == "IncrementTeamCityBuildConfigurationParameter" }?.disabled!!)

            val compileBuildVersion = "%dep.$compileConfigId.BUILD_VERSION%"
            Assertions.assertEquals(
                compileBuildVersion,
                teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BUILD_VERSION"),
            )

            Assertions.assertEquals(
                compileConfigId,
                teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BASE_CONFIGURATION_ID"),
            )

            Assertions.assertEquals(componentName, teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "COMPONENT_NAME"))
            Assertions.assertEquals(minorVersion, teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "PROJECT_VERSION"))
        }
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainForIEComponentWithRc(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val minorVersion = "1.0"
        val projectId = "TestTeamcityAutomation_IeComponent"
        val componentName = "ie-component"

        Assertions.assertEquals(
            0,
            executeForCreateBuildChainCommand(
                config,
                testInfo.methodName(),
                componentName,
                minorVersion,
                createChecklist = false,
                createRcForce = true,
            ),
        )

        Assertions.assertEquals(TEST_PROJECT, teamcityClient.getProject(projectId).parentProjectId)
        val buildTypes = teamcityClient.getBuildTypes(projectId).buildTypes
        Assertions.assertEquals(3, buildTypes.size)

        val compileConfigId = "${projectId}_10CompileUtAuto"
        val rcConfigId = "${projectId}_20ReleaseCandidateManual"
        val releaseConfigId = "${projectId}_30ReleaseManual"

        validateBuildTypeTemplate(teamcityClient, rcConfigId, TeamcityCreateBuildChainCommand.TEMPLATE_RC)
        validateBuildTypeTemplate(teamcityClient, releaseConfigId, TeamcityCreateBuildChainCommand.TEMPLATE_RELEASE)

        val buildTypesIdAndDependencyId = mapOf(
            compileConfigId to null,
            rcConfigId to compileConfigId,
            releaseConfigId to rcConfigId,
        )

        buildTypesIdAndDependencyId.forEach { (buildTypesId, dependencyId) ->
            Assertions.assertNotNull(buildTypes.find { it.id == buildTypesId })
            Assertions.assertEquals(1, teamcityClient.getBuildTypeVcsRootEntries(buildTypesId).entries.size)
            dependencyId?.let {
                val snapshotDependencies = teamcityClient.getSnapshotDependencies(buildTypesId).snapshotDependencies
                Assertions.assertEquals(1, snapshotDependencies.size)
                Assertions.assertEquals(dependencyId, snapshotDependencies.get(0).sourceBuildType.id)
            }
        }

        validateSnapshotDependencyFailureAction(teamcityClient, rcConfigId, DependencyFailureAction.CANCEL)
        validateSnapshotDependencyFailureAction(teamcityClient, releaseConfigId, DependencyFailureAction.CANCEL)

        val buildSteps = teamcityClient.getBuildSteps(releaseConfigId).steps
        Assertions.assertEquals(2, buildSteps.size)
        Assertions.assertTrue(buildSteps.find { it.name == "IncrementTeamCityBuildConfigurationParameter" }?.disabled!!)

        val compileBuildVersion = "%dep.$compileConfigId.BUILD_VERSION%"
        Assertions.assertEquals(
            compileBuildVersion,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BUILD_VERSION"),
        )

        Assertions.assertEquals(
            compileConfigId,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BASE_CONFIGURATION_ID"),
        )

        Assertions.assertEquals(
            componentName,
            teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "COMPONENT_NAME"),
        )
        Assertions.assertEquals(
            minorVersion,
            teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "PROJECT_VERSION"),
        )
    }

    /**
     * Tests CreateTeamCityBuildChain for overriding the JDK_VERSION parameter in compile build configurations based on the component's javaVersion.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainForJDKVersion(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val defaultJDKComponentName = "default-jdk-component"
        val defaultJDKProjectId = "TestTeamcityAutomation_DefaultJdkComponent"
        val customJDKComponentName = "custom-jdk-component"
        val customJDKProjectId = "TestTeamcityAutomation_CustomJdkComponent"

        val componentNamesToProjectId = mapOf(
            defaultJDKComponentName to defaultJDKProjectId,
            customJDKComponentName to customJDKProjectId,
        )

        componentNamesToProjectId.forEach { (componentName, projectId) ->
            Assertions.assertEquals(
                0,
                executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName),
            )
            val nonCompileConfigIds = listOf(
                "${projectId}_20ReleaseCandidateManual",
                "${projectId}_30ReleaseChecklistValidationManual",
                "${projectId}_40ReleaseManual",
            )
            nonCompileConfigIds.forEach { configId ->
                Assertions.assertEquals("1.8", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, configId, "JDK_VERSION"))
            }
        }

        Assertions.assertEquals(
            "1.8",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, "${defaultJDKProjectId}_10CompileUtAuto", "JDK_VERSION"),
        )
        Assertions.assertEquals(
            "11",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, "${customJDKProjectId}_10CompileUtAuto", "JDK_VERSION"),
        )
    }

    /**
     * Tests CreateTeamCityBuildChain to verify the template used in compile build configurations aligns with the component's build system.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainForCompileTemplate(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val componentNames = listOf("maven-component", "gradle-component", "provided-component", "in-container-component")

        componentNames.forEach { componentName ->
            Assertions.assertEquals(
                0,
                executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName),
            )
        }

        validateBuildTypeTemplate(
            teamcityClient,
            "TestTeamcityAutomation_MavenComponent_10CompileUtAuto",
            TeamcityCreateBuildChainCommand.TEMPLATE_MAVEN_COMPILE,
        )
        validateBuildTypeTemplate(
            teamcityClient,
            "TestTeamcityAutomation_GradleComponent_10CompileUtAuto",
            TeamcityCreateBuildChainCommand.TEMPLATE_GRADLE_COMPILE,
        )
        validateBuildTypeTemplate(
            teamcityClient,
            "TestTeamcityAutomation_ProvidedComponent_10CompileUtAuto",
            TeamcityCreateBuildChainCommand.TEMPLATE_GRADLE_COMPILE,
        )
        validateBuildTypeTemplate(
            teamcityClient,
            "TestTeamcityAutomation_InContainerComponent_10CompileUtAuto",
            TeamcityCreateBuildChainCommand.TEMPLATE_GRADLE_COMPILE,
        )

        Assertions.assertEquals(
            1,
            executeForCreateBuildChainCommand(config, testInfo.methodName(), "not-supported-component"),
        )
    }

    /**
     * Baseline (ONB-001): pins today's single-VCS-root chain layout. One project-level Git VCS root
     * named `<projectId>_VCS_ROOT` (branch `master`, branch spec `+:<default>`) attached to every
     * created build configuration without checkout rules.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testBaselineCreateBuildChainAttachesSingleVcsRootWithoutCheckoutRules(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val projectId = "TestTeamcityAutomation_EeComponent"
        Assertions.assertEquals(0, executeForCreateBuildChainCommand(config, testInfo.methodName(), "ee-component"))

        validateSingleChainVcsRoot(
            teamcityClient,
            projectId,
            "https://github.com/octopusden/octopus-teamcity-automation.git",
            listOf(
                "${projectId}_10CompileUtAuto",
                "${projectId}_20ReleaseCandidateManual",
                "${projectId}_30ReleaseChecklistValidationManual",
                "${projectId}_40ReleaseManual",
            ),
        )
    }

    /**
     * ONB-001 behaviour change (proposal.md, Impact): the Groovy DSL test registry carries no
     * placement fields, so `two-vcs-root-component`'s two roots both have no Checkout Directory.
     * Where the baseline silently built a chain from the first root, the generator now fails
     * before creating anything ("more than one root at the checkout root", spec.md "Unsupported
     * shapes fail before creation"). Superseded name kept ("UsesOnlyFirst...") since this is the
     * same baseline scenario, now asserting the new outcome.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testBaselineCreateBuildChainUsesOnlyFirstRegistryVcsRoot(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val projectId = "TestTeamcityAutomation_TwoVcsRootComponent"
        val exitCode = executeForCreateBuildChainCommand(config, testInfo.methodName(), "two-vcs-root-component")
        Assertions.assertNotEquals(0, exitCode)
        val log = logContent(testInfo.methodName())
        Assertions.assertTrue(log.contains("positions 1, 2"), log)
        Assertions.assertThrows(feign.FeignException.NotFound::class.java) {
            teamcityClient.getProject(projectId)
        }
    }

    /**
     * Two registry roots, naming by registry position (spec.md "One TeamCity VCS root per registry
     * VCS Root"): B, Checkout Directory `feature`, listed first; A, no Checkout Directory, listed
     * second (root-plus-subfolder). Covers the "Checkout Directory only" rule, attach order (A first
     * although listed second), and that no WORK_DIR/COMPONENT_CONFIG_DIR/version-format WARNING
     * appear without a Build Working Directory.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testCreateBuildChainPlacesTwoRootsRootPlusSubfolder(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)
        val stub = StubComponentsRegistry()
        try {
            stub.serve(
                "root-plus-subfolder-component",
                "1.0",
                listOf(
                    StubVcsRoot(name = "root-b", vcsPath = "ssh://git@example.test/proj/root-b.git", checkoutDirectory = "feature"),
                    StubVcsRoot(name = "root-a", vcsPath = "ssh://git@example.test/proj/root-a.git"),
                ),
            )
            val projectId = "TestTeamcityAutomation_RootPlusSubfolderComponent"
            Assertions.assertEquals(
                0,
                executeForCreateBuildChainCommand(
                    config,
                    testInfo.methodName(),
                    "root-plus-subfolder-component",
                    registryUrl = stub.url,
                ),
            )

            val vcsRoots = teamcityClient.getVcsRoots(VcsRootLocator(project = ProjectLocator(id = projectId))).vcsRoots
            Assertions.assertEquals(2, vcsRoots.size)
            val rootB = teamcityClient.getVcsRoot(vcsRoots.single { it.name == "${projectId}_VCS_ROOT" }.id)
            val rootA = teamcityClient.getVcsRoot(vcsRoots.single { it.name == "${projectId}_VCS_ROOT_2" }.id)
            Assertions.assertEquals("ssh://git@example.test/proj/root-b.git", rootB.property("url"))
            Assertions.assertEquals("ssh://git@example.test/proj/root-a.git", rootA.property("url"))

            val compileConfigId = "${projectId}_10CompileUtAuto"
            val entries = teamcityClient.getBuildTypeVcsRootEntries(compileConfigId).entries
            Assertions.assertEquals(2, entries.size)
            // A (no Checkout Directory) attaches first although it is registry position 2 (root-plus-subfolder).
            Assertions.assertEquals(rootA.id, entries[0].vcsRoot.id)
            Assertions.assertTrue(entries[0].checkoutRules.isNullOrEmpty(), "checkout rules of A: '${entries[0].checkoutRules}'")
            Assertions.assertEquals(rootB.id, entries[1].vcsRoot.id)
            Assertions.assertEquals("+:. => feature", entries[1].checkoutRules)

            listOf("WORK_DIR", "COMPONENT_CONFIG_DIR", "BUILD_VERSION_FORMAT_FILE").forEach { parameter ->
                Assertions.assertThrows(feign.FeignException.NotFound::class.java, {
                    teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, compileConfigId, parameter)
                }, "$parameter on $compileConfigId")
            }
            Assertions.assertFalse(logContent(testInfo.methodName()).contains("BUILD_VERSION_FORMAT_FILE"))
        } finally {
            stub.stop()
        }
    }

    /**
     * Covers the two remaining checkout-rule shapes (spec.md "Checkout rule from placement"):
     * Checkout Directory and Source Path together, and Source Path alone.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testCreateBuildChainChecksCheckoutDirectoryAndSourcePathRules(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)
        val stub = StubComponentsRegistry()
        try {
            stub.serve(
                "cd-sp-component",
                "1.0",
                listOf(
                    StubVcsRoot(
                        name = "root-both",
                        vcsPath = "ssh://git@example.test/proj/root-both.git",
                        checkoutDirectory = "feature",
                        sourcePath = "data",
                    ),
                    StubVcsRoot(
                        name = "root-sp",
                        vcsPath = "ssh://git@example.test/proj/root-sp.git",
                        sourcePath = "mapper",
                    ),
                ),
            )
            val projectId = "TestTeamcityAutomation_CdSpComponent"
            Assertions.assertEquals(
                0,
                executeForCreateBuildChainCommand(config, testInfo.methodName(), "cd-sp-component", registryUrl = stub.url),
            )

            val compileConfigId = "${projectId}_10CompileUtAuto"
            val vcsRoots = teamcityClient.getVcsRoots(VcsRootLocator(project = ProjectLocator(id = projectId))).vcsRoots
            val rootBoth = teamcityClient.getVcsRoot(vcsRoots.single { it.name == "${projectId}_VCS_ROOT" }.id)
            val rootSp = teamcityClient.getVcsRoot(vcsRoots.single { it.name == "${projectId}_VCS_ROOT_2" }.id)
            val entries = teamcityClient.getBuildTypeVcsRootEntries(compileConfigId).entries.associateBy { it.vcsRoot.id }
            Assertions.assertEquals("+:data => feature/data", entries.getValue(rootBoth.id).checkoutRules)
            Assertions.assertEquals("+:mapper => mapper", entries.getValue(rootSp.id).checkoutRules)
        } finally {
            stub.stop()
        }
    }

    /**
     * Build Working Directory in the second registry root (spec.md "Attach order follows the Build
     * Working Directory" and "Build Working Directory parameters"): attach order still follows the
     * root holding it, WORK_DIR/COMPONENT_CONFIG_DIR land on every created configuration (the
     * explicit/external shape, so all four: compile, RC, checklist, release), BUILD_VERSION_FORMAT_FILE
     * lands only on configurations that carry a 'Calculate Build Version' step (compile, matching
     * production: only the compile template has it there), and an INFO naming the parameter is
     * logged (owner decision on ADR-001's open question).
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testCreateBuildChainSetsWorkDirFromBuildWorkingDirectory(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)
        teamcityClient.createBuildStep(
            TeamcityCreateBuildChainCommand.TEMPLATE_MAVEN_COMPILE,
            step = TeamcityStep(
                "CalculateBuildVersion",
                "Calculate Build Version",
                "CalculateBuildVersion",
                disabled = false,
                properties = TeamcityProperties(listOf(TeamcityProperty("version-format-file", "%BUILD_VERSION_FORMAT_FILE%"))),
            ),
        )
        val stub = StubComponentsRegistry()
        try {
            stub.serve(
                "bwd-component",
                "1.0",
                listOf(
                    StubVcsRoot(name = "root-b", vcsPath = "ssh://git@example.test/proj/root-b.git", checkoutDirectory = "feature"),
                    StubVcsRoot(name = "root-a", vcsPath = "ssh://git@example.test/proj/root-a.git", checkoutDirectory = "core"),
                ),
                buildWorkingDirectory = "core/mapper",
                distribution = true,
            )
            val projectId = "TestTeamcityAutomation_BwdComponent"
            Assertions.assertEquals(
                0,
                executeForCreateBuildChainCommand(config, testInfo.methodName(), "bwd-component", registryUrl = stub.url),
            )

            val vcsRoots = teamcityClient.getVcsRoots(VcsRootLocator(project = ProjectLocator(id = projectId))).vcsRoots
            val rootA = teamcityClient.getVcsRoot(vcsRoots.single { it.name == "${projectId}_VCS_ROOT_2" }.id)

            val compileConfigId = "${projectId}_10CompileUtAuto"
            val rcConfigId = "${projectId}_20ReleaseCandidateManual"
            val checklistConfigId = "${projectId}_30ReleaseChecklistValidationManual"
            val releaseConfigId = "${projectId}_40ReleaseManual"
            val entries = teamcityClient.getBuildTypeVcsRootEntries(compileConfigId).entries
            Assertions.assertEquals(rootA.id, entries[0].vcsRoot.id, "root A (holds the Build Working Directory) attaches first")

            listOf(compileConfigId, rcConfigId, checklistConfigId, releaseConfigId).forEach { configId ->
                listOf("WORK_DIR", "COMPONENT_CONFIG_DIR").forEach { parameter ->
                    Assertions.assertEquals(
                        "%teamcity.build.checkoutDir%/core/mapper",
                        teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, configId, parameter),
                        "$parameter on $configId",
                    )
                }
            }
            Assertions.assertEquals(
                "core/mapper/build-version-format.properties",
                teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, compileConfigId, "BUILD_VERSION_FORMAT_FILE"),
                "BUILD_VERSION_FORMAT_FILE on $compileConfigId",
            )
            listOf(rcConfigId, checklistConfigId, releaseConfigId).forEach { configId ->
                Assertions.assertThrows(feign.FeignException.NotFound::class.java, {
                    teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, configId, "BUILD_VERSION_FORMAT_FILE")
                }, "BUILD_VERSION_FORMAT_FILE on $configId: no 'Calculate Build Version' step there")
            }
            val log = logContent(testInfo.methodName())
            Assertions.assertFalse(log.contains("WARN"), log)
            Assertions.assertTrue(log.contains("INFO"), log)
            Assertions.assertTrue(log.contains("BUILD_VERSION_FORMAT_FILE"), log)
            Assertions.assertTrue(log.contains("core/mapper/build-version-format.properties"), log)
        } finally {
            stub.stop()
        }
    }

    /**
     * Default branch from the registry (spec.md "Default branch from the registry"): the first
     * `|`-alternative, trimmed, and a WARNING when the resolved value still contains `null`.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testCreateBuildChainSetsDefaultBranchFromRegistry(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)
        val stub = StubComponentsRegistry()
        try {
            stub.serve(
                "branch-component",
                "1.0",
                listOf(
                    StubVcsRoot(name = "root-list", vcsPath = "ssh://git@example.test/proj/root-list.git", branch = "main|release/1.2"),
                    StubVcsRoot(
                        name = "root-null",
                        vcsPath = "ssh://git@example.test/proj/root-null.git",
                        branch = "release/null",
                        checkoutDirectory = "unresolved",
                    ),
                ),
            )
            val projectId = "TestTeamcityAutomation_BranchComponent"
            Assertions.assertEquals(
                0,
                executeForCreateBuildChainCommand(config, testInfo.methodName(), "branch-component", registryUrl = stub.url),
            )

            val vcsRoots = teamcityClient.getVcsRoots(VcsRootLocator(project = ProjectLocator(id = projectId))).vcsRoots
            val rootList = teamcityClient.getVcsRoot(vcsRoots.single { it.name == "${projectId}_VCS_ROOT" }.id)
            val rootNull = teamcityClient.getVcsRoot(vcsRoots.single { it.name == "${projectId}_VCS_ROOT_2" }.id)
            Assertions.assertEquals("main", rootList.property("branch"))
            Assertions.assertEquals("+:<default>", rootList.property("teamcity:branchSpec"))
            Assertions.assertEquals("release/null", rootNull.property("branch"))

            val log = logContent(testInfo.methodName())
            Assertions.assertTrue(log.contains("WARN"), log)
            Assertions.assertTrue(log.contains(rootNull.name), log)
            Assertions.assertTrue(log.contains("release/null"), log)
        } finally {
            stub.stop()
        }
    }

    /**
     * Unsupported shapes fail before anything is created (spec.md "Unsupported shapes fail before
     * creation"): repeated repository (compared case-insensitively, ADR-001 decision 4), a non-Git
     * root, and a Checkout Directory colliding with `RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR`.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testCreateBuildChainFailsForRepeatedRepository(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)
        val stub = StubComponentsRegistry()
        try {
            stub.serve(
                "repeated-repo-component",
                "1.0",
                listOf(
                    StubVcsRoot(name = "root-1", vcsPath = "ssh://git@example.test/proj/repo.git", checkoutDirectory = "one"),
                    StubVcsRoot(name = "root-2", vcsPath = "SSH://GIT@EXAMPLE.TEST/proj/repo.git", checkoutDirectory = "two"),
                ),
            )
            val projectId = "TestTeamcityAutomation_RepeatedRepoComponent"
            val exitCode = executeForCreateBuildChainCommand(
                config,
                testInfo.methodName(),
                "repeated-repo-component",
                registryUrl = stub.url,
            )
            Assertions.assertNotEquals(0, exitCode)
            Assertions.assertThrows(feign.FeignException.NotFound::class.java) {
                teamcityClient.getProject(projectId)
            }
        } finally {
            stub.stop()
        }
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testCreateBuildChainFailsForNonGitRoot(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)
        val stub = StubComponentsRegistry()
        try {
            stub.serve(
                "non-git-component",
                "1.0",
                listOf(StubVcsRoot(name = "root-1", vcsPath = "svn://example.test/proj/repo", type = "MERCURIAL")),
            )
            val projectId = "TestTeamcityAutomation_NonGitComponent"
            val exitCode = executeForCreateBuildChainCommand(
                config,
                testInfo.methodName(),
                "non-git-component",
                registryUrl = stub.url,
            )
            Assertions.assertNotEquals(0, exitCode)
            Assertions.assertThrows(feign.FeignException.NotFound::class.java) {
                teamcityClient.getProject(projectId)
            }
        } finally {
            stub.stop()
        }
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testCreateBuildChainFailsForReservedCheckoutDirectory(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)
        val stub = StubComponentsRegistry()
        try {
            stub.serve(
                "reserved-cd-component",
                "1.0",
                listOf(
                    StubVcsRoot(
                        name = "root-1",
                        vcsPath = "ssh://git@example.test/proj/repo.git",
                        checkoutDirectory = RESERVED_CHECKOUT_DIRECTORY_VALUE,
                    ),
                ),
            )
            val projectId = "TestTeamcityAutomation_ReservedCdComponent"
            val exitCode = executeForCreateBuildChainCommand(
                config,
                testInfo.methodName(),
                "reserved-cd-component",
                registryUrl = stub.url,
            )
            Assertions.assertNotEquals(0, exitCode)
            Assertions.assertThrows(feign.FeignException.NotFound::class.java) {
                teamcityClient.getProject(projectId)
            }
        } finally {
            stub.stop()
        }
    }

    /**
     * spec.md "Unsupported shapes fail before creation" reads
     * RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR "as JDK_VERSION is read today": when the parent
     * project has no such parameter, there is no reserved value to collide with, and a Checkout
     * Directory that happens to match the constant used elsewhere in this suite is accepted.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testCreateBuildChainAllowsCheckoutDirectoryWhenReservedParameterUnset(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)
        teamcityClient.deleteParameter(ConfigurationType.PROJECT, TEST_PROJECT, RESERVED_CHECKOUT_DIRECTORY_PARAMETER)
        val stub = StubComponentsRegistry()
        try {
            stub.serve(
                "unset-reserved-cd-component",
                "1.0",
                listOf(
                    StubVcsRoot(
                        name = "root-1",
                        vcsPath = "ssh://git@example.test/proj/repo.git",
                        checkoutDirectory = RESERVED_CHECKOUT_DIRECTORY_VALUE,
                    ),
                ),
            )
            val projectId = "TestTeamcityAutomation_UnsetReservedCdComponent"
            Assertions.assertEquals(
                0,
                executeForCreateBuildChainCommand(
                    config,
                    testInfo.methodName(),
                    "unset-reserved-cd-component",
                    registryUrl = stub.url,
                ),
            )
            val vcsRoot = teamcityClient.getVcsRoot(
                teamcityClient
                    .getVcsRoots(VcsRootLocator(project = ProjectLocator(id = projectId)))
                    .vcsRoots
                    .single()
                    .id,
            )
            val entry = teamcityClient.getBuildTypeVcsRootEntries("${projectId}_10CompileUtAuto").entries.single()
            Assertions.assertEquals(
                "+:. => $RESERVED_CHECKOUT_DIRECTORY_VALUE",
                entry.checkoutRules,
                "checkout rules for $vcsRoot",
            )
        } finally {
            stub.stop()
        }
    }

    /**
     * Baseline (ONB-001): the generator sets no WORK_DIR and no COMPONENT_CONFIG_DIR anywhere in the
     * chain and leaves the template's *Calculate Build Version* step untouched in every created
     * configuration.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testBaselineCreateBuildChainSetsNoWorkDirNorStepOverride(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val calculateBuildVersion = "Calculate Build Version"
        val templateIds = listOf(
            TeamcityCreateBuildChainCommand.TEMPLATE_MAVEN_COMPILE,
            TeamcityCreateBuildChainCommand.TEMPLATE_RC,
            TeamcityCreateBuildChainCommand.TEMPLATE_CHECKLIST,
            TeamcityCreateBuildChainCommand.TEMPLATE_RELEASE,
        )
        templateIds.forEach {
            teamcityClient.createBuildStep(
                it,
                step = TeamcityStep(
                    "CalculateBuildVersion",
                    calculateBuildVersion,
                    "CalculateBuildVersion",
                    disabled = false,
                    properties = TeamcityProperties(listOf(TeamcityProperty("version-format-file", "build-version-format.properties"))),
                ),
            )
        }

        val projectId = "TestTeamcityAutomation_EeComponent"
        Assertions.assertEquals(0, executeForCreateBuildChainCommand(config, testInfo.methodName(), "ee-component"))

        listOf("WORK_DIR", "COMPONENT_CONFIG_DIR").forEach { parameter ->
            Assertions.assertThrows(feign.FeignException.NotFound::class.java, {
                teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, parameter)
            }, "$parameter on project")
        }
        val configIds = listOf(
            "${projectId}_10CompileUtAuto",
            "${projectId}_20ReleaseCandidateManual",
            "${projectId}_30ReleaseChecklistValidationManual",
            "${projectId}_40ReleaseManual",
        )
        configIds.zip(templateIds).forEach { (configId, templateId) ->
            listOf("WORK_DIR", "COMPONENT_CONFIG_DIR").forEach { parameter ->
                Assertions.assertThrows(feign.FeignException.NotFound::class.java, {
                    teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, configId, parameter)
                }, "$parameter on $configId")
            }

            fun TeamcityStep.snapshot() = listOf(name, type, disabled, properties?.properties?.associate { it.name to it.value })
            val templateStep = teamcityClient.getBuildSteps(templateId).steps.single { it.name == calculateBuildVersion }
            val configStep = teamcityClient.getBuildSteps(configId).steps.single { it.name == calculateBuildVersion }
            Assertions.assertEquals(templateStep.snapshot(), configStep.snapshot(), "$calculateBuildVersion step in $configId")
        }
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityUpdateParameterIncrementCurrent(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val parameter = "TEST_PARAMETER"
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter, "1.0")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter, "1.0.1")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_1, parameter, "1.1")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_2, parameter, "1.1.1")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter, "1.1.2")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_2, parameter, "1.2.1")
        Assertions.assertEquals(
            0,
            execute(
                testInfo.testMethod.get().name,
                *getTeamcityOptions(config),
                TeamcityUpdateParameterCommand.COMMAND,
                "${TeamcityUpdateParameterCommand.NAME_OPTION}=$parameter",
                "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=$TEST_BUILD_1,$TEST_BUILD_2;$TEST_SUBPROJECT_1_BUILD_1,$TEST_SUBPROJECT_1_BUILD_2;$TEST_SUBPROJECT_2_BUILD_1,$TEST_SUBPROJECT_2_BUILD_2",
                TeamcityUpdateParameterIncrementCommand.COMMAND,
                "${TeamcityUpdateParameterIncrementCommand.CURRENT_OPTION}=1.1.1",
            ),
        )
        Assertions.assertEquals(
            "1.0",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter),
        )
        Assertions.assertEquals(
            "1.0.1",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter),
        )
        Assertions.assertEquals(
            "1.2",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_1, parameter),
        )
        Assertions.assertEquals(
            "1.1.2",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_2, parameter),
        )
        Assertions.assertEquals(
            "1.1.2",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter),
        )
        Assertions.assertEquals(
            "1.2.1",
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_2, parameter),
        )
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityUploadMetarunners(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val metarunners = ApplicationTest::class.java.getResource("metarunners.zip")!!
        Assertions.assertEquals(
            0,
            execute(
                testInfo.testMethod.get().name,
                *getTeamcityOptions(config),
                TeamcityUploadMetarunnersCommand.COMMAND,
                "${TeamcityUploadMetarunnersCommand.PROJECT_ID_OPTION}=$TEST_PROJECT",
                "${TeamcityUploadMetarunnersCommand.ZIP_OPTION}=$metarunners",
            ),
        )

        validateUploadedMetarunners(config, teamcityClient)
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testGetBuildTypesAgentRequirements(config: TeamcityTestConfiguration) {
        val file = File("build").resolve("logs").resolve("${testInfo.testMethod.get().name}.csv")
        Assertions.assertEquals(
            0,
            execute(
                testInfo.testMethod.get().name,
                *getTeamcityOptions(config),
                TeamcityGetBuildTypesAgentRequirementsCommand.COMMAND,
                "${TeamcityGetBuildTypesAgentRequirementsCommand.FILE}=$file",
            ),
        )
        Assertions.assertTrue(file.exists())
        Assertions.assertTrue(file.readText().contains("teamcity.agent.jvm.os.name;Mac OS X"))
        file.delete()
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityReplaceVcsRoot(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient, config)

        val oldUrl = "ssh://git@example.org/old/repository.git"
        val newUrl = "ssh://git@example.org/new/repository.git"
        val created = teamcityClient.createVcsRoot(
            TeamcityCreateVcsRoot(
                name = "Old_Repository_For_Test",
                vcsName = TeamcityReplaceVcsRootCommand.VCS_JETBRAINS_GIT,
                projectLocator = "id:$TEST_PROJECT",
                properties = TeamcityProperties(
                    listOf(
                        TeamcityProperty(TeamcityReplaceVcsRootCommand.PROPERTY_URL, oldUrl),
                        TeamcityProperty(TeamcityReplaceVcsRootCommand.PROPERTY_BRANCH, "refs/heads/master"),
                        TeamcityProperty(
                            TeamcityReplaceVcsRootCommand.PROPERTY_BRANCH_SPEC,
                            TeamcityReplaceVcsRootCommand.PROPERTY_VALUE_BRANCH_SPEC,
                        ),
                        TeamcityProperty(
                            TeamcityReplaceVcsRootCommand.PROPERTY_USERNAME,
                            TeamcityReplaceVcsRootCommand.PROPERTY_VALUE_USERNAME,
                        ),
                        TeamcityProperty(
                            TeamcityReplaceVcsRootCommand.PROPERTY_AUTH_METHOD,
                            TeamcityReplaceVcsRootCommand.PROPERTY_VALUE_AUTH_METHOD,
                        ),
                        TeamcityProperty(
                            TeamcityReplaceVcsRootCommand.PROPERTY_USERNAME_STYLE,
                            TeamcityReplaceVcsRootCommand.PROPERTY_VALUE_USERNAME_STYLE,
                        ),
                        TeamcityProperty(
                            TeamcityReplaceVcsRootCommand.PROPERTY_SUBMODULE_CHECKOUT,
                            TeamcityReplaceVcsRootCommand.PROPERTY_VALUE_SUBMODULE_CHECKOUT,
                        ),
                        TeamcityProperty(TeamcityReplaceVcsRootCommand.PROPERTY_IGNORE_KNOWN_HOSTS, TeamcityReplaceVcsRootCommand.TRUE),
                        TeamcityProperty(
                            TeamcityReplaceVcsRootCommand.PROPERTY_AGENT_CLEAN_FILES_POLICY,
                            TeamcityReplaceVcsRootCommand.PROPERTY_VALUE_CLEAN_FILES_POLICY,
                        ),
                        TeamcityProperty(
                            TeamcityReplaceVcsRootCommand.PROPERTY_AGENT_CLEAN_POLICY,
                            TeamcityReplaceVcsRootCommand.PROPERTY_VALUE_CLEAN_POLICY,
                        ),
                    ),
                ),
            ),
        )
        teamcityClient.createBuildTypeVcsRootEntry(
            buildType = BuildTypeLocator(TEST_SUBPROJECT_1_BUILD_1),
            vcsRootEntry = TeamcityCreateVcsRootEntry(
                id = created.id,
                vcsRoot = TeamcityLinkVcsRoot(id = created.id),
                checkoutRules = "",
            ),
        )
        teamcityClient.setParameter(
            ConfigurationType.BUILD_TYPE,
            TEST_SUBPROJECT_1_BUILD_1,
            TeamcityReplaceVcsRootCommand.PROPERTY_BUILD_TYPE_BRANCH,
            "master",
        )

        val exitCode = execute(
            testInfo.testMethod.get().name,
            *getTeamcityOptions(config),
            TeamcityReplaceVcsRootCommand.COMMAND,
            "${TeamcityReplaceVcsRootCommand.OLD_VCS_ROOT}=$oldUrl",
            "${TeamcityReplaceVcsRootCommand.NEW_VCS_ROOT}=$newUrl",
            "${TeamcityReplaceVcsRootCommand.DRY_RUN}=false",
        )
        Assertions.assertEquals(0, exitCode)
        val actualUrl = teamcityClient.getVcsRootProperty(created.id, TeamcityReplaceVcsRootCommand.PROPERTY_URL)
        Assertions.assertEquals(newUrl, actualUrl, "VCS Root url property was not updated")

        val entries = teamcityClient.getBuildTypeVcsRootEntries(TEST_SUBPROJECT_1_BUILD_1).entries
        Assertions.assertEquals(1, entries.size)
        Assertions.assertNotEquals(created.id, entries.first().vcsRoot.id)
    }

    @ParameterizedTest
    @MethodSource("validCommands")
    fun testValidCommands(
        name: String,
        command: Array<String>,
    ) = Assertions.assertEquals(0, execute(name, *command))

    @ParameterizedTest
    @MethodSource("invalidCommands")
    fun testInvalidCommands(
        name: String,
        command: Array<String>,
    ) = Assertions.assertEquals(1, execute(name, *command))

    @BeforeEach
    fun init(testInfo: TestInfo) {
        this.testInfo = testInfo
    }

    private fun cleanUpResources(
        teamcityClient: TeamcityClassicClient,
        config: TeamcityTestConfiguration,
    ) {
        try {
            teamcityClient.deleteProject(TEST_PROJECT)
        } catch (e: Exception) {
            // do nothing
        }
        createTestUser(config.host, TEST_USER)
        createTestUser(config.host, TEST_USER_2)
        teamcityClient.createProject(
            TeamcityCreateProject(
                TEST_PROJECT,
                TEST_PROJECT,
                TeamcityLinkProject("RDDepartment"),
            ),
        )
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_PROJECT, "JDK_VERSION", "1.8")
        teamcityClient.setParameter(
            ConfigurationType.PROJECT,
            TEST_PROJECT,
            RESERVED_CHECKOUT_DIRECTORY_PARAMETER,
            RESERVED_CHECKOUT_DIRECTORY_VALUE,
        )
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_BUILD_1,
                TEST_BUILD_1,
                project = TeamcityLinkProject(TEST_PROJECT),
            ),
        )
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_BUILD_2,
                TEST_BUILD_2,
                project = TeamcityLinkProject(TEST_PROJECT),
            ),
        )
        teamcityClient.createProject(
            TeamcityCreateProject(
                TEST_SUBPROJECT_1,
                TEST_SUBPROJECT_1,
                TeamcityLinkProject(TEST_PROJECT),
            ),
        )
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_SUBPROJECT_1_BUILD_1,
                TEST_SUBPROJECT_1_BUILD_1,
                project = TeamcityLinkProject(TEST_SUBPROJECT_1),
            ),
        )
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_SUBPROJECT_1_BUILD_2,
                TEST_SUBPROJECT_1_BUILD_2,
                project = TeamcityLinkProject(TEST_SUBPROJECT_1),
            ),
        )
        teamcityClient.createProject(
            TeamcityCreateProject(
                TEST_SUBPROJECT_2,
                TEST_SUBPROJECT_2,
                TeamcityLinkProject(TEST_PROJECT),
            ),
        )
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_SUBPROJECT_2_BUILD_1,
                TEST_SUBPROJECT_2_BUILD_1,
                project = TeamcityLinkProject(TEST_SUBPROJECT_2),
            ),
        )
        val buildType = teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_SUBPROJECT_2_BUILD_2,
                TEST_SUBPROJECT_2_BUILD_2,
                project = TeamcityLinkProject(TEST_SUBPROJECT_2),
            ),
        )
        val properties = TeamcityProperties(
            listOf(
                TeamcityProperty("property-value", "Mac OS X"),
                TeamcityProperty("property-name", "teamcity.agent.jvm.os.name"),
            ),
        )

        teamcityClient.addAgentRequirementToBuildType(
            BuildTypeLocator(buildType.id),
            TeamcityAgentRequirement(
                null,
                "agentName",
                "equals",
                null,
                null,
                null,
                properties,
            ),
        )

        val templates = listOf(
            TeamcityCreateBuildChainCommand.TEMPLATE_GRADLE_COMPILE,
            TeamcityCreateBuildChainCommand.TEMPLATE_MAVEN_COMPILE,
            TeamcityCreateBuildChainCommand.TEMPLATE_RC,
            TeamcityCreateBuildChainCommand.TEMPLATE_CHECKLIST,
            TeamcityCreateBuildChainCommand.TEMPLATE_RELEASE,
        )
        templates.forEach {
            teamcityClient.createBuildType(
                TeamcityCreateBuildType(
                    it,
                    it,
                    project = TeamcityLinkProject(TEST_PROJECT),
                    templateFlag = true,
                ),
            )
        }

        val releaseBuildStepsName = listOf("IncrementTeamCityBuildConfigurationParameter", "Deploy to Share")
        releaseBuildStepsName.forEach { stepName ->
            teamcityClient.createBuildStep(
                TeamcityCreateBuildChainCommand.TEMPLATE_RELEASE,
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

    private fun validateSnapshotDependencyFailureAction(
        teamcityClient: TeamcityClassicClient,
        buildTypeId: String,
        expectedAction: DependencyFailureAction,
    ) {
        val snapshotDependencies = teamcityClient.getSnapshotDependencies(buildTypeId).snapshotDependencies
        Assertions.assertEquals(1, snapshotDependencies.size)
        val properties = snapshotDependencies[0].properties.properties.associate { it.name to it.value }
        Assertions.assertEquals(
            expectedAction.value,
            properties["run-build-if-dependency-failed"],
            "run-build-if-dependency-failed for $buildTypeId",
        )
        Assertions.assertEquals(
            expectedAction.value,
            properties["run-build-if-dependency-failed-to-start"],
            "run-build-if-dependency-failed-to-start for $buildTypeId",
        )
    }

    private fun createTestUser(
        host: String,
        username: String,
    ) {
        val response = HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder()
                .uri(URI("$host/app/rest/users"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(
                    "Authorization",
                    "Basic ${Base64.getEncoder().encodeToString("$TEAMCITY_USER:$TEAMCITY_PASSWORD".toByteArray())}",
                ).POST(HttpRequest.BodyPublishers.ofString("""{"username":"$username","password":"$username"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val status = response.statusCode()
        Assertions.assertTrue(
            status in 200..299 || status == 400,
            "Failed to create user '$username': HTTP $status - ${response.body()}",
        )
    }

    private data class RoleEntry(
        val roleId: String,
        val scope: String,
    )

    private fun getUserRoles(
        host: String,
        username: String,
    ): List<RoleEntry> {
        val response = HttpClient.newHttpClient().send(
            HttpRequest
                .newBuilder()
                .uri(URI("$host/app/rest/users/username:$username/roles"))
                .header("Accept", "application/json")
                .header(
                    "Authorization",
                    "Basic ${Base64.getEncoder().encodeToString("$TEAMCITY_USER:$TEAMCITY_PASSWORD".toByteArray())}",
                ).GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        Assertions.assertEquals(200, response.statusCode(), "Failed to get roles for user '$username'")
        val tree = ObjectMapper().readTree(response.body())
        return tree["role"]?.map { node ->
            RoleEntry(
                roleId = node["roleId"].asText(),
                scope = node["scope"].asText(),
            )
        } ?: emptyList()
    }

    private fun validateSingleChainVcsRoot(
        teamcityClient: TeamcityClassicClient,
        projectId: String,
        expectedUrl: String,
        buildTypeIds: List<String>,
    ) {
        val vcsRoots = teamcityClient.getVcsRoots(VcsRootLocator(project = ProjectLocator(id = projectId))).vcsRoots
        Assertions.assertEquals(1, vcsRoots.size, "VCS roots in $projectId")
        val vcsRoot = teamcityClient.getVcsRoot(vcsRoots.single().id)
        Assertions.assertEquals("${projectId}_VCS_ROOT", vcsRoot.name)
        Assertions.assertEquals("jetbrains.git", vcsRoot.vcsName)
        val properties = requireNotNull(vcsRoot.properties).properties.associate { it.name to it.value }
        Assertions.assertEquals(expectedUrl, properties["url"])
        Assertions.assertEquals("master", properties["branch"])
        Assertions.assertEquals("+:<default>", properties["teamcity:branchSpec"])
        buildTypeIds.forEach { buildTypeId ->
            val entry = teamcityClient.getBuildTypeVcsRootEntries(buildTypeId).entries.single()
            Assertions.assertEquals(vcsRoot.id, entry.vcsRoot.id, "VCS root of $buildTypeId")
            Assertions.assertTrue(entry.checkoutRules.isNullOrEmpty(), "checkout rules of $buildTypeId: '${entry.checkoutRules}'")
        }
    }

    private fun validateBuildTypeTemplate(
        teamcityClient: TeamcityClassicClient,
        buildTypeId: String,
        templateId: String,
    ) {
        val templateBuildType = teamcityClient.getBuildType(buildTypeId).templates?.buildTypes
        Assertions.assertEquals(1, templateBuildType?.size)
        Assertions.assertEquals(templateId, templateBuildType?.get(0)?.id)
    }

    private fun validateUploadedMetarunners(
        config: TeamcityTestConfiguration,
        client: TeamcityClient,
    ) {
        val expected = listOf("TestMetarunner", "TestMetarunner2", "TestMetarunner3")
        if (config.version >= 2026) {
            expected.forEach { recipeId ->
                Assertions.assertNotNull(
                    client.getRecipeOverviewV2026(recipeId, TEST_PROJECT),
                    "Recipe '$recipeId' not found in project $TEST_PROJECT",
                )
            }
        } else {
            val tabName = if (config.version < 2025) "metaRunner" else "recipe"
            val url = "${config.host}/admin/editProject.html?projectId=$TEST_PROJECT&tab=$tabName"
            htmlDocument(
                HttpClient
                    .newHttpClient()
                    .send(
                        HttpRequest
                            .newBuilder()
                            .uri(URI(url))
                            .header(
                                "Authorization",
                                "Basic ${Base64.getEncoder().encodeToString("$TEAMCITY_USER:$TEAMCITY_PASSWORD".toByteArray())}",
                            ).GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    ).body(),
            ) {
                expected.forEach { id ->
                    tr {
                        withAttribute = "data-id" to id
                        findAll { size toBe 1 }
                    }
                }
            }
        }
    }

    companion object {
        const val TEST_PROJECT = "TestTeamcityAutomation"
        const val TEST_BUILD_1 = "TestTeamcityAutomationBuild1"
        const val TEST_BUILD_2 = "TestTeamcityAutomationBuild2"
        const val TEST_SUBPROJECT_1 = "TestTeamcityAutomationSubproject1"
        const val TEST_SUBPROJECT_1_BUILD_1 = "TestTeamcityAutomationSubproject1Build1"
        const val TEST_SUBPROJECT_1_BUILD_2 = "TestTeamcityAutomationSubproject1Build2"
        const val TEST_SUBPROJECT_2 = "TestTeamcityAutomationSubproject2"
        const val TEST_SUBPROJECT_2_BUILD_1 = "TestTeamcityAutomationSubproject2Build1"
        const val TEST_SUBPROJECT_2_BUILD_2 = "TestTeamcityAutomationSubproject2Build2"

        const val HELP_OPTION = "-h"

        const val TEAMCITY_USER = "admin"
        const val TEAMCITY_PASSWORD = "admin"
        const val TEST_USER = "testuser"
        const val TEST_USER_2 = "testuser2"

        // Helper clone directory of the chain templates (ADR-001); a Checkout Directory equal to
        // this value is rejected by the generator before anything is created.
        const val RESERVED_CHECKOUT_DIRECTORY_PARAMETER = "RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR"
        const val RESERVED_CHECKOUT_DIRECTORY_VALUE = "release-notes-report-templates"

        private val hostTeamcity2022 = System.getProperty("test.teamcity-2022-host")
            ?: throw Exception("System property 'test.teamcity-2022-host' must be defined")
        private val hostTeamcity2026 = System.getProperty("test.teamcity-2026-host")
            ?: throw Exception("System property 'test.teamcity-2026-host' must be defined")
        private val hostComponentsRegistry = System.getProperty("test.components-registry-host")
            ?: throw Exception("System property 'test.components-registry-host' must be defined")

        private fun createClient(config: TeamcityTestConfiguration): TeamcityClassicClient =
            TeamcityClassicClient(object : ClientParametersProvider {
                override fun getApiUrl() = config.host

                override fun getAuth() = StandardBasicCredCredentialProvider(TEAMCITY_USER, TEAMCITY_PASSWORD)
            })

        private fun getTeamcityOptions(config: TeamcityTestConfiguration) =
            arrayOf(
                "${TeamcityCommand.URL_OPTION}=${config.host}",
                "${TeamcityCommand.USER_OPTION}=$TEAMCITY_USER",
                "${TeamcityCommand.PASSWORD_OPTION}=$TEAMCITY_PASSWORD",
            )

        @JvmStatic
        fun teamcityConfigurations(): List<TeamcityTestConfiguration> =
            listOf(
                TeamcityTestConfiguration(
                    name = "v22",
                    host = "http://$hostTeamcity2022",
                    version = 2022,
                ),
                TeamcityTestConfiguration(
                    name = "v26",
                    host = "http://$hostTeamcity2026",
                    version = 2026,
                ),
            )

        @JvmStatic
        fun teamcityContexts(): List<TeamcityTestConfiguration> =
            teamcityConfigurations().map { TeamcityTestConfiguration(it.name, it.host, it.version) }

        // <editor-fold defaultstate="collapsed" desc="Test Data">
        @JvmStatic
        fun validCommands(): Stream<Arguments> =
            teamcityConfigurations()
                .flatMap { config ->
                    listOf(
                        "validCommand" to arrayOf(HELP_OPTION),
                        "validCommand2" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityUpdateParameterCommand.COMMAND,
                            HELP_OPTION,
                        ),
                        "validCommand3" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityUpdateParameterCommand.COMMAND,
                            "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                            "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=test",
                            TeamcityUpdateParameterSetCommand.COMMAND,
                            HELP_OPTION,
                        ),
                        "validCommand4" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityUpdateParameterCommand.COMMAND,
                            "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                            "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}=test",
                            "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=",
                            TeamcityUpdateParameterSetCommand.COMMAND,
                            HELP_OPTION,
                        ),
                        "validCommand5" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityUploadMetarunnersCommand.COMMAND,
                            HELP_OPTION,
                        ),
                        "validCommand6" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityPostGithubStatusCommand.COMMAND,
                            HELP_OPTION,
                        ),
                    ).map { (name, args) -> Arguments.of(name, args) }
                }.stream()

        @JvmStatic
        private fun invalidCommands(): Stream<Arguments> =
            teamcityConfigurations()
                .flatMap { config ->
                    listOf(
                        "invalidCommand" to arrayOf(TeamcityUpdateParameterCommand.COMMAND, HELP_OPTION),
                        "invalidCommand2" to arrayOf(
                            *(getTeamcityOptions(config).clone().also { it[2] = "${TeamcityCommand.PASSWORD_OPTION}=" }),
                            TeamcityUpdateParameterCommand.COMMAND,
                            HELP_OPTION,
                        ),
                        "invalidCommand3" to arrayOf(
                            *(getTeamcityOptions(config).clone().also { it[2] = "${TeamcityCommand.PASSWORD_OPTION}=invalid" }),
                            TeamcityUpdateParameterCommand.COMMAND,
                            HELP_OPTION,
                        ),
                        "invalidCommand4" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityUpdateParameterCommand.COMMAND,
                            "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                            "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}= , ",
                            TeamcityUpdateParameterSetCommand.COMMAND,
                            HELP_OPTION,
                        ),
                        "invalidCommand5" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityUpdateParameterCommand.COMMAND,
                            "${TeamcityUpdateParameterCommand.NAME_OPTION}= ",
                            "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}=test",
                            TeamcityUpdateParameterSetCommand.COMMAND,
                            HELP_OPTION,
                        ),
                        "invalidCommand6" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityUpdateParameterCommand.COMMAND,
                            "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                            TeamcityUpdateParameterSetCommand.COMMAND,
                            HELP_OPTION,
                        ),
                        "invalidCommand7" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityUpdateParameterCommand.COMMAND,
                            TeamcityUpdateParameterSetCommand.COMMAND,
                            HELP_OPTION,
                        ),
                        "invalidCommand8" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityUploadMetarunnersCommand.COMMAND,
                            "${TeamcityUploadMetarunnersCommand.PROJECT_ID_OPTION}= ",
                            "${TeamcityUploadMetarunnersCommand.ZIP_OPTION}=file:///test.zip",
                        ),
                        "invalidCommand9" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityUploadMetarunnersCommand.COMMAND,
                            "${TeamcityUploadMetarunnersCommand.PROJECT_ID_OPTION}=test",
                            "${TeamcityUploadMetarunnersCommand.ZIP_OPTION}=invalid",
                        ),
                        "invalidCommand10" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityPostGithubStatusCommand.COMMAND,
                            "${TeamcityPostGithubStatusCommand.OWNER}=owner",
                            "${TeamcityPostGithubStatusCommand.REPO}=repo",
                            "${TeamcityPostGithubStatusCommand.COMMIT}=0000000000000000000000000000000000000000",
                            "${TeamcityPostGithubStatusCommand.TOKEN}=token",
                            "${TeamcityPostGithubStatusCommand.STATE}=invalid",
                        ),
                        "invalidCommand11" to arrayOf(
                            *getTeamcityOptions(config),
                            TeamcityPostGithubStatusCommand.COMMAND,
                            "${TeamcityPostGithubStatusCommand.OWNER}= ",
                            "${TeamcityPostGithubStatusCommand.REPO}=repo",
                            "${TeamcityPostGithubStatusCommand.COMMIT}=0000000000000000000000000000000000000000",
                            "${TeamcityPostGithubStatusCommand.TOKEN}=token",
                            "${TeamcityPostGithubStatusCommand.STATE}=success",
                        ),
                    ).map { (name, args) -> Arguments.of(name, args) }
                }.stream()
        // </editor-fold>
    }
}
