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
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.octopusden.octopus.teamcityautomationservice.config.TeamcityProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Status
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TeamcityHealthIndicatorTest {
    @Volatile
    private var status = 200

    @Volatile
    private var delayMillis = 0L

    @Volatile
    private var requestedPath: String? = null

    @Volatile
    private var authorization: String? = null

    @Volatile
    private var stallBody = false

    @Volatile
    private var stalledAtNanos = 0L

    private val release = CountDownLatch(1)
    private val bodyStalled = CountDownLatch(1)
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(TeamcityHealthIndicator::class.java) as Logger

    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/") { exchange ->
            requestedPath = exchange.requestURI.path
            authorization = exchange.requestHeaders.getFirst("Authorization")
            Thread.sleep(delayMillis)
            if (stallBody) {
                exchange.sendResponseHeaders(status, 1_000)
                exchange.responseBody.write(ByteArray(10))
                exchange.responseBody.flush()
                stalledAtNanos = System.nanoTime()
                bodyStalled.countDown()
                release.await()
                exchange.close()
                return@createContext
            }
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
        release.countDown()
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
        val warnings = warnings()
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
    @Timeout(10)
    fun isDownWhenTheBodyStallsAfterTheHeaders() {
        stallBody = true
        val timeout = Duration.ofSeconds(1)

        val indicator = indicator("http://localhost:${server.address.port}", timeout)
        val started = System.nanoTime()
        val health = indicator.health()
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        // Shows the server sent the headers and part of the body before the deadline; without an overall
        // deadline health() would hang and @Timeout(10) would fail the test.
        assertTrue(bodyStalled.await(5, TimeUnit.SECONDS), "the handler never reached the stalled body")
        val stalledAfter = Duration.ofNanos(stalledAtNanos - started)
        assertTrue(stalledAfter < timeout, "the body stalled after $stalledAfter, past the $timeout timeout")
        assertEquals(Status.DOWN, health.status)
        assertTrue(health.details.isEmpty())
        assertTrue(elapsed < Duration.ofSeconds(3), "health() took $elapsed")
        val warnings = warnings()
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings.single().contains("did not answer within"))
    }

    @Test
    fun isDownWhenTheRequestCannotBeBuilt() {
        val health = indicator("http://localhost/%zz").health()

        assertEquals(Status.DOWN, health.status)
        assertTrue(health.details.isEmpty())
        val warnings = warnings()
        assertEquals(1, warnings.size, warnings.toString())
        assertFalse(warnings.single().contains(PASSWORD))
    }

    @Test
    fun isDownWhenTheConnectionIsRefused() {
        val port = server.address.port
        server.stop(0)

        val health = indicator("http://localhost:$port").health()

        assertEquals(Status.DOWN, health.status)
        val warnings = warnings()
        assertEquals(1, warnings.size, warnings.toString())
        assertFalse(warnings.single().contains(PASSWORD))
    }

    private fun warnings() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    private fun indicator(
        url: String,
        timeout: Duration = Duration.ofSeconds(5),
    ) = TeamcityHealthIndicator(TeamcityProperties(url, USERNAME, PASSWORD), timeout)

    companion object {
        private const val USERNAME = "service-account"

        // Generated per run: no credential, not even a dummy one, is written in the sources.
        private val PASSWORD = UUID.randomUUID().toString()
    }
}
