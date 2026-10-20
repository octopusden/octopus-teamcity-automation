package org.octopusden.octopus.teamcityautomationservice.client.common.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/** The TeamCity server the chain is created on, and the account that creates it. */
data class TeamcityConnection(
    @field:NotBlank
    @field:Pattern(regexp = URL_PATTERN, message = URL_MESSAGE)
    val url: String,
    @field:NotBlank
    val user: String,
    @field:NotBlank
    val password: String,
) {
    // The request object ends up in log lines and exception messages; the password must not.
    override fun toString() = "TeamcityConnection(url=$url, user=$user, password=***)"
}

// No user info: credentials belong in their own fields, and the URL is logged.
internal const val URL_PATTERN = "https?://[^\\s/?#@]+([/?#]\\S*)?"
internal const val URL_MESSAGE = "must be an http(s) URL without a user or password"
