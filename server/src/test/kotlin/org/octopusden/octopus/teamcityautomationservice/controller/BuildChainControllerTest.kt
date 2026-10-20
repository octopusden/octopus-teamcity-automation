package org.octopusden.octopus.teamcityautomationservice.controller

import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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
import org.octopusden.octopus.teamcityautomationservice.service.BuildChainService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.util.UUID

@WebMvcTest(BuildChainController::class)
@ActiveProfiles("test")
class BuildChainControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var buildChainService: BuildChainService

    @Test
    fun createsChainAndAnswers201() {
        whenever(buildChainService.createBuildChain(any())) doReturn
            BuildChainDTO("P_C", "P_C_10", null, null, "P_C_40")

        post(VALID_BODY).andExpect {
            status { isCreated() }
            jsonPath("$.projectId") { value("P_C") }
            jsonPath("$.compileBuildTypeId") { value("P_C_10") }
            jsonPath("$.rcBuildTypeId") { doesNotExist() }
            jsonPath("$.releaseBuildTypeId") { value("P_C_40") }
        }
        verify(buildChainService).createBuildChain(
            CreateBuildChainRequest(
                TeamcityConnection("http://teamcity:8111", "user", PASSWORD),
                "http://registry:4567",
                "Parent",
                "component",
                "1.0",
            ),
        )
    }

    @Test
    fun rejectsBlankFieldsWithoutEchoingValues() {
        post(VALID_BODY.replace("\"component\": \"component\"", "\"component\": \" \"").replace("\"user\": \"user\"", "\"user\": \"\""))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.errorCode") { value("VALIDATION") }
                jsonPath("$.errorMessage") { value(containsString("component: must not be blank")) }
                jsonPath("$.errorMessage") { value(containsString("teamcity.user: must not be blank")) }
            }
        verify(buildChainService, never()).createBuildChain(any())
    }

    @Test
    fun rejectsNonHttpUrl() {
        post(VALID_BODY.replace("http://registry:4567", "registry:4567")).andExpect {
            status { isBadRequest() }
            jsonPath("$.errorMessage") { value("componentsRegistryUrl: must be an http(s) URL without a user or password") }
        }
    }

    @Test
    fun rejectsCredentialsInUrlsWithoutEchoingThem() {
        val body = VALID_BODY
            .replace("http://teamcity:8111", withUserInfo("http://teamcity:8111", "user"))
            .replace("http://registry:4567", withUserInfo("https://registry:4567/path", "reader"))

        post(body).andExpect {
            status { isBadRequest() }
            jsonPath("$.errorMessage") { value(containsString("teamcity.url: must be an http(s) URL without a user or password")) }
            jsonPath("$.errorMessage") { value(containsString("componentsRegistryUrl: must be an http(s) URL without a user or password")) }
            content { string(not(containsString(URL_PASSWORD))) }
        }
        verify(buildChainService, never()).createBuildChain(any())
    }

    @Test
    fun rejectsMissingPasswordNamingOnlyTheField() {
        post(VALID_BODY.replace(", \"password\": \"$PASSWORD\"", "")).andExpect {
            status { isBadRequest() }
            jsonPath("$.errorCode") { value("VALIDATION") }
            jsonPath("$.errorMessage") { value("teamcity.password: missing or of the wrong type") }
        }
    }

    @Test
    fun rejectsMalformedBody() {
        post("{").andExpect {
            status { isBadRequest() }
            jsonPath("$.errorMessage") { value("Malformed request body") }
        }
    }

    @ParameterizedTest
    @MethodSource("serviceExceptions")
    fun mapsServiceExceptionToStatus(
        exception: Exception,
        status: Int,
        errorCode: String,
    ) {
        whenever(buildChainService.createBuildChain(any())) doThrow exception

        post(VALID_BODY).andExpect {
            status { isEqualTo(status) }
            jsonPath("$.errorCode") { value(errorCode) }
            content { string(not(containsString(PASSWORD))) }
        }
    }

    @Test
    fun namesExistingProjectIn409() {
        whenever(buildChainService.createBuildChain(any())) doThrow AlreadyExistsException("exists", "P_C")

        post(VALID_BODY).andExpect {
            status { isConflict() }
            jsonPath("$.projectId") { value("P_C") }
        }
    }

    @Test
    fun namesCreatedObjectsOnPartialFailure() {
        whenever(buildChainService.createBuildChain(any())) doThrow
            PartiallyCreatedException("partial", "P_C", listOf("P_C_10"))

        post(VALID_BODY).andExpect {
            status { isBadGateway() }
            jsonPath("$.errorCode") { value("PARTIALLY_CREATED") }
            jsonPath("$.projectId") { value("P_C") }
            jsonPath("$.buildTypeIds[0]") { value("P_C_10") }
        }
    }

    @Test
    fun hidesMessageOfUnexpectedException() {
        whenever(buildChainService.createBuildChain(any())) doThrow IllegalStateException("password=$PASSWORD")

        post(VALID_BODY).andExpect {
            status { isInternalServerError() }
            jsonPath("$.errorCode") { value("OTHER") }
            jsonPath("$.errorMessage") { value("Internal server error") }
        }
    }

    private fun withUserInfo(
        url: String,
        user: String,
    ) = url.replaceFirst("://", "://" + user + ":" + URL_PASSWORD + "@")

    private fun post(body: String): ResultActionsDsl =
        mockMvc.post("/rest/api/1/build-chains") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    companion object {
        // Generated per run: no credential, not even a dummy one, is written in the sources.
        private val PASSWORD = UUID.randomUUID().toString()
        private val URL_PASSWORD = UUID.randomUUID().toString()
        private val VALID_BODY =
            """
            {
              "teamcity": { "url": "http://teamcity:8111", "user": "user", "password": "$PASSWORD" },
              "componentsRegistryUrl": "http://registry:4567",
              "parentProjectId": "Parent",
              "component": "component",
              "minorVersion": "1.0"
            }
            """.trimIndent()

        @JvmStatic
        fun serviceExceptions(): List<Arguments> =
            listOf(
                Arguments.of(ValidationException("invalid"), 400, "VALIDATION"),
                Arguments.of(UnauthorizedException("rejected"), 401, "UNAUTHORIZED"),
                Arguments.of(ForbiddenException("denied"), 403, "FORBIDDEN"),
                Arguments.of(NotFoundException("missing"), 404, "NOT_FOUND"),
                Arguments.of(AlreadyExistsException("exists", "P_C"), 409, "ALREADY_EXISTS"),
                Arguments.of(UnsupportedComponentException("unsupported"), 422, "UNSUPPORTED_COMPONENT"),
                Arguments.of(PartiallyCreatedException("partial", "P_C", emptyList()), 502, "PARTIALLY_CREATED"),
                Arguments.of(UpstreamException("down"), 502, "UPSTREAM_ERROR"),
            )
    }
}
