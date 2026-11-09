package org.octopusden.octopus.teamcityautomationservice.config

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * The one TeamCity server the service works with, and the account it uses. A blank value stops the
 * startup with an error that names the property. Keys the shared service config defines under the same
 * prefix for other services (e.g. `teamcity.host`) are ignored.
 */
@Validated
@ConfigurationProperties("teamcity")
data class TeamcityProperties(
    /** Base URL of the TeamCity server, e.g. `https://teamcity.example.com`. */
    @field:NotBlank(message = "teamcity.url must not be blank")
    val url: String = "",
    /** The account the service creates chains as. */
    @field:NotBlank(message = "teamcity.username must not be blank")
    val username: String = "",
    @field:NotBlank(message = "teamcity.password must not be blank")
    val password: String = "",
) {
    // Checked as a boolean, not with @Pattern on the field: a rejected value is printed in the startup error.
    @get:AssertTrue(message = "teamcity.url $URL_MESSAGE")
    val isUrlValid: Boolean get() = url.isBlank() || URL_REGEX.matches(url)

    // Printed in logs and exception messages; the password must not be.
    override fun toString() = "TeamcityProperties(url=$url, username=$username, password=***)"
}

// No user info: credentials belong in their own properties.
internal val URL_REGEX = Regex("https?://[^\\s/?#@]+([/?#]\\S*)?")
internal const val URL_MESSAGE = "must be an http(s) URL without a user or password"
