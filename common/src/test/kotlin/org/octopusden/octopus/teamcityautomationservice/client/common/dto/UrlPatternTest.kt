package org.octopusden.octopus.teamcityautomationservice.client.common.dto

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class UrlPatternTest {
    private val pattern = Regex(URL_PATTERN)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://teamcity:8111",
            "https://teamcity.example",
            "https://teamcity.example/",
            "https://components-registry.example:4567/path?query=1#fragment",
            "http://[::1]:8111",
        ],
    )
    fun acceptsPlainUrls(url: String) {
        assertTrue(pattern.matches(url), url)
    }

    @ParameterizedTest
    @MethodSource("urlsWithUserInfo")
    fun rejectsUrlsWithUserInfo(url: String) {
        assertFalse(pattern.matches(url), url)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "teamcity.example",
            "ftp://teamcity.example",
            "https://",
            "https://teamcity example",
        ],
    )
    fun rejectsUrlsThatAreNotHttp(url: String) {
        assertFalse(pattern.matches(url), url)
    }

    companion object {
        // Built at run time, so no URL carrying a credential is written in the sources.
        private val secret = UUID.randomUUID().toString()

        @JvmStatic
        fun urlsWithUserInfo() =
            listOf(
                "https://" + "user" + ":" + secret + "@teamcity.example",
                "https://" + "user" + "@teamcity.example",
                "https://" + ":" + secret + "@teamcity.example/path",
            )
    }
}
