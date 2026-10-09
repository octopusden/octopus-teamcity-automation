package org.octopusden.octopus.automation.teamcity.parameter

import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient

/** Sets or increments a TeamCity parameter on projects and build configurations. */
class ParameterUpdater(
    private val client: TeamcityClient,
) {
    /**
     * Sets [value] on every target, projects first. [beforeWrite] is called for each target before its
     * value is written, so a caller can report progress even if a later write fails.
     */
    fun set(
        targets: ParameterTargets,
        value: String,
        beforeWrite: (ParameterTarget) -> Unit = {},
    ): List<ParameterTarget> =
        targets.all().onEach { target ->
            beforeWrite(target)
            client.setParameter(target.type, target.id, targets.name, value)
        }

    /**
     * Increments the last numeric component of each target's value (`1.2` -> `1.3`, `1.2-7` -> `1.2-8`).
     * With a non-empty [current], only where [current] starts with all components of the value
     * (`current = 1.2.7` increments `1.2`, not `1.3`). A target that cannot be read or incremented is
     * skipped, not failed. [onResult] is called for each target as soon as it is decided, before an
     * incremented value is written. A failing write is not caught.
     */
    @Suppress("TooGenericExceptionCaught")
    fun increment(
        targets: ParameterTargets,
        current: String = "",
        onResult: (IncrementResult) -> Unit = {},
    ): List<IncrementResult> =
        targets.all().map { target ->
            val value = try {
                client.getParameter(target.type, target.id, targets.name)
            } catch (e: Exception) {
                return@map IncrementResult.Skipped(target, "Unable to retrieve value", e).also(onResult)
            }
            when (val outcome = VersionIncrement.next(value, current)) {
                is VersionIncrement.Outcome.Skipped ->
                    IncrementResult.Skipped(target, outcome.reason, outcome.cause).also(onResult)
                is VersionIncrement.Outcome.Incremented ->
                    IncrementResult.Incremented(target, value, outcome.value).also { result ->
                        onResult(result)
                        client.setParameter(target.type, target.id, targets.name, outcome.value)
                    }
            }
        }

    private fun ParameterTargets.all() =
        projectIds.map { ParameterTarget(ConfigurationType.PROJECT, it) } +
            buildTypeIds.map { ParameterTarget(ConfigurationType.BUILD_TYPE, it) }
}
