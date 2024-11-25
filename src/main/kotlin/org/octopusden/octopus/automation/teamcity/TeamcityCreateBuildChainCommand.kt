package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.DetailedComponent
import org.octopusden.octopus.components.registry.core.dto.RepositoryType
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.infrastructure.teamcity.client.getProject
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityVCSType
import org.octopusden.octopus.infrastructure.teamcity.client.disableBuildStep
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildSteps
import org.octopusden.octopus.infrastructure.teamcity.client.createBuildTypeVcsRootEntry
import org.octopusden.octopus.infrastructure.teamcity.client.createSnapshotDependency
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRootEntry
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcitySnapshotDependency
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityVcsRoot
import org.slf4j.Logger

class TeamcityCreateBuildChainCommand : CliktCommand(name = COMMAND) {
    private val context by requireObject<MutableMap<String, Any>>()

    private val parentProjectId by option(PARENT, help = "Teamcity parent project Id").convert { it.trim() }.required()
        .check("$PARENT is empty") { it.isNotEmpty() }

    private val componentName by option(COMPONENT, help = "Component registry name").convert { it.trim() }.required()
        .check("$COMPONENT is empty") { it.isNotEmpty() }

    private val minorVersion by option(VERSION, help = "Minor version").convert { it.trim() }.required()
        .check("$VERSION is empty") { it.isNotEmpty() }

