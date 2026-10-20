package org.octopusden.octopus.teamcityautomationservice.config

import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainCreator
import org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.TeamcityConnection

/** Builds the clients from each request: the service holds no credentials of its own. */
interface BuildChainClientFactory {
    fun createTeamcityClient(connection: TeamcityConnection): TeamcityClient

    fun createComponentsRegistryClient(url: String): ComponentsRegistryServiceClient

    fun createBuildChainCreator(
        teamcityClient: TeamcityClient,
        componentsRegistryClient: ComponentsRegistryServiceClient,
    ): BuildChainCreator
}
