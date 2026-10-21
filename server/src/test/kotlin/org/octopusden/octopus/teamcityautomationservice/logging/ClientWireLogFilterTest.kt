package org.octopusden.octopus.teamcityautomationservice.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ClientWireLogFilterTest {
    private val context = LoggerContext().apply {
        addTurboFilter(ClientWireLogFilter().apply { start() })
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient",
            "org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceClient",
        ],
    )
    fun deniesDebugOnClientWireLoggersWhateverTheirLevel(name: String) {
        val logger = context.getLogger(name).apply { level = Level.TRACE }

        assertFalse(logger.isDebugEnabled)
        assertFalse(logger.isTraceEnabled)
        assertTrue(logger.isInfoEnabled)
    }

    @ParameterizedTest
    @ValueSource(strings = ["org.octopusden.octopus.teamcityautomationservice", "feign"])
    fun leavesOtherLoggersAlone(name: String) {
        val logger = context.getLogger(name).apply { level = Level.DEBUG }

        assertTrue(logger.isDebugEnabled)
    }
}
