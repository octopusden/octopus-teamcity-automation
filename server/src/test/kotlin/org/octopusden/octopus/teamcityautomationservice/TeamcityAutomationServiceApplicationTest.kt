package org.octopusden.octopus.teamcityautomationservice

import ch.qos.logback.classic.LoggerContext
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.octopusden.octopus.teamcityautomationservice.logging.ClientWireLogFilter
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class TeamcityAutomationServiceApplicationTest {
    @Test
    fun startsWithTheWireLogFilterInstalled() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext

        assertTrue(context.turboFilterList.any { it is ClientWireLogFilter })
    }
}
