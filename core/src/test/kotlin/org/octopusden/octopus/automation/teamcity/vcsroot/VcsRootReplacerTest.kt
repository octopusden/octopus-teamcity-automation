package org.octopusden.octopus.automation.teamcity.vcsroot

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class VcsRootReplacerTest {
    @Test
    fun requestRejectsAnInvalidUrl() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            VcsRootReplaceRequest("https://host.example/org/repo", "git@host:org/new.git", dryRun = true)
        }
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            VcsRootReplaceRequest("git@host:org/old.git", "git@host:Org/New.git", dryRun = true)
        }
    }

    @Test
    fun isValidGitUrlIsTheSameRuleAsTheRequest() {
        Assertions.assertTrue(VcsRootReplacer.isValidGitUrl("git@github.com:org/repo.git"))
        Assertions.assertFalse(VcsRootReplacer.isValidGitUrl("git@github.com:Org/repo.git"))
    }
}
