package org.octopusden.octopus.automation.teamcity.buildchain

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class BuildChainConfigTest {
    @Test
    fun defaultCompileTemplates() {
        val config = BuildChainConfig()
        Assertions.assertEquals("CDCompileUTGradle", config.gradleCompileTemplate)
        Assertions.assertEquals("CDCompileUTMaven", config.mavenCompileTemplate)
    }
}
