package org.octopusden.octopus.automation.teamcity.vcsroot

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class BranchRefTest {
    @Test
    fun defaultsToMasterWhenUnset() {
        Assertions.assertEquals("refs/heads/master", branchRef(null))
        Assertions.assertEquals("refs/heads/master", branchRef(""))
        Assertions.assertEquals("refs/heads/master", branchRef("   "))
    }

    @Test
    fun prefixesABranchNameAndKeepsAFullRef() {
        Assertions.assertEquals("refs/heads/release/1.2", branchRef(" release/1.2 "))
        Assertions.assertEquals("refs/tags/v1.0", branchRef("refs/tags/v1.0"))
    }
}
