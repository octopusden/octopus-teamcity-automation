package org.octopusden.octopus.teamcityautomationservice.controller

import jakarta.validation.Valid
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.BuildChainDTO
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.CreateBuildChainRequest
import org.octopusden.octopus.teamcityautomationservice.service.BuildChainService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("rest/api/1/build-chains")
class BuildChainController(
    private val buildChainService: BuildChainService,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun createBuildChain(
        @Valid @RequestBody request: CreateBuildChainRequest,
    ): BuildChainDTO = buildChainService.createBuildChain(request)
}
