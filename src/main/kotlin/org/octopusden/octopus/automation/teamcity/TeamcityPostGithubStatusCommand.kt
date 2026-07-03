package org.octopusden.octopus.automation.teamcity

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.BuildTypeLocator
import org.slf4j.Logger
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Posts a commit status to GitHub (`POST /repos/{owner}/{repo}/statuses/{sha}`) so TeamCity
 * builds can gate GitHub branch protection rules.
 *
 * The GitHub organization and repository are derived from the build configuration's VCS root
 * URL, so no organization needs to be hardcoded.
 */
class TeamcityPostGithubStatusCommand : CliktCommand(name = COMMAND) {

    private val buildTypeId by option(BUILD_TYPE_ID, help = "TeamCity build configuration id (used to resolve the VCS root URL)")
        .convert { it.trim() }.required()
        .check("$BUILD_TYPE_ID is empty") { it.isNotEmpty() }

    private val commit by option(COMMIT, help = "Git commit SHA to attach the status to")
        .convert { it.trim() }.required()
        .check("$COMMIT is empty") { it.isNotEmpty() }

    private val token by option(TOKEN, help = "GitHub API token")
        .convert { it.trim() }.required()
        .check("$TOKEN is empty") { it.isNotEmpty() }

    private val state by option(STATE, help = "Commit status state: one of $ALLOWED_STATES")
        .convert { it.trim().lowercase() }.required()
        .check("$STATE must be one of $ALLOWED_STATES") { ALLOWED_STATES.contains(it) }

    private val statusContext by option(CONTEXT, help = "Status check context (must match the branch protection rule)")
        .convert { it.trim() }.default(DEFAULT_CONTEXT)

    private val description by option(DESCRIPTION, help = "Short status description").default("")

    private val targetUrl by option(TARGET_URL, help = "URL linked from the GitHub status").default("")

    private val githubApiUrl by option(GITHUB_API_URL, help = "GitHub API base URL")
        .convert { it.trim().trimEnd('/') }.default(DEFAULT_GITHUB_API_URL)

    private val vcsUrl by option(VCS_URL, help = "Git repository URL override; resolved from the build configuration if omitted")
        .convert { it.trim() }

    private val context by requireObject<MutableMap<String, Any>>()

    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }

    private val log by lazy { context[TeamcityCommand.LOG] as Logger }

    override fun run() {
        log.info("Executing $COMMAND")
        val gitUrl = vcsUrl?.takeIf { it.isNotEmpty() } ?: resolveVcsUrl(buildTypeId)
        val (owner, repo) = parseOwnerRepo(gitUrl)
        postStatus(owner, repo)
    }

    private fun resolveVcsUrl(buildTypeId: String): String {
        val entries = client.getBuildTypeVcsRootEntries(BuildTypeLocator(id = buildTypeId)).entries
        val entry = entries.firstOrNull()
            ?: throw IllegalStateException("No VCS root attached to build configuration '$buildTypeId'")
        val rawUrl = client.getVcsRootProperty(entry.vcsRoot.id, PROPERTY_URL)
        // The VCS root URL may reference TeamCity parameters (e.g. %OCTOPUS_MODULE_NAME%); the
        // REST API returns them unexpanded, so resolve them against the build configuration.
        return expandParameters(rawUrl, buildTypeId)
    }

    private fun expandParameters(value: String, buildTypeId: String, depth: Int = 0): String {
        if (depth >= MAX_PARAM_DEPTH || !value.contains('%')) {
            return value
        }
        val resolved = PARAM_REGEX.replace(value) { match ->
            val name = match.groupValues[1]
            try {
                client.getParameter(ConfigurationType.BUILD_TYPE, buildTypeId, name)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Cannot resolve TeamCity parameter '%$name%' referenced in the VCS root URL of build configuration '$buildTypeId'",
                    e
                )
            }
        }
        return if (resolved == value) resolved else expandParameters(resolved, buildTypeId, depth + 1)
    }

    private fun postStatus(owner: String, repo: String) {
        val payload = linkedMapOf<String, String>("state" to state, "context" to statusContext)
        if (description.isNotEmpty()) payload["description"] = description
        if (targetUrl.isNotEmpty()) payload["target_url"] = targetUrl
        val body = OBJECT_MAPPER.writeValueAsBytes(payload)

        val endpoint = "$githubApiUrl/repos/$owner/$repo/statuses/$commit"
        log.info("Posting GitHub commit status '$state' (context '$statusContext') to $owner/$repo@$commit")

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "token $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code >= 400) {
                val error = connection.errorStream?.readBytesSafely() ?: ""
                throw IOException("Failed to post GitHub status (HTTP $code): $error")
            }
            log.info("GitHub commit status posted (HTTP $code)")
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesSafely(): String =
        use { it.readBytes().toString(StandardCharsets.UTF_8) }

    companion object {
        const val COMMAND = "post-github-status"
        const val BUILD_TYPE_ID = "--build-type-id"
        const val COMMIT = "--commit"
        const val TOKEN = "--token"
        const val STATE = "--state"
        const val CONTEXT = "--context"
        const val DESCRIPTION = "--description"
        const val TARGET_URL = "--target-url"
        const val GITHUB_API_URL = "--github-api-url"
        const val VCS_URL = "--vcs-url"

        const val PROPERTY_URL = "url"
        const val DEFAULT_CONTEXT = "TeamCity / build"
        const val DEFAULT_GITHUB_API_URL = "https://api.github.com"
        const val MAX_PARAM_DEPTH = 10
        val ALLOWED_STATES = setOf("pending", "success", "failure", "error")

        private val PARAM_REGEX = Regex("%([^%]+)%")
        private val OBJECT_MAPPER = ObjectMapper()
    }
}
