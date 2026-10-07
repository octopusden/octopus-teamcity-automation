package org.octopusden.octopus.automation.teamcity.vcsroot

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class VcsRootNamingTest {
    @Test
    fun capitalisesPathSegmentsAndDashSeparatedWords() {
        Assertions.assertEquals(
            "Octopusden_Octopus_Teamcity_Automation_x",
            VcsRootNaming.nameFor("git@github.com:octopusden/octopus-teamcity-automation.git", "x"),
        )
        Assertions.assertEquals("Org_Sub_Repo_x", VcsRootNaming.nameFor("https://host.example/org/sub/repo.git", "x"))
        Assertions.assertEquals("Proj_App_x", VcsRootNaming.nameFor("ssh://git@host.example/proj/app.git", "x"))
    }

    @Test
    fun dropsEmptyWords() {
        Assertions.assertEquals("Org_Repo_x", VcsRootNaming.nameFor("git@host:org/-repo--.git", "x"))
    }

    @Test
    fun suffixesARandomUuidByDefault() {
        val name = VcsRootNaming.nameFor("git@host:org/repo.git")

        Assertions.assertTrue(name.matches(Regex("Org_Repo_[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")), name)
        Assertions.assertNotEquals(name, VcsRootNaming.nameFor("git@host:org/repo.git"))
    }
}
