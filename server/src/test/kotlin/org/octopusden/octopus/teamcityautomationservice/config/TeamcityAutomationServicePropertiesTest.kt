package org.octopusden.octopus.teamcityautomationservice.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.util.UUID

class TeamcityAutomationServicePropertiesTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration::class.java)

    @Test
    fun bindsTheConfiguredServersAndAccount() {
        runner.withPropertyValues(*valid()).run { context ->
            val properties = context.getBean(TeamcityAutomationServiceProperties::class.java)

            assertNull(context.startupFailure)
            assertEquals("https://teamcity.example/tc", properties.teamcity.url)
            assertEquals("account", properties.teamcity.username)
            assertEquals(PASSWORD, properties.teamcity.password)
            assertEquals("http://registry:4567", properties.componentsRegistry.url)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "teamcity-automation-service.teamcity.url",
            "teamcity-automation-service.teamcity.username",
            "teamcity-automation-service.teamcity.password",
            "teamcity-automation-service.components-registry.url",
        ],
    )
    fun refusesToStartWhenAValueIsBlankAndNamesIt(property: String) {
        runner.withPropertyValues(*valid(), "$property= ").run { context ->
            assertNamed(context.startupFailure, property)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "teamcity-automation-service.teamcity.url",
            "teamcity-automation-service.teamcity.username",
            "teamcity-automation-service.teamcity.password",
            "teamcity-automation-service.components-registry.url",
        ],
    )
    fun refusesToStartWhenAValueIsMissingAndNamesIt(property: String) {
        runner.withPropertyValues(*valid().filterNot { it.startsWith("$property=") }.toTypedArray()).run { context ->
            assertNamed(context.startupFailure, property)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["teamcity.example", "ftp://teamcity.example", "https://", "https://teamcity example"])
    fun refusesUrlsThatAreNotHttp(url: String) {
        runner.withPropertyValues(*valid(), "teamcity-automation-service.teamcity.url=$url").run { context ->
            assertNamed(context.startupFailure, "teamcity-automation-service.teamcity.url")
        }
    }

    @Test
    fun refusesAUrlWithUserInfoWithoutEchoingIt() {
        val secret = UUID.randomUUID().toString()
        val url = "https://" + "user" + ":" + secret + "@teamcity.example"

        runner.withPropertyValues(*valid(), "teamcity-automation-service.components-registry.url=$url").run { context ->
            val failure = context.startupFailure

            assertNamed(failure, "teamcity-automation-service.components-registry.url")
            assertFalse(messages(failure).contains(secret))
        }
    }

    @Test
    fun masksThePasswordWhereverTheAccountIsPrinted() {
        val teamcity = TeamcityAutomationServiceProperties.Teamcity("http://teamcity", "account", PASSWORD)

        assertFalse(teamcity.toString().contains(PASSWORD))
    }

    private fun assertNamed(
        failure: Throwable?,
        property: String,
    ) {
        assertTrue(failure != null, "the context must fail to start")
        assertTrue(messages(failure).contains(property), messages(failure))
    }

    private fun messages(failure: Throwable?) = generateSequence(failure) { it.cause }.joinToString("\n") { it.message.orEmpty() }

    private fun valid() =
        arrayOf(
            "teamcity-automation-service.teamcity.url=https://teamcity.example/tc",
            "teamcity-automation-service.teamcity.username=account",
            "teamcity-automation-service.teamcity.password=$PASSWORD",
            "teamcity-automation-service.components-registry.url=http://registry:4567",
        )

    @Configuration
    @EnableConfigurationProperties(TeamcityAutomationServiceProperties::class)
    class PropertiesConfiguration

    companion object {
        // Generated per run: no credential, not even a dummy one, is written in the sources.
        private val PASSWORD = UUID.randomUUID().toString()
    }
}
