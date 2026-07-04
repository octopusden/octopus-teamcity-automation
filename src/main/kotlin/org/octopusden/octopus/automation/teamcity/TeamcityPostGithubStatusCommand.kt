package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.kohsuke.github.GHCommitState
import org.kohsuke.github.GitHubBuilder
import org.slf4j.Logger

/**
 * Posts a commit status to GitHub (`POST /repos/{owner}/{repo}/statuses/{sha}`) so TeamCity
 * builds can gate GitHub branch protection rules.
 *
 * The GitHub organization and repository are passed as arguments (typically wired to TeamCity
 * parameters in the metarunner), so no VCS root lookup is required and multiple VCS roots are
 * not an issue.
 */
class TeamcityPostGithubStatusCommand : CliktCommand(name = COMMAND) {

    private val owner by option(OWNER, help = "GitHub organization / owner")
        .convert { it.trim() }.required()
        .check("$OWNER is empty") { it.isNotEmpty() }

    private val repo by option(REPO, help = "GitHub repository name")
        .convert { it.trim() }.required()
        .check("$REPO is empty") { it.isNotEmpty() }

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

    private val githubApiUrl by option(GITHUB_API_URL, help = "GitHub API base URL")
        .convert { it.trim().trimEnd('/') }.default(DEFAULT_GITHUB_API_URL)

    private val context by requireObject<MutableMap<String, Any>>()

    private val log by lazy { context[TeamcityCommand.LOG] as Logger }

    override fun run() {
        log.info("Executing $COMMAND")
        postStatus()
    }

    private fun postStatus() {
        log.info("Posting GitHub commit status '$state' (context '$statusContext') to $owner/$repo@$commit")
        val github = GitHubBuilder()
            .withEndpoint(githubApiUrl)
            .withOAuthToken(token)
            .build()
        github.getRepository("$owner/$repo").createCommitStatus(
            commit,
            GHCommitState.valueOf(state.uppercase()),
            null,
            description.ifEmpty { null },
            statusContext,
        )
        log.info("GitHub commit status posted")
    }

    companion object {
        const val COMMAND = "post-github-status"
        const val OWNER = "--owner"
        const val REPO = "--repo"
        const val COMMIT = "--commit"
        const val TOKEN = "--token"
        const val STATE = "--state"
        const val CONTEXT = "--context"
        const val DESCRIPTION = "--description"
        const val GITHUB_API_URL = "--github-api-url"

        const val DEFAULT_CONTEXT = "TeamCity / build"
        const val DEFAULT_GITHUB_API_URL = "https://api.github.com"
        val ALLOWED_STATES = setOf("pending", "success", "failure", "error")
    }
}