    private val componentsRegistryUrl by option(CR, help = "Components Registry service Url").required()
        .check("$CR is empty") { it.isNotEmpty() }

    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }
    private val log by lazy { context[TeamcityCommand.LOG] as Logger }

    override fun run() {
        log.info("Create build chain")
        val parentProject = client.getProject(parentProjectId)
        val componentsRegistryClient = ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl(): String {
                    return componentsRegistryUrl
                }
            }
        )
        val detailedComponent = componentsRegistryClient.getDetailedComponent(componentName, minorVersion)
        createBuildChain(parentProject, detailedComponent)
    }

    private fun createBuildChain(
        parentProject: TeamcityProject,
        component: DetailedComponent
    ) {
        val project = client.createProject(
            TeamcityCreateProject(name = componentName, parentProject = TeamcityLinkProject(id = parentProject.id))
        )
        val vcsRootId = createVcsRoot(project.id, component)?.id
        var counter = 0
        val compileConfig = createBuildConf(
            when (component.buildSystem) {
                BuildSystem.MAVEN -> TEMPLATE_MAVEN_COMPILE
                BuildSystem.GRADLE -> TEMPLATE_GRADLE_COMPILE
                BuildSystem.PROVIDED -> TEMPLATE_GRADLE_COMPILE
                else -> throw NotFoundException("Unsupported build system: ${component.buildSystem?.name}")
            },
            "[${++counter}.0] Compile & UT [AUTO]",
            project.id
        )
        attachVcsRootToBuildType(compileConfig.id, vcsRootId)
        val defaultJDKVersion = client.getParameter(ConfigurationType.PROJECT, parentProjectId, "JDK_VERSION")
        component.buildParameters?.javaVersion?.takeIf { it != defaultJDKVersion }?.let { projectJDKVersion ->
            setBuildTypeParameter(compileConfig.id, "JDK_VERSION", projectJDKVersion)
        }
        val releaseConfig = if (component.distribution?.explicit == true && component.distribution?.external == true) {
            val rcConfig = createBuildConf(
                TEMPLATE_RC,
                "[${++counter}.0] Release Candidate [Manual]",
                project.id
            )
            attachVcsRootToBuildType(rcConfig.id, vcsRootId)

            val checklistConfig = createBuildConf(
                TEMPLATE_CHECKLIST,
                "[${++counter}.0] Release Checklist Validation [MANUAL]",
                project.id
            )
            attachVcsRootToBuildType(checklistConfig.id, vcsRootId)

            val releaseConfig = createBuildConf(
                TEMPLATE_RELEASE,
                "[${++counter}.0] Release [Manual]",
                project.id
            )
            attachVcsRootToBuildType(releaseConfig.id, vcsRootId)

            addSnapshotDependency(rcConfig, compileConfig)
            addSnapshotDependency(checklistConfig, rcConfig)
            addSnapshotDependency(releaseConfig, rcConfig)

            setBuildTypeParameter(rcConfig.id, "BUILD_VERSION", "%dep.${compileConfig.id}.BUILD_VERSION%")
            setBuildTypeParameter(checklistConfig.id, "BUILD_VERSION", "%dep.${compileConfig.id}.BUILD_VERSION%")
            releaseConfig
        } else {
            val releaseConfig = createBuildConf(
                TEMPLATE_RELEASE,
                "[${++counter}.0] Release [Manual]",
                project.id
            )
            attachVcsRootToBuildType(releaseConfig.id, vcsRootId)
            addSnapshotDependency(releaseConfig, compileConfig)
            releaseConfig
        }
        disableBuildStep(releaseConfig.id, "IncrementTeamCityBuildConfigurationParameter")
        if (component.buildSystem == BuildSystem.GRADLE) {
            disableBuildStep(releaseConfig.id, "Deploy to Share")
        }
        setBuildTypeParameter(releaseConfig.id, "BUILD_VERSION", "%dep.${compileConfig.id}.BUILD_VERSION%")
        setProjectParameter(project.id, "COMPONENT_NAME", componentName)
        setProjectParameter(project.id, "RELENG_SKIP", "false")
        setProjectParameter(project.id, "PROJECT_VERSION", minorVersion)
    }

    private fun attachVcsRootToBuildType(buildTypeId: String, vcsRootId: String?) =
        vcsRootId?.let {
            client.createBuildTypeVcsRootEntry(
                buildTypeId,
                TeamcityCreateVcsRootEntry(
                    id = vcsRootId,
                    vcsRoot = TeamcityLinkVcsRoot(vcsRootId)
                )
            )
        } ?: log.info("Skip attach vcs root to {}", buildTypeId)

    private fun createBuildConf(templateId: String, name: String, projectId: String) =
        client.createBuildType(
            TeamcityCreateBuildType(
                template = TeamcityLinkBuildType(id = templateId),
                name = name,
                project = TeamcityLinkProject(id = projectId),
            )
        )

    private fun addSnapshotDependency(buildType: TeamcityBuildType, sourceBuildType: TeamcityBuildType) {
        client.createSnapshotDependency(
            buildType.id,
            TeamcitySnapshotDependency(
                id = sourceBuildType.name,
                type = "snapshot_dependency",
                properties = TeamcityProperties(
                    listOf(
                        TeamcityProperty("run-build-if-dependency-failed", "MAKE_FAILED_TO_START"),
                        TeamcityProperty("run-build-if-dependency-failed-to-start", "MAKE_FAILED_TO_START"),
                        TeamcityProperty("run-build-on-the-same-agent", "false"),
                        TeamcityProperty("take-started-build-with-same-revisions", "true"),
                        TeamcityProperty("take-successful-builds-only", "true"),
                    )
                ),
                sourceBuildType = TeamcityLinkBuildType(sourceBuildType.id)
            )
        )
    }

    private fun setBuildTypeParameter(buildTypeId: String, name: String, value: String) =
        client.setParameter(ConfigurationType.BUILD_TYPE, buildTypeId, name, value).also {
            log.info("Set parameter $name value $value for build configuration with id $buildTypeId")
        }

    private fun setProjectParameter(projectId: String, name: String, value: String) =
        client.setParameter(ConfigurationType.PROJECT, projectId, name, value).also {
            log.info("Set parameter $name value $value for project with id $projectId")
        }

    private fun disableBuildStep(buildTypeId: String, stepNameOrType: String, disable: Boolean = true) {
        client.getBuildSteps(buildTypeId)
            .steps.find { step -> step.name == stepNameOrType || step.type == stepNameOrType }
            ?.let { step -> client.disableBuildStep(buildTypeId, step.id, disable) }
            ?: log.warn("Skip disable build step '{}' not found for build type {}", stepNameOrType, buildTypeId)
    }

    private fun createVcsRoot(projectId: String, component: DetailedComponent): TeamcityVcsRoot? {
        return component.vcsSettings?.versionControlSystemRoots?.firstOrNull()?.let { vcsRootData ->
            val vcsRootName = "${projectId}_VCS_ROOT"
            when (vcsRootData.type) {
                RepositoryType.GIT -> client.createVcsRoot(
                    TeamcityCreateVcsRoot(
                        name = vcsRootName,
                        vcsName = TeamcityVCSType.GIT.value,
                        projectLocator = projectId,
                        TeamcityProperties(
                            listOf(
                                TeamcityProperty("url", vcsRootData.vcsPath),
                                TeamcityProperty("branch", "master"),
                                TeamcityProperty("authMethod", "PRIVATE_KEY_DEFAULT"),
                                TeamcityProperty("userForTags", "tcagent"),
                                TeamcityProperty("username", "git"),
                                TeamcityProperty("ignoreKnownHosts", "true"),
                            )
                        )
                    )
                )
                else -> throw NotFoundException("Unsupported vcs type: ${vcsRootData.type}")
            }
        }
    }

    companion object {
        const val COMMAND = "create-build-chain"
        const val PARENT = "--parent-project-id"
        const val COMPONENT = "--component"
        const val VERSION = "--minor-version"
        const val CR = "--registry-url"

        const val TEMPLATE_GRADLE_COMPILE = "CDCompileUTGradle"
        const val TEMPLATE_MAVEN_COMPILE = "CDCompileUTMaven"
        const val TEMPLATE_RC = "CdReleaseCandidateNew"
        const val TEMPLATE_CHECKLIST = "CdReleaeChecklistValidation"
        const val TEMPLATE_RELEASE = "CDRelease"
    }
}