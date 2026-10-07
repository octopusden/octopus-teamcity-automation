package org.octopusden.octopus.automation.teamcity.vcsroot

import java.net.URI

/** Git repository URL rules, with no TeamCity calls. */
internal object GitUrl {
    private val baseSshScheme = Regex("""^ssh://[\w.-]+@[\w.-]+/[\w./~\-+%]+(\.git)$""")
    private val scpLikeScheme = Regex("""^[\w.-]+@[\w.-]+:[\w./~\-+%]+(\.git)$""")
    private val httpsScheme = Regex("""^https://[\w.-]+/[\w./~\-+%]+(\.git)$""")

    /** A lowercase `ssh://user@host/path.git`, `user@host:path.git` or `https://host/path.git` URL. */
    fun isValid(url: String) =
        url == url.lowercase() &&
            (baseSshScheme.matches(url) || scpLikeScheme.matches(url) || httpsScheme.matches(url))

    /** The repository path: `org/repo.git` for `ssh://git@host/org/repo.git`, `git@host:org/repo.git` or `https://host/org/repo.git`. */
    fun path(url: String): String =
        if (url.startsWith("ssh://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            URI(url).path.removePrefix("/")
        } else {
            url.substring(url.indexOf(':') + 1)
        }
}
