package org.octopusden.octopus.automation.teamcity.buildchain

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class BuildChainConfigTest {
    @Test
    fun defaultCompileTemplates() {
        val config = BuildChainConfig()
        Assertions.assertEquals("CDCompileUTGradle" to "CDCompileUTMaven", config.gradleCompileTemplate to config.mavenCompileTemplate)
    }
}
