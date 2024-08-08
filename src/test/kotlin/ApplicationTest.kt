import java.io.File
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkProject

class ApplicationTest { //TODO: use parametrized tests
    private val jar = System.getProperty("jar") ?: throw IllegalStateException("System property 'jar' must be provided")

    private fun execute(testInfo: TestInfo, vararg command: String) =
        ProcessBuilder("java", "-jar", jar, *command)
            .redirectErrorStream(true)
            .redirectOutput(File("").resolve("build").resolve("logs")
                .resolve("${testInfo.testMethod.get().name}.log").also { it.parentFile.mkdirs() })
            .start()
            .waitFor()

    @Test
    fun testTeamCityHelp(testInfo: TestInfo) = Assertions.assertEquals(0, execute(testInfo, "-h"))

    @Test
    fun testTeamCityUpdateParameterHelp(testInfo: TestInfo) = Assertions.assertEquals(
        0, execute(testInfo, *TEAMCITY_OPTIONS, "update-parameter", "-h")
    )

    @Test
    fun testTeamCityUpdateParameterHelp2(testInfo: TestInfo) = Assertions.assertEquals(
        1, execute(testInfo, "update-parameter", "-h")
    )

    @Test
    fun testTeamCityUpdateParameterHelp3(testInfo: TestInfo) = Assertions.assertEquals(
        1,
        execute(testInfo, *(TEAMCITY_OPTIONS.clone().also { it[2] = "--password=invalid" }), "update-parameter", "-h")
    )

    @Test
    fun testTeamCityUpdateParameterSetHelp(testInfo: TestInfo) = Assertions.assertEquals(
        0,
        execute(
            testInfo,
            *TEAMCITY_OPTIONS,
            "update-parameter",
            "--name=test",
            "--build-type-ids=test",
            "set",
            "-h"
        )
    )

    @Test
    fun testTeamCityUpdateParameterSetHelp2(testInfo: TestInfo) = Assertions.assertEquals(
        0,
        execute(
            testInfo,
            *TEAMCITY_OPTIONS,
            "update-parameter",
            "--name=test",
            "--project-ids=test",
            "set",
            "-h"
        )
    )

    @Test
    fun testTeamCityUpdateParameterSetHelp3(testInfo: TestInfo) = Assertions.assertEquals(
        1,
        execute(
            testInfo,
            *TEAMCITY_OPTIONS,
            "update-parameter",
            "--name=test",
            "set",
            "-h"
        )
    )

    @Test
    fun testTeamCityUpdateParameterSet(testInfo: TestInfo) {
        val parameter = "TEST_PARAMETER"
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter, "OLD")
        Assertions.assertEquals(
            0,
            execute(
                testInfo,
                *TEAMCITY_OPTIONS,
                "update-parameter",
                "--name=$parameter",
                "--project-ids=$TEST_SUBPROJECT_2;$TEST_PROJECT",
                "--build-type-ids=$TEST_BUILD_1,$TEST_SUBPROJECT_1_BUILD_1",
                "set",
                "--value=NEW"
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

    @Test
    fun testTeamCityUpdateParameterIncrement(testInfo: TestInfo) {
        val parameter = "TEST_PARAMETER"
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter, "1.0")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter, "1.1")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter, "1.2")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_2, parameter, "INVALID")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter, "1.3")
        Assertions.assertEquals(
            0,
            execute(
                testInfo,
                *TEAMCITY_OPTIONS,
                "update-parameter",
                "--name=$parameter",
                "--project-ids=$TEST_SUBPROJECT_2,$TEST_PROJECT",
                "--build-type-ids=$TEST_BUILD_1;$TEST_SUBPROJECT_1_BUILD_1;$TEST_SUBPROJECT_1_BUILD_1",
                "increment"
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

    @Test
    fun testTeamCityUpdateParameterIncrementCurrent(testInfo: TestInfo) {
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
                testInfo,
                *TEAMCITY_OPTIONS,
                "update-parameter",
                "--name=$parameter",
                "--build-type-ids=$TEST_BUILD_1,$TEST_BUILD_2;$TEST_SUBPROJECT_1_BUILD_1,$TEST_SUBPROJECT_1_BUILD_2;$TEST_SUBPROJECT_2_BUILD_1,$TEST_SUBPROJECT_2_BUILD_2",
                "increment",
                "--current=1.1.1"
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

    @BeforeEach
    fun beforeEach() {
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
                TEST_SUBPROJECT_1,
                TEST_SUBPROJECT_1,
                TeamcityLinkProject(TEST_PROJECT)
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
                TEST_SUBPROJECT_2,
                TEST_SUBPROJECT_2,
                TeamcityLinkProject(TEST_PROJECT)
            )
        )
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_SUBPROJECT_2_BUILD_1, TEST_SUBPROJECT_2_BUILD_1, project = TeamcityLinkProject(TEST_SUBPROJECT_2)
            )
        )
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                TEST_SUBPROJECT_2_BUILD_2, TEST_SUBPROJECT_2_BUILD_2, project = TeamcityLinkProject(TEST_SUBPROJECT_2)
            )
        )
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

        val TEAMCITY_OPTIONS = arrayOf("--url=http://localhost:8111", "--user=admin", "--password=admin")

        private val teamcityClient = TeamcityClassicClient(object : ClientParametersProvider {
            override fun getApiUrl() = "http://localhost:8111"

            override fun getAuth() = StandardBasicCredCredentialProvider("admin", "admin")
        })
    }
}