package org.octopusden.octopus.automation.teamcity.client

import org.octopusden.octopus.vcsfacade.client.common.dto.CreatePullRequest
import org.octopusden.octopus.vcsfacade.client.common.dto.PullRequest
import org.octopusden.octopus.vcsfacade.client.impl.ClassicVcsFacadeClient
import org.octopusden.octopus.vcsfacade.client.impl.VcsFacadeClientParametersProvider

class VcsFacadeApiClient(private val baseUrl: String) {
    private val client = ClassicVcsFacadeClient(
        object : VcsFacadeClientParametersProvider {
            override fun getApiUrl() = baseUrl
            override fun getTimeRetryInMillis() = 180000
        }
    )

    fun createPullRequest(repositoryUrl: String, sourceBranch: String, targetBranch: String, title: String, description: String): PullRequest {
        val pullRequestDto = CreatePullRequest(sourceBranch, targetBranch, title, description)
        return client.createPullRequest(repositoryUrl, pullRequestDto)
    }
}