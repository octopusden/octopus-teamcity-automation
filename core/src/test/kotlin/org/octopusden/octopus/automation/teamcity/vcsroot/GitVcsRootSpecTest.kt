package org.octopusden.octopus.automation.teamcity.vcsroot

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/** Pins exactly what each command creates today, so unifying them is a deliberate change. */
class GitVcsRootSpecTest {
    private val shared = mapOf(
        "url" to "git@host:org/repo.git",
        "branch" to "refs/heads/main",
        "authMethod" to "PRIVATE_KEY_DEFAULT",
        "username" to "git",
        "ignoreKnownHosts" to "true",
    )

    private fun GitVcsRootSpec.asMap() = properties("git@host:org/repo.git", "refs/heads/main").properties.associate { it.name to it.value }

    @Test
    fun buildChainWatchesTheDefaultBranchAndTagsAsTcagent() {
        Assertions.assertEquals(
            shared + mapOf("teamcity:branchSpec" to "+:<default>", "userForTags" to "tcagent"),
            GitVcsRootSpec.BUILD_CHAIN.asMap(),
        )
    }

    @Test
    fun replacementWatchesEveryBranchWithTheCheckoutSettingsItAlwaysHad() {
        Assertions.assertEquals(
            shared +
                mapOf(
                    "teamcity:branchSpec" to "+:refs/heads/*",
                    "usernameStyle" to "USERID",
                    "submoduleCheckout" to "IGNORE",
                    "agentCleanFilesPolicy" to "ALL_UNTRACKED",
                    "agentCleanPolicy" to "ON_BRANCH_CHANGE",
                ),
            GitVcsRootSpec.REPLACEMENT.asMap(),
        )
    }
}
