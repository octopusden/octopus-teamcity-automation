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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.automation.teamcity.TeamcityCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterIncrementCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUpdateParameterSetCommand
import org.octopusden.octopus.automation.teamcity.TeamcityUploadMetarunnersCommand
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.deleteProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkProject


class ApplicationTest {
    private val jar = System.getProperty("jar") ?: throw IllegalStateException("System property 'jar' must be provided")

    private fun execute(name: String, vararg command: String) =
        ProcessBuilder("java", "-jar", jar, *command).redirectErrorStream(true).redirectOutput(
            File("").resolve("build").resolve("logs").resolve("$name.log").also { it.parentFile.mkdirs() }).start()
            .waitFor()

    @Test
    fun testTeamCityUpdateParameterSet(testInfo: TestInfo) {
        val parameter = "TEST_PARAMETER"
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter, "OLD")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter, "OLD")
        Assertions.assertEquals(
            0, execute(
                testInfo.testMethod.get().name,
                *TEAMCITY_OPTIONS,
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

    @Test
    fun testTeamCityUpdateParameterIncrement(testInfo: TestInfo) {
        val parameter = "TEST_PARAMETER"
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_1, parameter, "1.0")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_BUILD_2, parameter, "1.1")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_1, parameter, "1.2")
        teamcityClient.setParameter(ConfigurationType.PROJECT, TEST_SUBPROJECT_2, parameter, "INVALID")
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, TEST_SUBPROJECT_2_BUILD_1, parameter, "1.3")
        Assertions.assertEquals(
            0, execute(
                testInfo.testMethod.get().name,
                *TEAMCITY_OPTIONS,
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
            0, execute(
                testInfo.testMethod.get().name,
                *TEAMCITY_OPTIONS,
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

    @Test
    fun testTeamCityUploadMetarunners(testInfo: TestInfo) {
        val metarunners = ApplicationTest::class.java.getResource("metarunners.zip")!!
        Assertions.assertEquals(
            0, execute(
                testInfo.testMethod.get().name,
                *TEAMCITY_OPTIONS,
                TeamcityUploadMetarunnersCommand.COMMAND,
                "${TeamcityUploadMetarunnersCommand.PROJECT_ID_OPTION}=$TEST_PROJECT",
                "${TeamcityUploadMetarunnersCommand.ZIP_OPTION}=$metarunners"
            )
        )
        htmlDocument(
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(URI("$TEAMCITY_URL/admin/editProject.html?projectId=$TEST_PROJECT&tab=metaRunner"))
                    .header("Origin", TEAMCITY_URL)
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

    @ParameterizedTest
    @MethodSource("validCommands")
    fun testValidCommands(name: String, command: Array<String>) = Assertions.assertEquals(0, execute(name, *command))

    @ParameterizedTest
    @MethodSource("invalidCommands")
    fun testInvalidCommands(name: String, command: Array<String>) = Assertions.assertEquals(1, execute(name, *command))

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

        const val HELP_OPTION = "-h"

        const val TEAMCITY_URL = "http://localhost:8111"
        const val TEAMCITY_USER = "admin"
        const val TEAMCITY_PASSWORD = "admin"

        val TEAMCITY_OPTIONS = arrayOf(
            "${TeamcityCommand.URL_OPTION}=$TEAMCITY_URL",
            "${TeamcityCommand.USER_OPTION}=$TEAMCITY_USER",
            "${TeamcityCommand.PASSWORD_OPTION}=$TEAMCITY_PASSWORD"
        )

        private val teamcityClient = TeamcityClassicClient(object : ClientParametersProvider {
            override fun getApiUrl() = TEAMCITY_URL

            override fun getAuth() = StandardBasicCredCredentialProvider(TEAMCITY_USER, TEAMCITY_PASSWORD)
        })

        //<editor-fold defaultstate="collapsed" desc="Test Data">
        @JvmStatic
        private fun validCommands(): Stream<Arguments> = Stream.of(
            Arguments.of("validCommand", arrayOf(HELP_OPTION)),
            Arguments.of(
                "validCommand2", arrayOf(*TEAMCITY_OPTIONS, TeamcityUpdateParameterCommand.COMMAND, HELP_OPTION)
            ),
            Arguments.of(
                "validCommand3", arrayOf(
                    *TEAMCITY_OPTIONS,
                    TeamcityUpdateParameterCommand.COMMAND,
                    "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                    "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=test",
                    TeamcityUpdateParameterSetCommand.COMMAND,
                    HELP_OPTION
                )
            ),
            Arguments.of(
                "validCommand4", arrayOf(
                    *TEAMCITY_OPTIONS,
                    TeamcityUpdateParameterCommand.COMMAND,
                    "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                    "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}=test",
                    "${TeamcityUpdateParameterCommand.BUILD_TYPE_IDS_OPTION}=",
                    TeamcityUpdateParameterSetCommand.COMMAND,
                    HELP_OPTION
                )
            ),
            Arguments.of(
                "validCommand5", arrayOf(*TEAMCITY_OPTIONS, TeamcityUploadMetarunnersCommand.COMMAND, HELP_OPTION)
            )
        )

        @JvmStatic
        private fun invalidCommands(): Stream<Arguments> = Stream.of(
            Arguments.of("invalidCommand", arrayOf(TeamcityUpdateParameterCommand.COMMAND, HELP_OPTION)),
            Arguments.of(
                "invalidCommand2", arrayOf(
                    *(TEAMCITY_OPTIONS.clone().also { it[2] = "${TeamcityCommand.PASSWORD_OPTION}=" }),
                    TeamcityUpdateParameterCommand.COMMAND,
                    HELP_OPTION
                )
            ),
            Arguments.of(
                "invalidCommand3", arrayOf(
                    *(TEAMCITY_OPTIONS.clone().also { it[2] = "${TeamcityCommand.PASSWORD_OPTION}=invalid" }),
                    TeamcityUpdateParameterCommand.COMMAND,
                    HELP_OPTION
                )
            ),
            Arguments.of(
                "invalidCommand4", arrayOf(
                    *TEAMCITY_OPTIONS,
                    TeamcityUpdateParameterCommand.COMMAND,
                    "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                    "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}= , ",
                    TeamcityUpdateParameterSetCommand.COMMAND,
                    HELP_OPTION
                )
            ),
            Arguments.of(
                "invalidCommand5", arrayOf(
                    *TEAMCITY_OPTIONS,
                    TeamcityUpdateParameterCommand.COMMAND,
                    "${TeamcityUpdateParameterCommand.NAME_OPTION}= ",
                    "${TeamcityUpdateParameterCommand.PROJECT_IDS_OPTION}=test",
                    TeamcityUpdateParameterSetCommand.COMMAND,
                    HELP_OPTION
                )
            ),
            Arguments.of(
                "invalidCommand6", arrayOf(
                    *TEAMCITY_OPTIONS,
                    TeamcityUpdateParameterCommand.COMMAND,
                    "${TeamcityUpdateParameterCommand.NAME_OPTION}=test",
                    TeamcityUpdateParameterSetCommand.COMMAND,
                    HELP_OPTION
                )
            ),
            Arguments.of(
                "invalidCommand7", arrayOf(
                    *TEAMCITY_OPTIONS,
                    TeamcityUpdateParameterCommand.COMMAND,
                    TeamcityUpdateParameterSetCommand.COMMAND,
                    HELP_OPTION
                )
            ),
            Arguments.of(
                "invalidCommand8", arrayOf(
                    *TEAMCITY_OPTIONS,
                    TeamcityUploadMetarunnersCommand.COMMAND,
                    "${TeamcityUploadMetarunnersCommand.PROJECT_ID_OPTION}= ",
                    "${TeamcityUploadMetarunnersCommand.ZIP_OPTION}=file:///test.zip"
                )
            ),
            Arguments.of(
                "invalidCommand9", arrayOf(
                    *TEAMCITY_OPTIONS,
                    TeamcityUploadMetarunnersCommand.COMMAND,
                    "${TeamcityUploadMetarunnersCommand.PROJECT_ID_OPTION}=test",
                    "${TeamcityUploadMetarunnersCommand.ZIP_OPTION}=invalid"
                )
            )
        )
        //</editor-fold>
    }
}