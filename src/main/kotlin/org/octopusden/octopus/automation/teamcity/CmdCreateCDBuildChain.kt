package org.octopusden.octopus.automation.teamcity

import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.required
import org.octopusden.octopus.automation.teamcity.template.GradleTemplateProvider
import org.octopusden.octopus.automation.teamcity.template.MavenTemplateProvider
import org.octopusden.octopus.automation.teamcity.template.TemplateProvider
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.DetailedComponent
import org.octopusden.octopus.components.registry.core.dto.RepositoryType
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityVCSType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRootEntry
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkFeature
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcitySnapshotDependency
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityVcsRoot
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@kotlinx.cli.ExperimentalCli
class CmdCreateCDBuildChain(val application: Application) : Subcommand(
    name = "createCDBuildChain",
    actionDescription = "Create TeamCity CD build chain for component"
) {
    private val logger: Logger = LoggerFactory.getLogger(CmdCreateCDBuildChain::class.java)

    private val parentProjectId by option(
        type = ArgType.String,
        fullName = "project.parent",
        description = "Teamcity parent project Id"
    ).required()

    private val componentName by option(
        type = ArgType.String,
        fullName = "component",
        description = "Component registry name"
    ).required()

    private val minorVersion by option(
        type = ArgType.String,
        fullName = "version.minor",
        description = "Minor version"
    ).required()

    private val teamcityClient by lazy { application.getTeamCityClient() }

    override fun execute() {
        val parentProject = teamcityClient.getProject(parentProjectId)
        val componentsRegistryServiceClient = application.componentsRegistryClient
        val detailedComponent = componentsRegistryServiceClient.getDetailedComponent(componentName, minorVersion)
        val templateProvider = getTemplateProvider(detailedComponent.buildSystem)
        createBuildChain(templateProvider, parentProject, detailedComponent)
        logger.debug("VCS = {}", componentsRegistryServiceClient.getVCSSetting(componentName, minorVersion))
    }

    private fun createBuildChain(
        templateProvider: TemplateProvider,
        parentProject: TeamcityProject,
        component: DetailedComponent
    ) {
        val project = teamcityClient.createProject(
            TeamcityCreateProject(name = componentName, parentProject = TeamcityLinkProject(id = parentProject.id))
        )
        val vcsRootId = createVcsRoot(project.id, component)?.id
        val counter = object {
            private var major: Int = 0
            private var minor: Int = 0
            private fun format(a: Int, b: Int) = "$a.$b"

            fun getCounterIncMajor(): String {
                minor = 0
                return format(++major, minor)
            }
        }
        val compileConfig = createBuildConf(
            templateProvider.getCompileTemplate(),
            "[${counter.getCounterIncMajor()}] Compile & UT [AUTO]",
            project.id
        )
        attachVcsRootToBuildType(compileConfig.id, vcsRootId)
        val releaseConfig = if (component.distribution?.explicit == true && component.distribution?.external == true) {
            val rcConfig = createBuildConf(
                templateProvider.getRCTemplate(),
                "[${counter.getCounterIncMajor()}] Release Candidate [Manual]",
                project.id
            )
            attachVcsRootToBuildType(rcConfig.id, vcsRootId)

            val checklistConfig = createBuildConf(
                templateProvider.getChecklistTemplate(),
                "[${counter.getCounterIncMajor()}] Release Checklist Validation [MANUAL]",
                project.id
            )
            attachVcsRootToBuildType(checklistConfig.id, vcsRootId)

            val releaseConfig = createBuildConf(
                templateProvider.getReleaseTemplate(),
                "[${counter.getCounterIncMajor()}] Release [Manual]",
                project.id
            )
            attachVcsRootToBuildType(releaseConfig.id, vcsRootId)

            addSnapshotDependency(rcConfig, compileConfig)
            addSnapshotDependency(checklistConfig, rcConfig)
            addSnapshotDependency(releaseConfig, rcConfig)

            setBuildTypeParameter(checklistConfig.id, "BUILD_VERSION", "%dep.${compileConfig.id}.BUILD_VERSION%")
            releaseConfig
        } else {
            val releaseConfig = createBuildConf(
                templateProvider.getReleaseTemplate(),
                "[${counter.getCounterIncMajor()}] Release [Manual]",
                project.id
            )
            attachVcsRootToBuildType(releaseConfig.id, vcsRootId)
            addSnapshotDependency(releaseConfig, compileConfig)
            releaseConfig
        }
        disableBuildStep(releaseConfig.id, "IncrementTeamCityBuildConfigurationParameter")
        attachVcsLabelingFeature(releaseConfig.id, vcsRootId)
        if (component.buildSystem == BuildSystem.GRADLE) {
            disableBuildStep(releaseConfig.id, "Deploy to Share")
        }
        setBuildTypeParameter(releaseConfig.id, "BUILD_VERSION", "%dep.${compileConfig.id}.BUILD_VERSION%")
        setBuildTypeParameter(
            releaseConfig.id,
            "STAGING_REPOSITORY_ID",
            "%dep.${compileConfig.id}.STAGING_REPOSITORY_ID%"
        )
        setProjectParameter(project.id, "COMPONENT_NAME", componentName)
        setProjectParameter(project.id, "RELENG_SKIP", "false")
        setProjectParameter(project.id, "PROJECT_VERSION", minorVersion)
    }

    private fun getTemplateProvider(buildSystem: BuildSystem?): TemplateProvider {
        return when (buildSystem) {
            BuildSystem.MAVEN -> MavenTemplateProvider()
            BuildSystem.GRADLE -> GradleTemplateProvider()
            BuildSystem.PROVIDED -> GradleTemplateProvider()
            else -> throw NotFoundException("Unsupported build system: ${buildSystem?.name}")
        }
    }

    private fun attachVcsRootToBuildType(buildTypeId: String, vcsRootId: String?) =
        vcsRootId?.let {
            teamcityClient.createBuildTypeVcsRootEntry(
                buildTypeId,
                TeamcityCreateVcsRootEntry(
                    id = vcsRootId,
                    vcsRoot = TeamcityLinkVcsRoot(vcsRootId)
                )
            )
        } ?: logger.info("Skip attach vcs root to {}", buildTypeId)

    private fun createBuildConf(templateId: String, name: String, projectId: String) =
        teamcityClient.createBuildType(
            TeamcityCreateBuildType(
                template = TeamcityLinkBuildType(id = templateId),
                name = name,
                project = TeamcityLinkProject(id = projectId),
            )
        )

    private fun addSnapshotDependency(buildType: TeamcityBuildType, sourceBuildType: TeamcityBuildType) {
        teamcityClient.createSnapshotDependency(
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

    private fun attachVcsLabelingFeature(buildTypeId: String, vcsRootId: String?) {
        vcsRootId?.let {
            teamcityClient.addBuildTypeFeature(
                buildTypeId, TeamcityLinkFeature(
                    type = "VcsLabeling",
                    id = "VcsLabeling",
                    properties = TeamcityProperties(
                        listOf(
                            TeamcityProperty("labelingPattern", "%LABELING_PATTERN%"),
                            TeamcityProperty("successfulOnly", "true"),
                            TeamcityProperty("vcsRootId", vcsRootId),
                        )
                    )
                )
            )
        } ?: logger.info("skip attach labeling feature to {}", buildTypeId)
    }

    private fun setBuildTypeParameter(buildTypeId: String, name: String, value: String) =
        teamcityClient.setParameter(ConfigurationType.BUILD_TYPE, buildTypeId, name, value)

    private fun setProjectParameter(projectId: String, name: String, value: String) =
        teamcityClient.setParameter(ConfigurationType.PROJECT, projectId, name, value)

    private fun disableBuildStep(buildTypeId: String, stepNameOrType: String, disable: Boolean = true) {
        teamcityClient.getBuildSteps(buildTypeId)
            .steps.find { step -> step.name == stepNameOrType || step.type == stepNameOrType }
            ?.let { step -> teamcityClient.disableBuildStep(buildTypeId, step.id, disable) }
            ?: logger.warn("Skip disable build step '{}' not found for build type {}", stepNameOrType, buildTypeId)
    }

    private fun createVcsRoot(projectId: String, component: DetailedComponent): TeamcityVcsRoot? {
        return component.vcsSettings?.versionControlSystemRoots?.firstOrNull()?.let { vcsRootData ->
            val vcsRootName = "${projectId}_VCS_ROOT"
            when (vcsRootData.type) {
                RepositoryType.GIT -> teamcityClient.createVcsRoot(
                    TeamcityCreateVcsRoot(
                        name = vcsRootName,
                        vcsName = TeamcityVCSType.GIT.toString(),
                        projectLocator = projectId,
                        TeamcityProperties(
                            listOf(
                                TeamcityProperty("url", vcsRootData.vcsPath),
                                TeamcityProperty("branch", vcsRootData.branch.ifBlank { "master" }),
                                TeamcityProperty("authMethod", "PRIVATE_KEY_DEFAULT"),
                                TeamcityProperty("userForTags", "tcagent"),
                                TeamcityProperty("username", "git"),
                                TeamcityProperty("ignoreKnownHosts", "true"),
                            )
                        )
                    )
                )

                RepositoryType.MERCURIAL -> teamcityClient.createVcsRoot(
                    TeamcityCreateVcsRoot(
                        name = vcsRootName,
                        vcsName = TeamcityVCSType.HG.toString(),
                        projectLocator = projectId,
                        TeamcityProperties(
                            listOf(
                                TeamcityProperty("repositoryPath", vcsRootData.vcsPath.replace("ssh://", "")),
                                TeamcityProperty("branch", vcsRootData.branch.ifBlank { "default" }),
                                TeamcityProperty("hgCommandPath", "hg"),
                            )
                        )
                    )
                )

                else -> throw NotFoundException("Unsupported vcs type: ${vcsRootData.type}")
            }
        }
    }
}