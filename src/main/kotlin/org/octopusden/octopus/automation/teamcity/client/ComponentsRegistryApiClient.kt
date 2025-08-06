package org.octopusden.octopus.automation.teamcity.client

import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import org.octopusden.octopus.components.registry.core.dto.ComponentV1
import org.octopusden.octopus.components.registry.core.dto.ComponentV3

class ComponentsRegistryApiClient(
    private val baseUrl: String
) {
    private val client = ClassicComponentsRegistryServiceClient(
        object : ClassicComponentsRegistryServiceClientUrlProvider {
            override fun getApiUrl() = baseUrl
        }
    )

    fun getComponent(componentName: String): ComponentV1 {
        return client.getById(componentName)
    }

    fun getNotArchivedComponents(): Collection<ComponentV3> {
        return client.getComponents().filter { it.component.name == null || it.component.name?.trim()?.endsWith(ARCHIVE_TAG) != true}
    }

    companion object {
        const val ARCHIVE_TAG = "(archived)"
    }
}