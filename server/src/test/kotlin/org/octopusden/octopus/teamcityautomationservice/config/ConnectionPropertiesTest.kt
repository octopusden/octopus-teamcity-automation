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

class ConnectionPropertiesTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration::class.java)

    @Test
    fun bindsTheConfiguredServersAndAccount() {
        runner.withPropertyValues(*valid()).run { context ->
            val teamcity = context.getBean(TeamcityProperties::class.java)
            val registry = context.getBean(ComponentsRegistryProperties::class.java)

            assertNull(context.startupFailure)
            assertEquals("https://teamcity.example/tc", teamcity.url)
            assertEquals("account", teamcity.username)
            assertEquals(PASSWORD, teamcity.password)
            assertEquals("http://registry:4567", registry.url)
        }
    }

    @Test
    fun ignoresSiblingKeysTheSharedConfigDefinesForOtherServices() {
        runner
            .withPropertyValues(
                *valid(),
                "teamcity.host=teamcity.example",
                "components-registry.vcs.enabled=true",
                "components-registry.vcs.url=http://vcs.example",
            ).run { context ->
                assertNull(context.startupFailure)
                assertEquals("https://teamcity.example/tc", context.getBean(TeamcityProperties::class.java).url)
                assertEquals("http://registry:4567", context.getBean(ComponentsRegistryProperties::class.java).url)
            }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "teamcity.url",
            "teamcity.username",
            "teamcity.password",
            "components-registry.url",
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
            "teamcity.url",
            "teamcity.username",
            "teamcity.password",
            "components-registry.url",
        ],
    )
    fun refusesToStartWhenAValueIsMissingAndNamesIt(property: String) {
        runner.withPropertyValues(*valid().filterNot { it.startsWith("$property=") }.toTypedArray()).run { context ->
            assertNamed(context.startupFailure, property)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["teamcity.example", "ftp://teamcity.example", "https://", "https://teamcity example", "http://localhost/%zz"])
    fun refusesUrlsThatAreNotHttp(url: String) {
        runner.withPropertyValues(*valid(), "teamcity.url=$url").run { context ->
            assertNamed(context.startupFailure, "teamcity.url")
        }
    }

    @Test
    fun refusesAUrlWithUserInfoWithoutEchoingIt() {
        val secret = UUID.randomUUID().toString()
        val url = "https://" + "user" + ":" + secret + "@teamcity.example"

        runner.withPropertyValues(*valid(), "components-registry.url=$url").run { context ->
            val failure = context.startupFailure

            assertNamed(failure, "components-registry.url")
            assertFalse(messages(failure).contains(secret))
        }
    }

    @Test
    fun masksThePasswordWhereverTheAccountIsPrinted() {
        val teamcity = TeamcityProperties("http://teamcity", "account", PASSWORD)

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
            "teamcity.url=https://teamcity.example/tc",
            "teamcity.username=account",
            "teamcity.password=$PASSWORD",
            "components-registry.url=http://registry:4567",
        )

    @Configuration
    @EnableConfigurationProperties(TeamcityProperties::class, ComponentsRegistryProperties::class)
    class PropertiesConfiguration

    companion object {
        // Generated per run: no credential, not even a dummy one, is written in the sources.
        private val PASSWORD = UUID.randomUUID().toString()
    }
}
