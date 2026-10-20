package org.octopusden.octopus.teamcityautomationservice.client

import feign.Headers
import feign.RequestLine
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.BuildChainDTO
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.CreateBuildChainRequest

interface TeamcityAutomationServiceClient {
    @RequestLine("POST /rest/api/1/build-chains")
    @Headers("Content-Type: application/json")
    fun createBuildChain(request: CreateBuildChainRequest): BuildChainDTO
}
