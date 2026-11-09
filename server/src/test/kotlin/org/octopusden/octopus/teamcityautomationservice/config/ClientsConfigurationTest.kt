package org.octopusden.octopus.teamcityautomationservice.config

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainCreator
import org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

class ClientsConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withUserConfiguration(Properties::class.java)
        .withPropertyValues(
            "teamcity-automation-service.teamcity.url=http://teamcity:8111",
            "teamcity-automation-service.teamcity.username=account",
            "teamcity-automation-service.teamcity.password=secret",
            "teamcity-automation-service.components-registry.url=http://registry:4567",
        )

    @Test
    fun buildsTheClientsAndTheCreatorOnce() {
        runner.run { context ->
            assertNull(context.startupFailure)
            val creator = context.getBean(BuildChainCreator::class.java)

            assertSame(creator, context.getBean(BuildChainCreator::class.java))
            assertSame(context.getBean(TeamcityClient::class.java), context.getBean(TeamcityClient::class.java))
            context.getBean(ComponentsRegistryServiceClient::class.java)
        }
    }

    @Configuration
    @EnableConfigurationProperties(TeamcityAutomationServiceProperties::class)
    @Import(ClientsConfiguration::class)
    class Properties
}
