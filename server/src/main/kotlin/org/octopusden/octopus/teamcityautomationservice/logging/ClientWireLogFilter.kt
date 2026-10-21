package org.octopusden.octopus.teamcityautomationservice.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.slf4j.Marker

/**
 * The TeamCity and Components Registry clients log every exchange at Feign's FULL level, headers
 * included, so DEBUG on their loggers would write the caller's Basic credentials to the log. This
 * denies those loggers anything below INFO, whatever level config or actuator sets.
 */
class ClientWireLogFilter : TurboFilter() {
    override fun decide(
        marker: Marker?,
        logger: Logger?,
        level: Level?,
        format: String?,
        params: Array<out Any>?,
        t: Throwable?,
    ): FilterReply =
        if (logger?.name in WIRE_LOGGERS && level != null && !level.isGreaterOrEqual(Level.INFO)) {
            FilterReply.DENY
        } else {
            FilterReply.NEUTRAL
        }

    companion object {
        val WIRE_LOGGERS = setOf(TeamcityClient::class.java.name, ComponentsRegistryServiceClient::class.java.name)
    }
}
