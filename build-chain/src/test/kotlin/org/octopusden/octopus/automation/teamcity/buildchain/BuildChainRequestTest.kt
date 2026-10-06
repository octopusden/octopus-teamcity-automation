package org.octopusden.octopus.automation.teamcity.buildchain

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class BuildChainRequestTest {
    @Test
    fun rejectsBlankValues() {
        Assertions.assertThrows(IllegalArgumentException::class.java) { BuildChainRequest(" ", "component", "1.0") }
        Assertions.assertThrows(IllegalArgumentException::class.java) { BuildChainRequest("Parent", "", "1.0") }
        Assertions.assertThrows(IllegalArgumentException::class.java) { BuildChainRequest("Parent", "component", "\t") }
    }

    /** The CLI trims its options; a library caller must not get a TeamCity project named "component ". */
    @Test
    fun rejectsSurroundingWhitespace() {
        val e = Assertions.assertThrows(IllegalArgumentException::class.java) { BuildChainRequest("Parent", "component ", "1.0") }
        Assertions.assertEquals("componentName has leading or trailing whitespace: 'component '", e.message)
        Assertions.assertThrows(IllegalArgumentException::class.java) { BuildChainRequest(" Parent", "component", "1.0") }
        Assertions.assertThrows(IllegalArgumentException::class.java) { BuildChainRequest("Parent", "component", "1.0\n") }
    }

    @Test
    fun acceptsTrimmedValues() {
        Assertions.assertEquals("component", BuildChainRequest("Parent", "component", "1.0").componentName)
    }
}
