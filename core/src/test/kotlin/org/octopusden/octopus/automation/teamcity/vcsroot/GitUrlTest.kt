package org.octopusden.octopus.automation.teamcity.vcsroot

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class GitUrlTest {
    @Test
    fun acceptsLowercaseSshScpAndHttpsUrls() {
        listOf(
            "ssh://git@host.example/org/repo.git",
            "git@github.com:org/repo.git",
            "https://host.example/org/sub/repo.git",
        ).forEach { Assertions.assertTrue(GitUrl.isValid(it), it) }
    }

    @Test
    fun rejectsUppercaseMissingSuffixAndOtherSchemes() {
        listOf(
            "ssh://git@host.example/Org/repo.git",
            "https://host.example/org/repo",
            "http://host.example/org/repo.git",
            "svn://host.example/org/repo.git",
            "",
        ).forEach { Assertions.assertFalse(GitUrl.isValid(it), it) }
    }

    @Test
    fun pathIsWhatFollowsTheHost() {
        Assertions.assertEquals("org/repo.git", GitUrl.path("ssh://git@host.example/org/repo.git"))
        Assertions.assertEquals("org/sub/repo.git", GitUrl.path("https://host.example/org/sub/repo.git"))
        Assertions.assertEquals("org/repo.git", GitUrl.path("git@github.com:org/repo.git"))
    }
}
