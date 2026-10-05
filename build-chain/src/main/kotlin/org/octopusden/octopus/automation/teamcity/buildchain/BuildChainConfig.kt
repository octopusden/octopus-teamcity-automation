package org.octopusden.octopus.automation.teamcity.buildchain

/** The TeamCity templates a chain is created from, and the parameter that carries the component's JDK. */
data class BuildChainConfig(
    val gradleCompileTemplate: String = DEFAULT_GRADLE_COMPILE_TEMPLATE,
    val mavenCompileTemplate: String = DEFAULT_MAVEN_COMPILE_TEMPLATE,
    val rcTemplate: String = DEFAULT_RC_TEMPLATE,
    val checklistTemplate: String = DEFAULT_CHECKLIST_TEMPLATE,
    val releaseTemplate: String = DEFAULT_RELEASE_TEMPLATE,
    val jdkVersionParameter: String = DEFAULT_JDK_VERSION_PARAMETER,
) {
    companion object {
        const val DEFAULT_GRADLE_COMPILE_TEMPLATE = "CDGradleBuild"
        const val DEFAULT_MAVEN_COMPILE_TEMPLATE = "CDJavaMavenBuild"
        const val DEFAULT_RC_TEMPLATE = "CdReleaseCandidateNew"
        const val DEFAULT_CHECKLIST_TEMPLATE = "CdReleaeChecklistValidation"
        const val DEFAULT_RELEASE_TEMPLATE = "CDRelease"
        const val DEFAULT_JDK_VERSION_PARAMETER = "JDK_VERSION"
    }
}
