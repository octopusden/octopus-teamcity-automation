package org.octopusden.octopus.automation.teamcity.parameter

/** The increment rule, with no TeamCity calls. */
internal object VersionIncrement {
    private val componentDelimiters = "[.-]".toRegex()

    sealed interface Outcome {
        data class Incremented(
            val value: String,
        ) : Outcome

        data class Skipped(
            val reason: String,
            val cause: Throwable? = null,
        ) : Outcome
    }

    /**
     * Increments the last numeric component of [value] (`1.2` -> `1.3`, `1.2-7` -> `1.2-8`). With a
     * non-empty [current], only when [current] starts with all components of [value].
     */
    fun next(
        value: String,
        current: String,
    ): Outcome {
        val valueComponents = value.split(componentDelimiters)
        if (current.isNotEmpty() && valueComponents != current.split(componentDelimiters).take(valueComponents.size)) {
            return Outcome.Skipped("$current does not contain components of $value")
        }
        val lastComponent = valueComponents.last()
        val incrementedLastComponent = try {
            lastComponent.toLong().inc().toString()
        } catch (e: NumberFormatException) {
            return Outcome.Skipped("Unable to increment last component $lastComponent of $value", e)
        }
        return Outcome.Incremented(value.removeSuffix(lastComponent) + incrementedLastComponent)
    }
}
