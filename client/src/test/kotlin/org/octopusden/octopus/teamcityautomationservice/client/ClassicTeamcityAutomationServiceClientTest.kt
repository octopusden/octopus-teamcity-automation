package org.octopusden.octopus.teamcityautomationservice.client

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
import org.octopusden.octopus.teamcityautomationservice.client.impl.ClassicTeamcityAutomationServiceClient
import org.octopusden.octopus.teamcityautomationservice.client.impl.TeamcityAutomationServiceClientParametersProvider
import java.net.InetSocketAddress
import java.util.UUID

/** Runs the client against an in-process stub of the service. */
class ClassicTeamcityAutomationServiceClientTest {
    private var status = 201
    private var contentType = "application/json"
    private var responseBody = ""
    private var receivedBody = ""

    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/rest/api/1/build-chains") { exchange ->
            receivedBody = exchange.requestBody.bufferedReader().readText()
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val client = ClassicTeamcityAutomationServiceClient(
        object : TeamcityAutomationServiceClientParametersProvider {
            override fun getApiUrl() = "http://localhost:${server.address.port}"

            override fun getConnectTimeoutInMillis() = 5_000

            override fun getReadTimeoutInMillis() = 5_000
        },
    )

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun sendsRequestAndReadsCreatedChain() {
        responseBody = """{"projectId":"P_C","compileBuildTypeId":"P_C_10","rcBuildTypeId":null,""" +
            """"checklistBuildTypeId":null,"releaseBuildTypeId":"P_C_40","unknown":1}"""

        val chain = client.createBuildChain(REQUEST)

        assertEquals(BuildChainDTO("P_C", "P_C_10", null, null, "P_C_40"), chain)
        assertTrue(receivedBody.contains(PASSWORD))
        assertTrue(receivedBody.contains(""""createChecklist":true"""))
    }

    @ParameterizedTest
    @MethodSource("errors")
    fun rethrowsTheServiceException(
        httpStatus: Int,
        errorCode: String,
        expected: Class<out Exception>,
    ) {
        status = httpStatus
        responseBody = """{"errorCode":"$errorCode","errorMessage":"message"}"""

        val e = assertThrows(expected) { client.createBuildChain(REQUEST) }

        assertEquals("message", e.message)
    }

    @Test
    fun carriesExistingProjectOf409() {
        status = 409
        responseBody = """{"errorCode":"ALREADY_EXISTS","errorMessage":"exists","projectId":"P_C"}"""

        val e = assertThrows(AlreadyExistsException::class.java) { client.createBuildChain(REQUEST) }

        assertEquals("P_C", e.projectId)
    }

    @Test
    fun carriesCreatedObjectsOfPartialFailure() {
        status = 502
        responseBody = """{"errorCode":"PARTIALLY_CREATED","errorMessage":"partial","projectId":"P_C","buildTypeIds":["P_C_10"]}"""

        val e = assertThrows(PartiallyCreatedException::class.java) { client.createBuildChain(REQUEST) }

        assertEquals("P_C", e.projectId)
        assertEquals(listOf("P_C_10"), e.buildTypeIds)
    }

    @Test
    fun fallsBackToFeignForNonJsonError() {
        status = 503
        contentType = "text/plain"
        responseBody = "unavailable"

        val e = assertThrows(feign.FeignException::class.java) { client.createBuildChain(REQUEST) }

        assertEquals(503, e.status())
    }

    companion object {
        // Generated per run: no credential, not even a dummy one, is written in the sources.
        private val PASSWORD = UUID.randomUUID().toString()
        private val REQUEST = CreateBuildChainRequest(
            TeamcityConnection("http://teamcity:8111", "user", PASSWORD),
            "http://registry:4567",
            "Parent",
            "component",
            "1.0",
        )

        @JvmStatic
        fun errors(): List<Arguments> =
            listOf(
                Arguments.of(400, "VALIDATION", ValidationException::class.java),
                Arguments.of(401, "UNAUTHORIZED", UnauthorizedException::class.java),
                Arguments.of(403, "FORBIDDEN", ForbiddenException::class.java),
                Arguments.of(404, "NOT_FOUND", NotFoundException::class.java),
                Arguments.of(422, "UNSUPPORTED_COMPONENT", UnsupportedComponentException::class.java),
                Arguments.of(502, "UPSTREAM_ERROR", UpstreamException::class.java),
                Arguments.of(500, "OTHER", IllegalStateException::class.java),
            )
    }
}
