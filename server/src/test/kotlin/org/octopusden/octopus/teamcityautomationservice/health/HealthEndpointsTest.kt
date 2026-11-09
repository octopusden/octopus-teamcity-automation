package org.octopusden.octopus.teamcityautomationservice.health

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.net.InetSocketAddress

/** The health groups, against a stub TeamCity whose answer the test sets. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthEndpointsTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun teamcityIsUp() {
        status = 200
    }

    @Test
    fun readinessComponentIsUpWithTheStatusOnly() {
        mockMvc.get("/actuator/health/readiness/teamcity").andExpect {
            status { isOk() }
            content { json("""{"status":"UP"}""", true) }
        }
    }

    @Test
    fun readinessComponentIsDownWithTheStatusOnly() {
        status = 401

        mockMvc.get("/actuator/health/readiness/teamcity").andExpect {
            status { isServiceUnavailable() }
            content { json("""{"status":"DOWN"}""", true) }
        }
    }

    @Test
    fun theComponentIsNotServedOutsideTheReadinessGroup() {
        mockMvc.get("/actuator/health/teamcity").andExpect { status { isNotFound() } }
    }

    @Test
    fun livenessStaysUpWhileTeamcityIsDown() {
        status = 503

        mockMvc.get("/actuator/health/liveness").andExpect {
            status { isOk() }
            content { json("""{"status":"UP"}""", true) }
        }
    }

    @Test
    fun readinessAndTheAggregateAreUpWhenTeamcityIsUp() {
        mockMvc.get("/actuator/health/readiness").andExpect { status { isOk() } }
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }

    @Test
    fun readinessAndTheAggregateTurnDownWithTeamcityAndRecover() {
        status = 503

        mockMvc.get("/actuator/health/readiness").andExpect { status { isServiceUnavailable() } }
        mockMvc.get("/actuator/health").andExpect { status { isServiceUnavailable() } }

        status = 200

        mockMvc.get("/actuator/health/readiness").andExpect { status { isOk() } }
    }

    @Test
    fun showsNoDetails() {
        mockMvc.get("/actuator/health/readiness").andExpect {
            content { json("""{"status":"UP","components":{"readinessState":{"status":"UP"},"teamcity":{"status":"UP"}}}""", true) }
        }
    }

    companion object {
        @Volatile
        private var status = 200

        private val teamcity = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun teamcityUrl(registry: DynamicPropertyRegistry) {
            registry.add("teamcity-automation-service.teamcity.url") { "http://localhost:${teamcity.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stopTeamcity() = teamcity.stop(0)
    }
}
