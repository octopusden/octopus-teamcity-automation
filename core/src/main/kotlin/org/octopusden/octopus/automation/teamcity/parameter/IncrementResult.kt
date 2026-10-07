package org.octopusden.octopus.automation.teamcity.parameter

sealed interface IncrementResult {
    val target: ParameterTarget

    data class Incremented(
        override val target: ParameterTarget,
        val from: String,
        val to: String,
    ) : IncrementResult

    /** Left unchanged: its value could not be read, does not match `current`, or its last component is not a number. */
    data class Skipped(
        override val target: ParameterTarget,
        val reason: String,
        val cause: Throwable? = null,
    ) : IncrementResult
}
