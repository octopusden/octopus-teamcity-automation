package org.octopusden.octopus.automation.teamcity.utils.javahome

import com.github.ajalt.clikt.core.BadParameterValue
import org.octopusden.octopus.automation.teamcity.SPLIT_SYMBOLS

/**
 * Parsed `--java-home-mapping` value: a required leading bare template  plus per-major overrides.
 * [parse] validates eagerly, before any TeamCity project is created.
 */
data class JavaHomeMappingOption(
    val overrides: Map<Int, String>,
    val template: String,
) {
    /** `null` when [javaVersion] is `null` — there is nothing to resolve without it. */
    fun resolveOrNull(javaVersion: String?): String? = javaVersion?.let { JavaHomeMapping.resolve(it, overrides, template) }

    companion object {
        /** [optionName] is the CLI flag name used only to prefix error messages. */
        fun parse(
            raw: String,
            optionName: String,
        ): JavaHomeMappingOption {
            fun fail(message: String): Nothing = throw BadParameterValue("$optionName: $message")

            val entries = raw.split(SPLIT_SYMBOLS.toRegex()).map { it.trim() }.filter { it.isNotEmpty() }
            val template = parseTemplate(entries.firstOrNull(), ::fail)

            val overrides = mutableMapOf<Int, String>()
            entries.drop(1).forEach { entry -> parseOverride(entry, overrides, ::fail) }

            return JavaHomeMappingOption(overrides, template)
        }

        private fun parseTemplate(
            candidate: String?,
            fail: (String) -> Nothing,
        ): String {
            val template = candidate?.takeIf { !it.contains('=') }
                ?: fail("requires a leading template entry (no '=') as its first segment, e.g. env.JDK_{major}_0")
            if (isWrapped(template)) fail("leading template must not already be %-wrapped (got '$template')")
            val placeholderCount = template.split(JavaHomeMapping.PLACEHOLDER).size - 1
            if (placeholderCount != 1) {
                fail("leading template must contain exactly one ${JavaHomeMapping.PLACEHOLDER} occurrence (got '$template')")
            }
            return template
        }

        private fun parseOverride(
            entry: String,
            overrides: MutableMap<Int, String>,
            fail: (String) -> Nothing,
        ) {
            if (!entry.contains('=')) fail("a bare entry ('$entry') is only valid as the first segment")
            val key = entry.substringBefore('=').trim()
            val major = key.toIntOrNull()?.takeIf { it > 0 }
                ?: fail("'$key' is not a valid JDK major version (from entry '$entry')")
            val value = entry.substringAfter('=').trim()
            if (isWrapped(value)) fail("override value must not already be %-wrapped (from entry '$entry')")
            if (value.contains(JavaHomeMapping.PLACEHOLDER)) {
                fail("override value must not contain ${JavaHomeMapping.PLACEHOLDER} (from entry '$entry')")
            }
            if (overrides.containsKey(major)) fail("duplicate override for major '$major' (from entry '$entry')")
            overrides[major] = value
        }

        private fun isWrapped(value: String) = value.startsWith('%') && value.endsWith('%')
    }
}
