package org.octopusden.octopus.automation.teamcity.github

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/** Against a local stand-in for the GitHub REST API; nothing reaches github.com. */
class CommitStatusPublisherTest {
    private data class Request(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String,
    )

    private val requests = mutableListOf<Request>()
    private val server = HttpServer.create(InetSocketAddress(0), 0).also { server ->
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            requests +=
                Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), body)
            val base = "http://localhost:${server.address.port}"
            val response = when {
                exchange.requestURI.path == "/repos/octopusden/demo" ->
                    """{"id":1,"name":"demo","full_name":"octopusden/demo",""" +
                        """"owner":{"login":"octopusden"},"url":"$base/repos/octopusden/demo"}"""
                exchange.requestURI.path.startsWith("/repos/octopusden/demo/statuses/") ->
                    """{"id":2,"state":"success","context":"ci","url":"$base/status/2"}"""
                else -> "{}"
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (exchange.requestMethod == "POST") 201 else 200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
    }
    private val apiUrl = "http://localhost:${server.address.port}"

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun postsStatusForTheCommitWithToken() {
        CommitStatusPublisher("secret-token", apiUrl).post(
            CommitStatus("octopusden", "demo", "abc123", CommitState.SUCCESS, context = "ci", description = "Build passed"),
        )

        val post = requests.single { it.method == "POST" }
        Assertions.assertEquals("/repos/octopusden/demo/statuses/abc123", post.path)
        Assertions.assertEquals("token secret-token", post.authorization)
        val body = ObjectMapper().readTree(post.body)
        Assertions.assertEquals("success", body["state"].asText())
        Assertions.assertEquals("ci", body["context"].asText())
        Assertions.assertEquals("Build passed", body["description"].asText())
    }

    @Test
    fun omitsEmptyDescription() {
        CommitStatusPublisher("secret-token", "$apiUrl/")
            .post(CommitStatus("octopusden", "demo", "abc123", CommitState.PENDING, description = ""))

        val body = ObjectMapper().readTree(requests.single { it.method == "POST" }.body)
        Assertions.assertEquals("pending", body["state"].asText())
        Assertions.assertEquals(CommitStatus.DEFAULT_CONTEXT, body["context"].asText())
        Assertions.assertTrue(body["description"] == null || body["description"].isNull, body.toString())
    }

    @Test
    fun rejectsBlankCoordinatesAndToken() {
        Assertions.assertThrows(IllegalArgumentException::class.java) { CommitStatus(" ", "demo", "abc", CommitState.ERROR) }
        Assertions.assertThrows(IllegalArgumentException::class.java) { CommitStatusPublisher("") }
    }
}
