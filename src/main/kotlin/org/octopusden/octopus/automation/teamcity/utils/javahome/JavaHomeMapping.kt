package org.octopusden.octopus.automation.teamcity.utils.javahome

/**
 * Resolves the `env.JAVA_HOME` reference for a JDK major version, given a caller-supplied [JavaHomeMappingOption].
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
