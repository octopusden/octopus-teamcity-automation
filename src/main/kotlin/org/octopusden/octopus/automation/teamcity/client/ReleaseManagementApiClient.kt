package org.octopusden.octopus.automation.teamcity.client

import org.octopusden.octopus.releasemanagementservice.client.common.dto.ComponentDTO
import org.octopusden.octopus.releasemanagementservice.client.impl.ClassicReleaseManagementServiceClient
import org.octopusden.octopus.releasemanagementservice.client.impl.ReleaseManagementServiceClientParametersProvider

class ReleaseManagementApiClient(
    private val baseUrl: String
) {
    private val client = ClassicReleaseManagementServiceClient(
        object : ReleaseManagementServiceClientParametersProvider {
            override fun getApiUrl(): String = baseUrl
            override fun getTimeRetryInMillis(): Int = 30000
        }
    )

    fun renameComponent(componentName: String, componentNewName: String) {
        client.updateComponent(componentName, ComponentDTO(componentNewName))
    }
}