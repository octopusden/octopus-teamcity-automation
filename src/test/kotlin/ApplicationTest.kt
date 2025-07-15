import it.skrape.core.htmlDocument
import it.skrape.matchers.toBe
import it.skrape.selects.html5.tr
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.automation.teamcity.TeamcityCommand
import org.octopusden.octopus.automation.teamcity.TeamcityCreateBuildChainCommand
import org.octopusden.octopus.automation.teamcity.TeamcityGetBuildTypesAgentRequirementsCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterIncrementCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterSetCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUploadMetarunnersCommand
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.createBuildStep
import org.octopusden.octopus.infrastructure.teamcity.client.deleteProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityAgentRequirement
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildSteps
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildTypes
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildTypeVcsRootEntries
import org.octopusden.octopus.infrastructure.teamcity.client.getProject
import org.octopusden.octopus.infrastructure.teamcity.client.getSnapshotDependencies
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityStep
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.BuildTypeLocator


class ApplicationTest {
    private val jar = System.getProperty("jar") ?: throw IllegalStateException("System property 'jar' must be provided")
    private lateinit var testInfo: TestInfo

    private fun execute(name: String, vararg command: String) =
        ProcessBuilder("java", "-jar", jar, *command).redirectErrorStream(true).redirectOutput(
            File("").resolve("build").resolve("logs").resolve("$name.log").also { it.parentFile.mkdirs() }).start()
            .waitFor()

    private fun executeForCreateBuildChainCommand(
        config: TeamcityTestConfiguration,
        testMethodName: String,
        componentName: String,
        minorVersion: String? = "1.0",
        createChecklist: Boolean = true,
        createRcForce: Boolean = false
    ): Int =
        execute(
            testMethodName,
            *getTeamcityOptions(config),
            TeamcityCreateBuildChainCommand.COMMAND,
            "${TeamcityCreateBuildChainCommand.PARENT}=$TEST_PROJECT",
            "${TeamcityCreateBuildChainCommand.COMPONENT}=$componentName",
            "${TeamcityCreateBuildChainCommand.VERSION}=$minorVersion",
            "${TeamcityCreateBuildChainCommand.CR}=$COMPONENTS_REGISTRY_SERVICE_URL",
            "${TeamcityCreateBuildChainCommand.CREATE_CHECKLIST}=$createChecklist",
            "${TeamcityCreateBuildChainCommand.CREATE_RC_FORCE}=$createRcForce",
        )

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityUpdateParameterSet(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient)

