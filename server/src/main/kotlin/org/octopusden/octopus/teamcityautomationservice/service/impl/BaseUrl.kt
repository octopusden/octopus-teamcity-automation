package org.octopusden.octopus.teamcityautomationservice.service.impl

import java.net.URI
import java.net.URISyntaxException

/**
 * A server's base URL. Another URL is under it when scheme, host and effective port match and its path
 * starts with the base path at a segment boundary, so `http://host/tc` is not under `http://host/t`.
 */
internal class BaseUrl private constructor(
    private val scheme: String,
    private val host: String,
    private val port: Int,
    private val segments: List<String>,
) {
    /** How specific the base is: the longer of two matching bases names the server. */
    val depth get() = segments.size

    fun contains(url: String): Boolean =
        parse(url)?.let { other ->
            other.scheme == scheme &&
                other.host == host &&
                other.port == port &&
                other.segments.size >= segments.size &&
                other.segments.subList(0, segments.size) == segments
        } ?: false

    companion object {
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443

        // Not a URL at all: it is under no base, and no base can be built from it.
        @Suppress("SwallowedException")
        fun parse(url: String): BaseUrl? =
            try {
                val uri = URI(url.trim()).normalize()
                val scheme = uri.scheme?.lowercase()
                val host = uri.host?.lowercase()
                if (scheme == null || host == null) {
                    null
                } else {
                    val port = if (uri.port != -1) {
                        uri.port
                    } else if (scheme == "https") {
                        HTTPS_PORT
                    } else {
                        HTTP_PORT
                    }
                    BaseUrl(
                        scheme,
                        host,
                        port,
                        uri.path
                            .orEmpty()
                            .split('/')
                            .filter { it.isNotEmpty() },
                    )
                }
            } catch (e: URISyntaxException) {
                null
            }
    }
}
