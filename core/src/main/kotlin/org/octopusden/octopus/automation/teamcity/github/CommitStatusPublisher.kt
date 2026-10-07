package org.octopusden.octopus.automation.teamcity.github

import okhttp3.OkHttpClient
import org.kohsuke.github.GHCommitState
import org.kohsuke.github.GitHubBuilder
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Posts commit statuses to GitHub (`POST /repos/{owner}/{repo}/statuses/{sha}`), so TeamCity builds
 * can gate GitHub branch protection rules.
 */
class CommitStatusPublisher(
    private val token: String,
    private val apiUrl: String = DEFAULT_API_URL,
    private val connectTimeout: Duration = DEFAULT_TIMEOUT,
    private val readTimeout: Duration = DEFAULT_TIMEOUT,
) {
    private val log = LoggerFactory.getLogger(CommitStatusPublisher::class.java)

    init {
        require(token.isNotBlank()) { "token is blank" }
    }

    fun post(status: CommitStatus) {
        log.info(
            "Posting GitHub commit status '${status.state.name.lowercase()}' (context '${status.context}') " +
                "to ${status.owner}/${status.repo}@${status.commit}",
        )
        val github = GitHubBuilder()
            .withEndpoint(apiUrl.trimEnd('/'))
            .withOAuthToken(token)
            .withConnector(
                OkHttpGitHubConnector(
                    OkHttpClient
                        .Builder()
                        .connectTimeout(connectTimeout)
                        .readTimeout(readTimeout)
                        .build(),
                ),
            ).build()
        github.getRepository("${status.owner}/${status.repo}").createCommitStatus(
            status.commit,
            GHCommitState.valueOf(status.state.name),
            null,
            status.description?.ifEmpty { null },
            status.context,
        )
        log.info("GitHub commit status posted")
    }

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com"
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