        val parameter = "TEST_PARAMETER"
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter, "OLD")
        Assertions.assertEquals(
            0, execute(
                testInfo.testMethod.get().name,
                *getTeamcityOptions(config),
                TeamcityUpdateParameterCommand.COMMAND,
                "${TeamcityUpdateParameterCommand.NAME_OPTION}=$parameter",
                "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}=$TEST_SUBPROJECT_2;$TEST_PROJECT",
                "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=$TEST_BUILD_1,$TEST_SUBPROJECT_1_BUILD_1",
                TeamcityUpdateParameterSetCommand.COMMAND,
                "${TeamcityUpdateParameterSetCommand.VALUE_OPTION}=NEW"
            )
        )
        Assertions.assertEquals(
            "NEW", teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_PROJECT, parameter)
        )
        Assertions.assertEquals(
            "NEW", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter)
        )
        Assertions.assertEquals(
            "OLD", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter)
        )
        Assertions.assertEquals(
            "OLD", teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter)
        )
        Assertions.assertEquals(
            "NEW", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_1, parameter)
        )
        Assertions.assertEquals(
            "OLD", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_2, parameter)
        )
        Assertions.assertEquals(
            "NEW", teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_2, parameter)
        )
        Assertions.assertEquals(
            "OLD", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter)
        )
        Assertions.assertEquals(
            "NEW", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_2, parameter)
        )
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityUpdateParameterIncrement(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient)

        val parameter = "TEST_PARAMETER"
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter, "1.0")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter, "1.1")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter, "1.2")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_2, parameter, "INVALID")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter, "1.3")
        Assertions.assertEquals(
            0, execute(
                testInfo.testMethod.get().name,
                *getTeamcityOptions(config),
                TeamcityUpdateParameterCommand.COMMAND,
                "${TeamcityUpdateParameterCommand.NAME_OPTION}=$parameter",
                "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}=$TEST_SUBPROJECT_2,$TEST_PROJECT",
                "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=$TEST_BUILD_1;$TEST_SUBPROJECT_1_BUILD_1;$TEST_SUBPROJECT_1_BUILD_1",
                TeamcityUpdateParameterIncrementCommand.COMMAND
            )
        )
        Assertions.assertThrows(feign.FeignException.NotFound::class.java) {
            teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_PROJECT, parameter)
        }
        Assertions.assertEquals(
            "1.1", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter)
        )
        Assertions.assertEquals(
            "1.1", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter)
        )
        Assertions.assertEquals(
            "1.2", teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter)
        )
        Assertions.assertEquals(
            "1.3", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_1, parameter)
        )
        Assertions.assertEquals(
            "1.2", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_2, parameter)
        )
        Assertions.assertEquals(
            "INVALID", teamcityClient.getParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_2, parameter)
        )
        Assertions.assertEquals(
            "1.3", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter)
        )
        Assertions.assertEquals(
            "INVALID", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_2, parameter)
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
        cleanUpResources(teamcityClient)

        val minorVersion = "1.0"
        val componentName = "ee-component"

        Assertions.assertEquals(
            0, executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName, minorVersion)
        )

        val projectId = "TestTeamcityAutomation_EeComponent"
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
            releaseConfigId to rcConfigId
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

        val buildSteps = teamcityClient.getBuildSteps(releaseConfigId).steps
        Assertions.assertEquals(2, buildSteps.size)
        Assertions.assertTrue(buildSteps.find { it.name == "IncrementTeamCityBuildConfigurationParameter" }?.disabled!!)

        val compileBuildVersion = "%dep.$compileConfigId.BUILD_VERSION%"
        Assertions.assertEquals(compileBuildVersion, teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, rcConfigId, "BUILD_VERSION"))
        Assertions.assertEquals(compileBuildVersion, teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, checklistConfigId, "BUILD_VERSION"))
        Assertions.assertEquals(compileBuildVersion, teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BUILD_VERSION"))

        Assertions.assertEquals(compileConfigId, teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BASE_CONFIGURATION_ID"))

        Assertions.assertEquals(componentName, teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "COMPONENT_NAME"))
        Assertions.assertEquals(minorVersion, teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "PROJECT_VERSION"))
        teamcityClient.deleteProject(projectId)
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainForEEComponentWithoutCheckList(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient)

        val minorVersion = "1.0"
        val componentName = "ee-component"

        Assertions.assertEquals(
            0, executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName, minorVersion, false)
        )

        val projectId = "TestTeamcityAutomation_EeComponent"
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
            releaseConfigId to rcConfigId
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

        val buildSteps = teamcityClient.getBuildSteps(releaseConfigId).steps
        Assertions.assertEquals(2, buildSteps.size)
        Assertions.assertTrue(buildSteps.find { it.name == "IncrementTeamCityBuildConfigurationParameter" }?.disabled!!)

        val compileBuildVersion = "%dep.$compileConfigId.BUILD_VERSION%"
        Assertions.assertEquals(
            compileBuildVersion,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, rcConfigId, "BUILD_VERSION")
        )
        Assertions.assertEquals(
            compileBuildVersion,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BUILD_VERSION")
        )

        Assertions.assertEquals(
            compileConfigId,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BASE_CONFIGURATION_ID")
        )

        Assertions.assertEquals(
            componentName,
            teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "COMPONENT_NAME")
        )
        Assertions.assertEquals(
            minorVersion,
            teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "PROJECT_VERSION")
        )
        teamcityClient.deleteProject(projectId)
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
        cleanUpResources(teamcityClient)

        val minorVersion = "1.0"
        val componentNamesToProjectId = mapOf(
            "ie-component" to "TestTeamcityAutomation_IeComponent",
            "ei-component" to "TestTeamcityAutomation_EiComponent",
            "ii-component" to "TestTeamcityAutomation_IiComponent"
        )

        componentNamesToProjectId.forEach { (componentName, projectId) ->
            Assertions.assertEquals(
                0, executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName, minorVersion)
            )

            Assertions.assertEquals(TEST_PROJECT, teamcityClient.getProject(projectId).parentProjectId)
            val buildTypes = teamcityClient.getBuildTypes(projectId).buildTypes
            Assertions.assertEquals(2, buildTypes.size)

            val compileConfigId = "${projectId}_10CompileUtAuto"
            val releaseConfigId = "${projectId}_20ReleaseManual"

            validateBuildTypeTemplate(teamcityClient, releaseConfigId, TeamcityCreateBuildChainCommand.TEMPLATE_RELEASE)

            val buildTypesIdAndDependencyId = mapOf(
                compileConfigId to null,
                releaseConfigId to compileConfigId
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

            val buildSteps = teamcityClient.getBuildSteps(releaseConfigId).steps
            Assertions.assertEquals(2, buildSteps.size)
            Assertions.assertTrue(buildSteps.find { it.name == "IncrementTeamCityBuildConfigurationParameter" }?.disabled!!)

            val compileBuildVersion = "%dep.$compileConfigId.BUILD_VERSION%"
            Assertions.assertEquals(compileBuildVersion, teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BUILD_VERSION"))

            Assertions.assertEquals(compileConfigId, teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BASE_CONFIGURATION_ID"))

            Assertions.assertEquals(componentName, teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "COMPONENT_NAME"))
            Assertions.assertEquals(minorVersion, teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "PROJECT_VERSION"))
            teamcityClient.deleteProject(projectId)
        }
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainForIEComponentWithRc(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient)

        val minorVersion = "1.0"
        val projectId = "TestTeamcityAutomation_IeComponent"
        val componentName = "ie-component"

        Assertions.assertEquals(
            0,
            executeForCreateBuildChainCommand(config,
                testInfo.methodName(),
                componentName,
                minorVersion,
                createChecklist = false,
                createRcForce = true
            )
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
            releaseConfigId to rcConfigId
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

        val buildSteps = teamcityClient.getBuildSteps(releaseConfigId).steps
        Assertions.assertEquals(2, buildSteps.size)
        Assertions.assertTrue(buildSteps.find { it.name == "IncrementTeamCityBuildConfigurationParameter" }?.disabled!!)

        val compileBuildVersion = "%dep.$compileConfigId.BUILD_VERSION%"
        Assertions.assertEquals(
            compileBuildVersion,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BUILD_VERSION")
        )

        Assertions.assertEquals(
            compileConfigId,
            teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, releaseConfigId, "BASE_CONFIGURATION_ID")
        )

        Assertions.assertEquals(
            componentName,
            teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "COMPONENT_NAME")
        )
        Assertions.assertEquals(
            minorVersion,
            teamcityClient.getParameter(ConfigurationType.PROJECT, projectId, "PROJECT_VERSION")
        )
        teamcityClient.deleteProject(projectId)
    }

    /**
     * Tests CreateTeamCityBuildChain for overriding the JDK_VERSION parameter in compile build configurations based on the component's javaVersion.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainForJDKVersion(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient)

        val defaultJDKComponentName = "default-jdk-component"
        val defaultJDKProjectId = "TestTeamcityAutomation_DefaultJdkComponent"
        val customJDKComponentName = "custom-jdk-component"
        val customJDKProjectId = "TestTeamcityAutomation_CustomJdkComponent"

        val componentNamesToProjectId = mapOf(
            defaultJDKComponentName to defaultJDKProjectId,
            customJDKComponentName to customJDKProjectId
        )

        componentNamesToProjectId.forEach { (componentName, projectId) ->
            Assertions.assertEquals(
                0, executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName)
            )
            val nonCompileConfigIds = listOf(
                "${projectId}_20ReleaseCandidateManual",
                "${projectId}_30ReleaseChecklistValidationManual",
                "${projectId}_40ReleaseManual"
            )
            nonCompileConfigIds.forEach { configId ->
                Assertions.assertEquals("1.8", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, configId, "JDK_VERSION"))
            }
        }

        Assertions.assertEquals(
            "1.8", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, "${defaultJDKProjectId}_10CompileUtAuto", "JDK_VERSION")
        )
        Assertions.assertEquals(
            "11", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, "${customJDKProjectId}_10CompileUtAuto", "JDK_VERSION")
        )
    }

    /**
     * Tests CreateTeamCityBuildChain to verify the template used in compile build configurations aligns with the component's build system.
     */
    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityCreateBuildChainForCompileTemplate(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient)

        val componentNames = listOf("maven-component", "gradle-component", "provided-component")

        componentNames.forEach { componentName ->
            Assertions.assertEquals(
                0, executeForCreateBuildChainCommand(config, testInfo.methodName(), componentName)
            )
        }

        validateBuildTypeTemplate(teamcityClient, "TestTeamcityAutomation_MavenComponent_10CompileUtAuto", TeamcityCreateBuildChainCommand.TEMPLATE_MAVEN_COMPILE)
        validateBuildTypeTemplate(teamcityClient, "TestTeamcityAutomation_GradleComponent_10CompileUtAuto", TeamcityCreateBuildChainCommand.TEMPLATE_GRADLE_COMPILE)
        validateBuildTypeTemplate(teamcityClient, "TestTeamcityAutomation_ProvidedComponent_10CompileUtAuto", TeamcityCreateBuildChainCommand.TEMPLATE_GRADLE_COMPILE)

        Assertions.assertEquals(
            1, executeForCreateBuildChainCommand(config, testInfo.methodName(), "not-supported-component")
        )
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityUpdateParameterIncrementCurrent(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient)

        val parameter = "TEST_PARAMETER"
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter, "1.0")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter, "1.0.1")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_1, parameter, "1.1")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_2, parameter, "1.1.1")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter, "1.1.2")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_2, parameter, "1.2.1")
        Assertions.assertEquals(
            0, execute(
                testInfo.testMethod.get().name,
                *getTeamcityOptions(config),
                TeamcityUpdateParameterCommand.COMMAND,
                "${TeamcityUpdateParameterCommand.NAME_OPTION}=$parameter",
                "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=$TEST_BUILD_1,$TEST_BUILD_2;$TEST_SUBPROJECT_1_BUILD_1,$TEST_SUBPROJECT_1_BUILD_2;$TEST_SUBPROJECT_2_BUILD_1,$TEST_SUBPROJECT_2_BUILD_2",
                TeamcityUpdateParameterIncrementCommand.COMMAND,
                "${TeamcityUpdateParameterIncrementCommand.CURRENT_OPTION}=1.1.1"
            )
        )
        Assertions.assertEquals(
            "1.0", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter)
        )
        Assertions.assertEquals(
            "1.0.1", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter)
        )
        Assertions.assertEquals(
            "1.2", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_1, parameter)
        )
        Assertions.assertEquals(
            "1.1.2", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_1_BUILD_2, parameter)
        )
        Assertions.assertEquals(
            "1.1.2", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter)
        )
        Assertions.assertEquals(
            "1.2.1", teamcityClient.getParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_2, parameter)
        )
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testTeamCityUploadMetarunners(config: TeamcityTestConfiguration) {
        val teamcityClient = createClient(config)
        cleanUpResources(teamcityClient)

        val metarunners = ApplicationTest::class.java.getResource("metarunners.zip")!!
        Assertions.assertEquals(
            0, execute(
                testInfo.testMethod.get().name,
                *getTeamcityOptions(config),
                TeamcityUploadMetarunnersCommand.COMMAND,
                "${TeamcityUploadMetarunnersCommand.PROJECT_ID_OPTION}=$TEST_PROJECT",
                "${TeamcityUploadMetarunnersCommand.ZIP_OPTION}=$metarunners"
            )
        )

        val tabName = if (config.version < 2025) "metaRunner" else "recipe"
        validateUploadedMetarunners("${config.host}/admin/editProject.html?projectId=$TEST_PROJECT&tab=$tabName")
    }

    @ParameterizedTest
    @MethodSource("teamcityContexts")
    fun testGetBuildTypesAgentRequirements(config: TeamcityTestConfiguration) {
        val file = File("build").resolve("logs").resolve("${testInfo.testMethod.get().name}.csv")
        Assertions.assertEquals(
            0, execute(
                testInfo.testMethod.get().name,
                *getTeamcityOptions(config),
                TeamcityGetBuildTypesAgentRequirementsCommand.COMMAND,
                "${TeamcityGetBuildTypesAgentRequirementsCommand.FILE}=$file"
            )
        )
        Assertions.assertTrue(file.exists())
        Assertions.assertTrue(file.readText().contains("teamcity.agent.jvm.os.name;Mac OS X"))
        file.delete()
    }

    @ParameterizedTest
    @MethodSource("validCommands")
    fun testValidCommands(name: String, command: Array<String>) = Assertions.assertEquals(0, execute(name, *command))

    @ParameterizedTest
    @MethodSource("invalidCommands")
    fun testInvalidCommands(name: String, command: Array<String>) = Assertions.assertEquals(1, execute(name, *command))

    @BeforeEach
    fun init(testInfo: TestInfo) {
        this.testInfo = testInfo
    }

    private fun cleanUpResources(teamcityClient: TeamcityClassicClient) {
        try {
            teamcityClient.deleteProject(TEST_PROJECT)
        } catch (e: Exception) {
            //do nothing
        }
        teamcityClient.createProject(
            TeamcityCreateProject(
                TEST_PROJECT, TEST_PROJECT, TeamcityLinkProject("RDDepartment")
            )
        )
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_PROJECT, "JDK_VERSION", "1.8")
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_BUILD_1, TEST_BUILD_1, project = TeamcityLinkProject(TEST_PROJECT)
            )
        )
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_BUILD_2, TEST_BUILD_2, project = TeamcityLinkProject(TEST_PROJECT)
            )
        )
        teamcityClient.createProject(
            TeamcityCreateProject(
                TEST_SUBPROJECT_1, TEST_SUBPROJECT_1, TeamcityLinkProject(TEST_PROJECT)
            )
        )
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_SUBPROJECT_1_BUILD_1, TEST_SUBPROJECT_1_BUILD_1, project = TeamcityLinkProject(TEST_SUBPROJECT_1)
            )
        )
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_SUBPROJECT_1_BUILD_2, TEST_SUBPROJECT_1_BUILD_2, project = TeamcityLinkProject(TEST_SUBPROJECT_1)
            )
        )
        teamcityClient.createProject(
            TeamcityCreateProject(
                TEST_SUBPROJECT_2, TEST_SUBPROJECT_2, TeamcityLinkProject(TEST_PROJECT)
            )
        )
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_SUBPROJECT_2_BUILD_1, TEST_SUBPROJECT_2_BUILD_1, project = TeamcityLinkProject(TEST_SUBPROJECT_2)
            )
        )
        val buildType = teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_SUBPROJECT_2_BUILD_2, TEST_SUBPROJECT_2_BUILD_2, project = TeamcityLinkProject(TEST_SUBPROJECT_2)
            )
        )
        val properties = TeamcityProperties(
            listOf(
                TeamcityProperty("property-value", "Mac OS X"),
                TeamcityProperty("property-name", "teamcity.agent.jvm.os.name"),
            )
        )

        teamcityClient.addAgentRequirementToBuildType(
            BuildTypeLocator(buildType.id),
            TeamcityAgentRequirement(null, "agentName", "equals", null, null, null,
                properties
                )
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
                    it, it,
                    project = TeamcityLinkProject(TEST_PROJECT),
                    templateFlag = true
                )
            )
        }

        val releaseBuildStepsName = listOf("IncrementTeamCityBuildConfigurationParameter", "Deploy to Share")
        releaseBuildStepsName.forEach { stepName ->
            teamcityClient.createBuildStep(
                TeamcityCreateBuildChainCommand.TEMPLATE_RELEASE,
                step = TeamcityStep(
                    stepName, stepName, stepName,
                    disabled = false,
                    properties = TeamcityProperties(listOf(TeamcityProperty("property", "")))
                )
            )
        }
    }

    private fun validateBuildTypeTemplate(teamcityClient: TeamcityClassicClient, buildTypeId: String, templateId: String) {
        val templateBuildType = teamcityClient.getBuildType(buildTypeId).templates?.buildTypes
        Assertions.assertEquals(1, templateBuildType?.size)
        Assertions.assertEquals(templateId, templateBuildType?.get(0)?.id)
    }

    private fun validateUploadedMetarunners(url: String) {
        htmlDocument(
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(URI(url))
                    .header(
                        "Authorization",
                        "Basic ${Base64.getEncoder().encodeToString("$TEAMCITY_USER:$TEAMCITY_PASSWORD".toByteArray())}"
                    )
                    .method("GET", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            ).body()
        ) {
            tr {
                withAttribute = "data-id" to "TestMetarunner"
                findAll { size toBe 1 }
            }
            tr {
                withAttribute = "data-id" to "TestMetarunner2"
                findAll { size toBe 1 }
            }
            tr {
                withAttribute = "data-id" to "TestMetarunner3"
                findAll { size toBe 1 }
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

        const val COMPONENTS_REGISTRY_SERVICE_URL = "http://localhost:4567"

        private fun createClient(config: TeamcityTestConfiguration): TeamcityClassicClient {
            return TeamcityClassicClient(object : ClientParametersProvider {
                override fun getApiUrl() = config.host
                override fun getAuth() = StandardBasicCredCredentialProvider(TEAMCITY_USER, TEAMCITY_PASSWORD)
            })
        }

        private fun getTeamcityOptions(config: TeamcityTestConfiguration) = arrayOf(
            "${TeamcityCommand.URL_OPTION}=${config.host}",
            "${TeamcityCommand.USER_OPTION}=$TEAMCITY_USER",
            "${TeamcityCommand.PASSWORD_OPTION}=$TEAMCITY_PASSWORD"
        )

        @JvmStatic
        fun teamcityConfigurations(): List<TeamcityTestConfiguration> = listOf(
            TeamcityTestConfiguration(
                name = "v22",
                host = "http://localhost:8111",
                version = 2022
            ),
            TeamcityTestConfiguration(
                name = "v25",
                host = "http://localhost:8112",
                version = 2025
            )
        )

        @JvmStatic
        fun teamcityContexts(): List<TeamcityTestConfiguration> =
            teamcityConfigurations().map { TeamcityTestConfiguration(it.name, it.host, it.version) }

        //<editor-fold defaultstate="collapsed" desc="Test Data">
        @JvmStatic
        fun validCommands(): Stream<Arguments> {
            return teamcityConfigurations().flatMap { config ->
                listOf(
                    "validCommand" to arrayOf(HELP_OPTION),
                    "validCommand2" to arrayOf(
                        *getTeamcityOptions(config),
                        TeamcityUpdateParameterCommand.COMMAND,
                        HELP_OPTION
                    ),
                    "validCommand3" to arrayOf(
                        *getTeamcityOptions(config),
                        TeamcityUpdateParameterCommand.COMMAND,
                        "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                        "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=test",
                        TeamcityUpdateParameterSetCommand.COMMAND,
                        HELP_OPTION
                    ),
                    "validCommand4" to arrayOf(
                        *getTeamcityOptions(config),
                        TeamcityUpdateParameterCommand.COMMAND,
                        "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                        "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}=test",
                        "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=",
                        TeamcityUpdateParameterSetCommand.COMMAND,
                        HELP_OPTION
                    ),
                    "validCommand5" to arrayOf(
                        *getTeamcityOptions(config),
                        TeamcityUploadMetarunnersCommand.COMMAND,
                        HELP_OPTION
                    )
                ).map { (name, args) -> Arguments.of(name, args) }
            }.stream()
        }

        @JvmStatic
        private fun invalidCommands(): Stream<Arguments> {
            return teamcityConfigurations().flatMap { config ->
                listOf(
                    "invalidCommand" to arrayOf(TeamcityUpdateParameterCommand.COMMAND, HELP_OPTION),
                    "invalidCommand2" to arrayOf(
                        *(getTeamcityOptions(config).clone().also { it[2] = "${TeamcityCommand.PASSWORD_OPTION}=" }),
                        TeamcityUpdateParameterCommand.COMMAND,
                        HELP_OPTION
                    ),
                    "invalidCommand3" to arrayOf(
                        *(getTeamcityOptions(config).clone().also { it[2] = "${TeamcityCommand.PASSWORD_OPTION}=invalid" }),
                        TeamcityUpdateParameterCommand.COMMAND,
                        HELP_OPTION
                    ),
                    "invalidCommand4" to arrayOf(
                        *getTeamcityOptions(config),
                        TeamcityUpdateParameterCommand.COMMAND,
                        "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                        "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}= , ",
                        TeamcityUpdateParameterSetCommand.COMMAND,
                        HELP_OPTION
                    ),
                    "invalidCommand5" to arrayOf(
                        *getTeamcityOptions(config),
                        TeamcityUpdateParameterCommand.COMMAND,
                        "${TeamcityUpdateParameterCommand.NAME_OPTION}= ",
                        "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}=test",
                        TeamcityUpdateParameterSetCommand.COMMAND,
                        HELP_OPTION
                    ),
                    "invalidCommand6" to arrayOf(
                        *getTeamcityOptions(config),
                        TeamcityUpdateParameterCommand.COMMAND,
                        "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                        TeamcityUpdateParameterSetCommand.COMMAND,
                        HELP_OPTION
                    ),
                    "invalidCommand7" to arrayOf(
                        *getTeamcityOptions(config),
                        TeamcityUpdateParameterCommand.COMMAND,
                        TeamcityUpdateParameterSetCommand.COMMAND,
                        HELP_OPTION
                    ),
                    "invalidCommand8" to arrayOf(
                        *getTeamcityOptions(config),
                        TeamcityUploadMetarunnersCommand.COMMAND,
                        "${TeamcityUploadMetarunnersCommand.PROJECT_ID_OPTION}= ",
                        "${TeamcityUploadMetarunnersCommand.ZIP_OPTION}=file:///test.zip"
                    ),
                    "invalidCommand9" to arrayOf(
                        *getTeamcityOptions(config),
                        TeamcityUploadMetarunnersCommand.COMMAND,
                        "${TeamcityUploadMetarunnersCommand.PROJECT_ID_OPTION}=test",
                        "${TeamcityUploadMetarunnersCommand.ZIP_OPTION}=invalid"
                    )
                ).map { (name, args) -> Arguments.of(name, args) }
            }.stream()
        }
        //</editor-fold>
    }
}