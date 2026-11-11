package org.octopusden.octopus.teamcityautomationservice

import com.fasterxml.jackson.databind.ObjectMapper
import feign.FeignException
import org.junit.jupiter.api.Assertions
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
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.getProject
import org.octopusden.octopus.infrastructure.teamcity.client.getSnapshotDependencies
import org.octopusden.octopus.teamcityautomationservice.client.impl.ClassicTeamcityAutomationServiceClient
import org.octopusden.octopus.teamcityautomationservice.client.impl.TeamcityAutomationServiceClientParametersProvider
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** A TeamCity server as the tests reach it, and the service instance that works with it. */
data class TeamcityServer(
    val name: String,
    val host: String,
    val serviceHost: String,
) {
    val client =
        TeamcityClassicClient(
            object : ClientParametersProvider {
                override fun getApiUrl() = host

                override fun getAuth() = StandardBasicCredCredentialProvider(ADMIN, ADMIN)
            },
        )

    /** The service instance that works with this server. */
    val service =
        ClassicTeamcityAutomationServiceClient(
            object : TeamcityAutomationServiceClientParametersProvider {
                override fun getApiUrl() = "http://$serviceHost"

                override fun getConnectTimeoutInMillis() = 10_000

                override fun getReadTimeoutInMillis() = 120_000
            },
        )

    override fun toString() = name

    /** The parent project with the default templates, the release steps the chain expects and `JDK_VERSION` 1.8. */
    fun resetParentProject() {
        // Absent on the first run against a server.
        runCatching { client.deleteProject(PARENT_PROJECT) }
        createUser(TEST_USER, TEST_USER)
        createUser(TEST_USER_2, TEST_USER_2)
        client.createProject(TeamcityCreateProject(PARENT_PROJECT, PARENT_PROJECT, TeamcityLinkProject("RDDepartment")))
        client.setParameter(ConfigurationType.PROJECT, PARENT_PROJECT, "JDK_VERSION", "1.8")
        TEMPLATES.forEach {
            client.createBuildType(TeamcityCreateBuildType(it, it, project = TeamcityLinkProject(PARENT_PROJECT), templateFlag = true))
        }
        listOf("IncrementTeamCityBuildConfigurationParameter", "Deploy to Share").forEach { stepName ->
            client.createBuildStep(
                RELEASE_TEMPLATE,
                step =
                    TeamcityStep(
                        stepName,
                        stepName,
                        stepName,
                        disabled = false,
                        properties = TeamcityProperties(listOf(TeamcityProperty("property", ""))),
                    ),
            )
        }
    }

    fun templateOf(buildTypeId: String?) =
        client
            .getBuildType(requireNotNull(buildTypeId))
            .templates
            ?.buildTypes
            ?.single()
            ?.id

    fun dependencyOf(buildTypeId: String?) =
        client
            .getSnapshotDependencies(requireNotNull(buildTypeId))
            .snapshotDependencies
            .single()
            .sourceBuildType.id

    fun assertNoProject(projectId: String) {
        Assertions.assertThrows(FeignException.NotFound::class.java) { client.getProject(projectId) }
    }

    /** (roleId, scope) pairs. */
    fun userRoles(username: String): List<Pair<String, String>> {
        val response =
            HttpClient.newHttpClient().send(
                adminRequest("$host/app/rest/users/username:$username/roles").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        Assertions.assertEquals(200, response.statusCode(), response.body())
        return ObjectMapper().readTree(response.body())["role"]?.map { it["roleId"].asText() to it["scope"].asText() } ?: emptyList()
    }

    private fun createUser(
        username: String,
        password: String,
    ) {
        val response =
            HttpClient.newHttpClient().send(
                adminRequest("$host/app/rest/users")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(userJson(username, password)))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        // 400: the user already exists
        Assertions.assertTrue(response.statusCode() in 200..299 || response.statusCode() == 400, response.body())
        if (response.statusCode() == 400) {
            setPassword(username, password)
        }
    }

    private fun userJson(
        username: String,
        password: String,
    ) = ObjectMapper().writeValueAsString(mapOf("username" to username, "password" to password))

    private fun setPassword(
        username: String,
        password: String,
    ) {
        val response =
            HttpClient.newHttpClient().send(
                adminRequest("$host/app/rest/users/username:$username/password", accept = "text/plain")
                    .header("Content-Type", "text/plain")
                    .PUT(HttpRequest.BodyPublishers.ofString(password))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        Assertions.assertTrue(response.statusCode() in 200..299, response.body())
    }

    private fun adminRequest(
        uri: String,
        accept: String = "application/json",
    ) = HttpRequest
        .newBuilder()
        .uri(URI(uri))
        .header("Accept", accept)
        .header("Authorization", "Basic ${Base64.getEncoder().encodeToString("$ADMIN:$ADMIN".toByteArray())}")

    companion object {
        // The library tests' parent project, so the registry fixtures resolve under the same ids.
        const val PARENT_PROJECT = "TestTeamcityAutomation"
        const val ADMIN = "admin"
        const val TEST_USER = "testuser"
        const val TEST_USER_2 = "testuser2"

        // BuildChainConfig defaults of the library; the "Releae" typo is the real template id.
        const val GRADLE_COMPILE_TEMPLATE = "CDGradleBuild"
        const val MAVEN_COMPILE_TEMPLATE = "CDJavaMavenBuild"
        const val RC_TEMPLATE = "CdReleaseCandidateNew"
        const val CHECKLIST_TEMPLATE = "CdReleaeChecklistValidation"
        const val RELEASE_TEMPLATE = "CDRelease"
        val TEMPLATES = listOf(GRADLE_COMPILE_TEMPLATE, MAVEN_COMPILE_TEMPLATE, RC_TEMPLATE, CHECKLIST_TEMPLATE, RELEASE_TEMPLATE)
    }
}
