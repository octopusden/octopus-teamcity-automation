package org.octopusden.octopus.automation.teamcity.utils.javahome

/**
 * Resolves the `env.JAVA_HOME` reference for a JDK major version, given a caller-supplied
 * [JavaHomeMappingOption]. Carries no formula or exception of its own (D4 in
 * `set-env-java-home-from-java-version/design.md`) — every mapping comes from the option.
 */
object JavaHomeMapping {
    const val PLACEHOLDER = "{major}"

    fun resolve(
        javaVersion: String,
        overrides: Map<Int, String>,
        template: String,
    ): String {
        val major = javaVersion.substringAfterLast('.').toInt()
        return overrides[major] ?: template.replace(PLACEHOLDER, major.toString())
    }
}
