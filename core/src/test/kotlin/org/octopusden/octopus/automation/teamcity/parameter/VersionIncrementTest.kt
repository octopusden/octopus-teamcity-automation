package org.octopusden.octopus.automation.teamcity.parameter

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.octopusden.octopus.automation.teamcity.parameter.VersionIncrement.Outcome

class VersionIncrementTest {
    @Test
    fun incrementsTheLastComponentWhateverTheDelimiter() {
        Assertions.assertEquals(Outcome.Incremented("1.3"), VersionIncrement.next("1.2", ""))
        Assertions.assertEquals(Outcome.Incremented("1.2-8"), VersionIncrement.next("1.2-7", ""))
        Assertions.assertEquals(Outcome.Incremented("10"), VersionIncrement.next("9", ""))
        Assertions.assertEquals(Outcome.Incremented("1.2.100"), VersionIncrement.next("1.2.99", ""))
    }

    @Test
    fun incrementsOnlyWhenCurrentStartsWithTheValue() {
        Assertions.assertEquals(Outcome.Incremented("1.3"), VersionIncrement.next("1.2", "1.2.7"))
        Assertions.assertEquals(Outcome.Incremented("1.3"), VersionIncrement.next("1.2", "1.2"))
        Assertions.assertEquals(Outcome.Skipped("1.2.7 does not contain components of 1.3"), VersionIncrement.next("1.3", "1.2.7"))
        Assertions.assertEquals(Outcome.Skipped("1.2 does not contain components of 1.2.7"), VersionIncrement.next("1.2.7", "1.2"))
    }

    @Test
    fun skipsANonNumericLastComponentWithTheCause() {
        val outcome = VersionIncrement.next("1.x", "")

        Assertions.assertTrue(outcome is Outcome.Skipped)
        outcome as Outcome.Skipped
        Assertions.assertEquals("Unable to increment last component x of 1.x", outcome.reason)
        Assertions.assertTrue(outcome.cause is NumberFormatException)
    }
}
