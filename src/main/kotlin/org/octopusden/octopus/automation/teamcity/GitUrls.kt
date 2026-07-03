package org.octopusden.octopus.automation.teamcity

import java.net.URI

/**
 * Extracts the path portion of a Git URL.
 *
 * Supports:
 * - `ssh://git@host/org/repo.git`
 * - `https://host/org/repo.git`
 * - scp-like `git@host:org/repo.git`
 */
fun extractPathFromGitUrl(vcsUrl: String): String {
    return when {
        vcsUrl.startsWith("ssh://", ignoreCase = true) || vcsUrl.startsWith("https://", ignoreCase = true) -> {
            URI(vcsUrl).path.removePrefix("/")
        }
        else -> {
            vcsUrl.substring(vcsUrl.indexOf(':') + 1)
        }
    }
}

/**
 * Parses the owner (organization) and repository name from a Git URL.
 *
 * Case is preserved because GitHub repository names are case-sensitive.
 *
 * @return a [Pair] of `owner` to `repo` (without the `.git` suffix)
 * @throws IllegalArgumentException if the URL does not contain an owner and repository segment
 */
fun parseOwnerRepo(vcsUrl: String): Pair<String, String> {
    val segments = extractPathFromGitUrl(vcsUrl)
        .removeSuffix(".git")
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() }
    require(segments.size >= 2) {
        "Cannot parse owner/repo from Git URL '$vcsUrl'"
    }
    val owner = segments[segments.size - 2]
    val repo = segments[segments.size - 1]
    return owner to repo
}
