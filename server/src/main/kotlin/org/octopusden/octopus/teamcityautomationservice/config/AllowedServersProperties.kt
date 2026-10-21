package org.octopusden.octopus.teamcityautomationservice.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The servers a request may name. The service calls whatever URL a request carries, so without this list
 * any caller could make it reach any host it can; an empty list refuses every request.
 */
@ConfigurationProperties("teamcity-automation-service")
data class AllowedServersProperties(
    /** Base URLs of the TeamCity servers chains may be created on, e.g. `https://teamcity.example.com`. */
    val allowedTeamcityUrls: List<String> = emptyList(),
    /** Base URLs of the Components Registry servers the service may read. */
    val allowedComponentsRegistryUrls: List<String> = emptyList(),
)
