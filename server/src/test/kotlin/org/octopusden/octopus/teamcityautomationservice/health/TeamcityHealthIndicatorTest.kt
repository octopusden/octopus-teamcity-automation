package org.octopusden.octopus.teamcityautomationservice.health

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.octopusden.octopus.teamcityautomationservice.config.TeamcityAutomationServiceProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Status
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64
import java.util.UUID

class TeamcityHealthIndicatorTest {
    private var status = 200
    private var delayMillis = 0L
    private var requestedPath: String? = null
    private var authorization: String? = null
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(TeamcityHealthIndicator::class.java) as Logger

    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/") { exchange ->
            requestedPath = exchange.requestURI.path
            authorization = exchange.requestHeaders.getFirst("Authorization")
            Thread.sleep(delayMillis)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        start()
    }

    @BeforeEach
    fun capture() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun stop() {
        logger.detachAppender(appender)
        server.stop(0)
    }

    @Test
    fun isUpWhenTeamcityAnswers200ToTheServiceAccount() {
        val health = indicator("http://localhost:${server.address.port}/tc/").health()

        assertEquals(Status.UP, health.status)
        assertTrue(health.details.isEmpty())
        assertEquals("/tc/app/rest/server", requestedPath)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("$USERNAME:$PASSWORD".toByteArray()), authorization)
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 404, 500, 503])
    fun isDownOnAnyOtherStatusAndLogsTheCauseWithoutThePassword(httpStatus: Int) {
        status = httpStatus

        val health = indicator("http://localhost:${server.address.port}").health()

        assertEquals(Status.DOWN, health.status)
        assertTrue(health.details.isEmpty())
        val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings.single().contains("$httpStatus"))
        assertFalse(warnings.single().contains(PASSWORD))
    }

    @Test
    fun isDownWhenTeamcityDoesNotAnswerInTime() {
        delayMillis = 2_000

        val health = indicator("http://localhost:${server.address.port}", Duration.ofMillis(200)).health()

        assertEquals(Status.DOWN, health.status)
        assertTrue(health.details.isEmpty())
        assertTrue(appender.list.any { it.level == Level.WARN })
    }

    @Test
    fun isDownWhenTheConnectionIsRefused() {
        val port = server.address.port
        server.stop(0)

        val health = indicator("http://localhost:$port").health()

        assertEquals(Status.DOWN, health.status)
        val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertEquals(1, warnings.size, warnings.toString())
        assertFalse(warnings.single().contains(PASSWORD))
    }

    private fun indicator(
        url: String,
        timeout: Duration = Duration.ofSeconds(5),
    ) = TeamcityHealthIndicator(TeamcityAutomationServiceProperties.Teamcity(url, USERNAME, PASSWORD), timeout)

    companion object {
        private const val USERNAME = "service-account"

        // Generated per run: no credential, not even a dummy one, is written in the sources.
        private val PASSWORD = UUID.randomUUID().toString()
    }
}
