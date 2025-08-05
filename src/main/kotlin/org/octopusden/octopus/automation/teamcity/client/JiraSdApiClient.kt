package org.octopusden.octopus.automation.teamcity.client

import com.atlassian.jira.rest.client.api.JiraRestClient
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory
import org.slf4j.LoggerFactory
import java.net.URI

class JiraSdApiClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String
) {
    private val client: JiraRestClient =
        AsynchronousJiraRestClientFactory().createWithBasicHttpAuthentication(URI(baseUrl), username, password)

    fun createSdIssue(summary: String, description: String): String {
        val builder = IssueInputBuilder()
            .setProjectKey(SD_PRJ_KEY)
            .setIssueTypeId(SD_ISSUE_TYPE_ID)
            .setSummary(summary)
            .setDescription(description)
        val input = builder.build()
        return client.issueClient.createIssue(input).claim().key
    }

    fun updateIssueDescription(issueKey: String, newDescription: String) {
        client.issueClient.updateIssue(issueKey, IssueInputBuilder().setDescription(newDescription).build()).claim()
    }

    companion object {
        private const val SD_ISSUE_TYPE_ID = 10003L
        private const val SD_PRJ_KEY = "SD"
        private val log = LoggerFactory.getLogger(JiraSdApiClient::class.java)
    }
}