package org.octopusden.octopus.teamcityautomationservice.health

import org.octopusden.octopus.teamcityautomationservice.config.TeamcityProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * UP when the configured TeamCity answers `GET /app/rest/server` to the service's account. The one call
 * proves the URL, the network path and the credentials. The response carries the status only; the cause
 * goes to the log, never with the password. The TeamCity client has no timeout and logs full exchanges,
 * so this is a plain HTTP call. The timeout is one overall deadline for the whole exchange, headers and
 * body: the request timeout alone covers only the headers, so a stalled body would block the probe.
 */
@Component
class TeamcityHealthIndicator(
    private val teamcity: TeamcityProperties,
    private val timeout: Duration,
) : HealthIndicator {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    @Autowired
    constructor(properties: TeamcityProperties) : this(properties, DEFAULT_TIMEOUT)

    // Whatever goes wrong, the answer is DOWN; the cause is logged.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun health(): Health {
        var future: CompletableFuture<HttpResponse<Void>>? = null
        return try {
            val sent = client.sendAsync(request(), HttpResponse.BodyHandlers.discarding())
            future = sent
            val response = sent.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (response.statusCode() in HTTP_OK_RANGE) {
                Health.up().build()
            } else {
                down("TeamCity answered HTTP ${response.statusCode()}")
            }
        } catch (e: TimeoutException) {
            future?.cancel(true)
            down("TeamCity did not answer within $timeout")
        } catch (e: InterruptedException) {
            future?.cancel(true)
            Thread.currentThread().interrupt()
            down("the check was interrupted")
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            down("${cause.javaClass.simpleName}: ${cause.message}")
        } catch (e: Exception) {
            future?.cancel(true)
            down("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun request(): HttpRequest {
        val credentials = Base64.getEncoder().encodeToString("${teamcity.username}:${teamcity.password}".toByteArray())
        return HttpRequest
            .newBuilder(URI.create(teamcity.url.trimEnd('/') + "/app/rest/server"))
            .timeout(timeout)
            .header("Authorization", "Basic $credentials")
            .GET()
            .build()
    }

    private fun down(cause: String): Health {
        log.warn("TeamCity health check failed: {}", cause)
        return Health.down().build()
    }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val HTTP_OK_RANGE = 200..299
        val log = LoggerFactory.getLogger(TeamcityHealthIndicator::class.java)
    }
}
