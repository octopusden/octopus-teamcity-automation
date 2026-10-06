package org.octopusden.octopus.automation.teamcity.vcsroot

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.octopusden.octopus.automation.teamcity.RecordingTeamcityClient

class VcsRootReplacerTest {
    @Test
    fun acceptsLowercaseSshScpAndHttpsGitUrls() {
        listOf(
            "ssh://git@host.example/org/repo.git",
            "git@github.com:org/repo.git",
            "https://host.example/org/sub/repo.git",
        ).forEach { Assertions.assertTrue(VcsRootReplacer.isValidGitUrl(it), it) }
    }

    @Test
    fun rejectsUppercaseMissingSuffixAndOtherSchemes() {
        listOf(
            "ssh://git@host.example/Org/repo.git",
            "https://host.example/org/repo",
            "http://host.example/org/repo.git",
            "svn://host.example/org/repo.git",
        ).forEach { Assertions.assertFalse(VcsRootReplacer.isValidGitUrl(it), it) }
    }

    @Test
    fun rejectsAnInvalidUrlBeforeCallingTeamcity() {
        val recorder = RecordingTeamcityClient()

        Assertions.assertThrows(IllegalArgumentException::class.java) {
            VcsRootReplacer(recorder.client).replace("https://host.example/org/repo", "git@host:org/new.git", dryRun = true)
        }
        Assertions.assertTrue(recorder.calls.isEmpty())
    }
}
