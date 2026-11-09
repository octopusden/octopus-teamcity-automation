package org.octopusden.octopus.teamcityautomationservice.config

import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * The one TeamCity server and Components Registry the service works with, and the TeamCity account it
 * uses. A blank value stops the startup with an error that names the property.
 */
@Validated
@ConfigurationProperties("teamcity-automation-service")
data class TeamcityAutomationServiceProperties(
    @field:Valid
    val teamcity: Teamcity = Teamcity(),
    @field:Valid
    val componentsRegistry: ComponentsRegistry = ComponentsRegistry(),
) {
    data class Teamcity(
        /** Base URL of the TeamCity server, e.g. `https://teamcity.example.com`. */
        @field:NotBlank(message = "teamcity-automation-service.teamcity.url must not be blank")
        val url: String = "",
        /** The account the service creates chains as. */
        @field:NotBlank(message = "teamcity-automation-service.teamcity.username must not be blank")
        val username: String = "",
        @field:NotBlank(message = "teamcity-automation-service.teamcity.password must not be blank")
        val password: String = "",
    ) {
        // Checked as a boolean, not with @Pattern on the field: a rejected value is printed in the startup error.
        @get:AssertTrue(message = "teamcity-automation-service.teamcity.url $URL_MESSAGE")
        val isUrlValid: Boolean get() = url.isBlank() || URL_REGEX.matches(url)

        // Printed in logs and exception messages; the password must not be.
        override fun toString() = "Teamcity(url=$url, username=$username, password=***)"
    }

    data class ComponentsRegistry(
        /** Base URL of the Components Registry the service reads components from. */
        @field:NotBlank(message = "teamcity-automation-service.components-registry.url must not be blank")
        val url: String = "",
    ) {
        @get:AssertTrue(message = "teamcity-automation-service.components-registry.url $URL_MESSAGE")
        val isUrlValid: Boolean get() = url.isBlank() || URL_REGEX.matches(url)
    }
}

// No user info: credentials belong in their own properties.
private val URL_REGEX = Regex("https?://[^\\s/?#@]+([/?#]\\S*)?")
private const val URL_MESSAGE = "must be an http(s) URL without a user or password"
