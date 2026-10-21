package org.octopusden.octopus.teamcityautomationservice.client.impl

interface TeamcityAutomationServiceClientParametersProvider {
    fun getApiUrl(): String

    fun getConnectTimeoutInMillis(): Int

    /** Creating a chain makes a few dozen TeamCity calls, so leave room for a slow server. */
    fun getReadTimeoutInMillis(): Int
}
