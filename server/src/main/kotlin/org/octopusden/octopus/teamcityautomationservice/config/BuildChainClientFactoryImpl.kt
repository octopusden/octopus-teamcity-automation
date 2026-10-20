package org.octopusden.octopus.teamcityautomationservice.config

import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainCreator
import org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.TeamcityConnection
import org.springframework.stereotype.Component

/** The same construction as the CLI's `create-build-chain`. */
@Component
class BuildChainClientFactoryImpl : BuildChainClientFactory {
    override fun createTeamcityClient(connection: TeamcityConnection): TeamcityClient =
        TeamcityClassicClient(
            object : ClientParametersProvider {
                override fun getApiUrl() = connection.url

                override fun getAuth() = StandardBasicCredCredentialProvider(connection.user, connection.password)
            },
        )

    override fun createComponentsRegistryClient(url: String): ComponentsRegistryServiceClient =
        ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl(): String = url
            },
        )

    override fun createBuildChainCreator(
        teamcityClient: TeamcityClient,
        componentsRegistryClient: ComponentsRegistryServiceClient,
    ) = BuildChainCreator(teamcityClient, componentsRegistryClient)
}
