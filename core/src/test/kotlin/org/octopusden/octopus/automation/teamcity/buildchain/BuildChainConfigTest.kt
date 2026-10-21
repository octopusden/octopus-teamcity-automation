package org.octopusden.octopus.automation.teamcity.buildchain

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class BuildChainConfigTest {
    /**
     * The default compile templates are the ids every installation has. A server that renamed them
     * still resolves these ids; one that never did knows no other.
     */
    @Test
    fun defaultCompileTemplatesAreTheOriginalIds() {
        Assertions.assertEquals("CDCompileUTGradle", BuildChainConfig().gradleCompileTemplate)
        Assertions.assertEquals("CDCompileUTMaven", BuildChainConfig().mavenCompileTemplate)
    }
}
