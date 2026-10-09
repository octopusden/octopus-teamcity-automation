package org.octopusden.octopus.automation.teamcity.parameter

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.octopusden.octopus.automation.teamcity.RecordingTeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType

/** How the updater reads, reports and writes; the increment rule itself is in [VersionIncrementTest]. */
class ParameterUpdaterTest {
    private val project = ParameterTarget(ConfigurationType.PROJECT, "Proj")
    private val build = ParameterTarget(ConfigurationType.BUILD_TYPE, "Build")

    private fun clientWithValues(vararg values: Pair<String, String>): RecordingTeamcityClient {
        val byId = values.toMap()
        return RecordingTeamcityClient { method, args ->
            if (method == "getParameter") byId[args[1]] ?: error("no value for ${args[1]}") else null
        }
    }

    private fun RecordingTeamcityClient.written() = callsOf("setParameter").map { it.args }

    @Test
    fun setsValueOnProjectsThenBuildTypes() {
        val recorder = RecordingTeamcityClient()

        val updated = ParameterUpdater(recorder.client).set(ParameterTargets("P", setOf("Proj"), setOf("Build")), "v")

        Assertions.assertEquals(listOf(project, build), updated)
        Assertions.assertEquals(
            listOf(listOf(ConfigurationType.PROJECT, "Proj", "P", "v"), listOf(ConfigurationType.BUILD_TYPE, "Build", "P", "v")),
            recorder.written(),
        )
    }

    @Test
    fun returnsAResultPerTargetAndWritesOnlyIncrementedOnes() {
        val recorder = clientWithValues("Proj" to "1.2", "Build" to "1.x")

        val results = ParameterUpdater(recorder.client).increment(ParameterTargets("P", setOf("Proj", "Missing"), setOf("Build")))

        Assertions.assertEquals(IncrementResult.Incremented(project, "1.2", "1.3"), results[0])
        Assertions.assertEquals("Unable to retrieve value", (results[1] as IncrementResult.Skipped).reason)
        Assertions.assertEquals("Unable to increment last component x of 1.x", (results[2] as IncrementResult.Skipped).reason)
        Assertions.assertEquals(listOf(listOf(ConfigurationType.PROJECT, "Proj", "P", "1.3")), recorder.written())
    }

    @Test
    fun reportsEachResultBeforeWritingIt() {
        val recorder = clientWithValues("Proj" to "1.2")
        val writesSeenByCallback = mutableListOf<Int>()

        ParameterUpdater(recorder.client).increment(ParameterTargets("P", setOf("Proj"))) {
            writesSeenByCallback += recorder.written().size
        }

        Assertions.assertEquals(listOf(0), writesSeenByCallback)
        Assertions.assertEquals(1, recorder.written().size)
    }

    @Test
    fun rejectsTargetsWithoutProjectsOrBuildTypes() {
        Assertions.assertThrows(IllegalArgumentException::class.java) { ParameterTargets("P") }
    }
}
