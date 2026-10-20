package org.octopusden.octopus.teamcityautomationservice.client.impl

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import feign.Feign
import feign.Logger
import feign.Request
import feign.Retryer
import feign.httpclient.ApacheHttpClient
import feign.jackson.JacksonDecoder
import feign.jackson.JacksonEncoder
import feign.slf4j.Slf4jLogger
import org.octopusden.octopus.teamcityautomationservice.client.TeamcityAutomationServiceClient
import org.octopusden.octopus.teamcityautomationservice.client.TeamcityAutomationServiceErrorDecoder
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.BuildChainDTO
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.CreateBuildChainRequest
import java.util.concurrent.TimeUnit

class ClassicTeamcityAutomationServiceClient(
    apiParametersProvider: TeamcityAutomationServiceClientParametersProvider,
    mapper: ObjectMapper,
) : TeamcityAutomationServiceClient {
    private val client =
        createClient(
            apiParametersProvider.getApiUrl(),
            mapper,
            apiParametersProvider.getConnectTimeoutInMillis(),
            apiParametersProvider.getReadTimeoutInMillis(),
        )

    constructor(apiParametersProvider: TeamcityAutomationServiceClientParametersProvider) : this(
        apiParametersProvider,
        getMapper(),
    )

    override fun createBuildChain(request: CreateBuildChainRequest): BuildChainDTO = client.createBuildChain(request)

    companion object {
        private fun getMapper(): ObjectMapper =
            with(jacksonObjectMapper()) {
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }

        private fun createClient(
            apiUrl: String,
            objectMapper: ObjectMapper,
            connectTimeoutInMillis: Int,
            readTimeoutInMillis: Int,
        ): TeamcityAutomationServiceClient =
            Feign
                .builder()
                .client(ApacheHttpClient())
                .options(
                    Request.Options(
                        connectTimeoutInMillis.toLong(),
                        TimeUnit.MILLISECONDS,
                        readTimeoutInMillis.toLong(),
                        TimeUnit.MILLISECONDS,
                        true,
                    ),
                ).encoder(JacksonEncoder(objectMapper))
                .decoder(JacksonDecoder(objectMapper))
                .errorDecoder(TeamcityAutomationServiceErrorDecoder(objectMapper))
                // Creating a chain is not idempotent: a retry after a timeout would answer 409.
                .retryer(Retryer.NEVER_RETRY)
                .logger(Slf4jLogger(TeamcityAutomationServiceClient::class.java))
                // Not FULL: the request body carries the caller's TeamCity password.
                .logLevel(Logger.Level.BASIC)
                .target(TeamcityAutomationServiceClient::class.java, apiUrl)
    }
}
