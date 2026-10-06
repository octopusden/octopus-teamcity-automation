package org.octopusden.octopus.automation.teamcity.parameter

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.octopusden.octopus.automation.teamcity.RecordingTeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType

class ParameterUpdaterTest {
    private fun clientWithValues(vararg values: Pair<String, String>): RecordingTeamcityClient {
        val byId = values.toMap()
        return RecordingTeamcityClient { method, args ->
            if (method == "getParameter") byId[args[1]] ?: error("no value for ${args[1]}") else null
        }
    }

    private fun RecordingTeamcityClient.written() = callsOf("setParameter").map { it.args }

    @Test
    fun setsValueOnEveryProjectAndBuildType() {
        val recorder = RecordingTeamcityClient()

        ParameterUpdater(recorder.client).set(ParameterTargets("P", setOf("Proj"), setOf("Build")), "v")

        Assertions.assertEquals(
            listOf(
                listOf(ConfigurationType.PROJECT, "Proj", "P", "v"),
                listOf(ConfigurationType.BUILD_TYPE, "Build", "P", "v"),
            ),
            recorder.written(),
        )
    }

    @Test
    fun incrementsLastNumericComponent() {
        val recorder = clientWithValues("A" to "1.2", "B" to "1.2-7", "C" to "9")

        ParameterUpdater(recorder.client).increment(ParameterTargets("P", setOf("A"), setOf("B", "C")))

        Assertions.assertEquals(
            listOf(
                listOf(ConfigurationType.PROJECT, "A", "P", "1.3"),
                listOf(ConfigurationType.BUILD_TYPE, "B", "P", "1.2-8"),
                listOf(ConfigurationType.BUILD_TYPE, "C", "P", "10"),
            ),
            recorder.written(),
        )
    }

    @Test
    fun incrementsOnlyWhereCurrentContainsTheValue() {
        val recorder = clientWithValues("Match" to "1.2", "Other" to "1.3")

        ParameterUpdater(recorder.client).increment(ParameterTargets("P", setOf("Match", "Other")), current = "1.2.7")

        Assertions.assertEquals(listOf(listOf(ConfigurationType.PROJECT, "Match", "P", "1.3")), recorder.written())
    }

    @Test
    fun skipsValuesThatCannotBeReadOrIncremented() {
        val recorder = clientWithValues("NotNumeric" to "1.x")

        ParameterUpdater(recorder.client).increment(ParameterTargets("P", setOf("NotNumeric", "Missing")))

        Assertions.assertTrue(recorder.written().isEmpty())
    }

    @Test
    fun rejectsTargetsWithoutProjectsOrBuildTypes() {
        Assertions.assertThrows(IllegalArgumentException::class.java) { ParameterTargets("P") }
    }
}
