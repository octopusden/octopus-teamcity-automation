package org.octopusden.octopus.automation.teamcity.utils.javahome

/**
 * Resolves the TeamCity parameter name to reference from `env.JAVA_HOME`, given a caller-supplied [JavaHomeMappingOption].
 * The caller wraps the result in `%...%`.
 */
object JavaHomeMapping {
    const val PLACEHOLDER = "{major}"

    /** `null` when no JDK major version can be derived from [javaVersion] — the registry does not constrain that field. */
    fun resolve(
        javaVersion: String,
        overrides: Map<Int, String>,
        template: String,
    ): String? {
        val major = majorOrNull(javaVersion) ?: return null
        return overrides[major] ?: template.replace(PLACEHOLDER, major.toString())
    }

    /** `1.8` and `1.8.0_292` are the legacy scheme, where the major is the second segment; everything since is the first. */
    private fun majorOrNull(javaVersion: String): Int? =
        javaVersion
            .trim()
            .removePrefix("1.")
            .substringBefore('.')
            .toIntOrNull()
            ?.takeIf { it > 0 }
}
