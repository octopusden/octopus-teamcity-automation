package org.octopusden.octopus.automation.teamcity.client

import org.octopusden.octopus.dms.client.impl.ClassicDmsServiceClient
import org.octopusden.octopus.dms.client.impl.DmsServiceClientParametersProvider

class DmsApiClient (
    private val baseUrl: String,
    private val username: String,
    private val password: String
) {
    private val client = ClassicDmsServiceClient(
        object : DmsServiceClientParametersProvider {
            override fun getApiUrl(): String = baseUrl
            override fun getBasicCredentials(): String? = "$username:$password"
            override fun getBearerToken(): String? = null
        }
    )

    fun renameComponent(componentName: String, componentNewName: String) {
        client.renameComponent(componentName, componentNewName)
    }
}