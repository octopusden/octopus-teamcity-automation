package org.octopusden.octopus.teamcityautomationservice.service

import org.octopusden.octopus.teamcityautomationservice.client.common.dto.BuildChainDTO
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.CreateBuildChainRequest

interface BuildChainService {
    fun createBuildChain(request: CreateBuildChainRequest): BuildChainDTO
}
