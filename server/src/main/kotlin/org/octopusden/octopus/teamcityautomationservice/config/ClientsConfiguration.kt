package org.octopusden.octopus.teamcityautomationservice.config

import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainCreator
import org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The same construction as the CLI's `create-build-chain`, done once from the configured servers and account. */
@Configuration
class ClientsConfiguration {
    @Bean
    fun teamcityClient(properties: TeamcityAutomationServiceProperties): TeamcityClient =
        TeamcityClassicClient(
            object : ClientParametersProvider {
                override fun getApiUrl() = properties.teamcity.url

                override fun getAuth() = StandardBasicCredCredentialProvider(properties.teamcity.username, properties.teamcity.password)
            },
        )

    @Bean
    fun componentsRegistryClient(properties: TeamcityAutomationServiceProperties): ComponentsRegistryServiceClient =
        ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl(): String = properties.componentsRegistry.url
            },
        )

    @Bean
    fun buildChainCreator(
        teamcityClient: TeamcityClient,
        componentsRegistryClient: ComponentsRegistryServiceClient,
    ) = BuildChainCreator(teamcityClient, componentsRegistryClient)
}
