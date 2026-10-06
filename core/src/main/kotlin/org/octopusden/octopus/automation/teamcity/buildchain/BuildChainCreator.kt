package org.octopusden.octopus.automation.teamcity.buildchain

import feign.FeignException
import org.octopusden.octopus.components.registry.client.ComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.DetailedComponent
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityRole
import org.octopusden.octopus.infrastructure.teamcity.client.createSnapshotDependency
import org.octopusden.octopus.infrastructure.teamcity.client.disableBuildStep
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcitySnapshotDependency
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildSteps
import org.octopusden.octopus.infrastructure.teamcity.client.getProject
import org.slf4j.LoggerFactory

/**
 * Creates a component's TeamCity build chain — Compile, optional Release Candidate and Release
 * Checklist Validation, Release — under a parent project, from the component's Components Registry
 * metadata. The caller owns both clients.
 */
class BuildChainCreator(
    private val client: TeamcityClient,
    private val componentsRegistryClient: ComponentsRegistryServiceClient,
    private val config: BuildChainConfig = BuildChainConfig(),
) {
    private val log = LoggerFactory.getLogger(BuildChainCreator::class.java)

    /**
     * @throws UnsupportedBuildSystemException the component's build system has no compile template
     * @throws UnsupportedVcsTypeException a registry VCS root is not Git
     * @throws UnsupportedVcsRootLayoutException the registry VCS roots cannot be placed
     */
    fun create(request: BuildChainRequest): BuildChainResult {
        log.info("Create build chain")
        val parentProject = client.getProject(request.parentProjectId)
        val detailedComponent = componentsRegistryClient.getDetailedComponent(request.componentName, request.minorVersion)
        return createBuildChain(request, parentProject, detailedComponent)
    }

    private fun createBuildChain(
        request: BuildChainRequest,
        parentProject: TeamcityProject,
        component: DetailedComponent,
    ): BuildChainResult {
        val componentName = request.componentName
        val placement = VcsRootPlacement(client, log, componentName)
        val registryRoots = component.vcsSettings.versionControlSystemRoots
        placement.validate(registryRoots, reservedCheckoutDirectory(request.parentProjectId))
        val compileTemplate = when (component.buildSystem) {
            BuildSystem.MAVEN -> config.mavenCompileTemplate
            BuildSystem.GRADLE -> config.gradleCompileTemplate
            BuildSystem.PROVIDED -> config.gradleCompileTemplate
            BuildSystem.IN_CONTAINER -> config.gradleCompileTemplate
            else -> throw UnsupportedBuildSystemException(component.buildSystem)
        }

        val project = client.createProject(
            TeamcityCreateProject(name = componentName, parentProject = TeamcityLinkProject(id = parentProject.id)),
        )
        val buildWorkingDirectory = component.vcsSettings.buildWorkingDirectory?.takeIf { it.isNotBlank() }
        val placedRoots = placement.createAll(project.id, registryRoots)
        val attachOrder = placement.attachOrder(registryRoots, buildWorkingDirectory)
        log.info(
            "Attach order for '{}': {}",
            componentName,
            attachOrder.map { placedRoots.getValue(it).vcsRoot.name },
        )
        if (buildWorkingDirectory != null) {
            log.info(
                "Component '{}': BUILD_VERSION_FORMAT_FILE set to '{}/build-version-format.properties' on every " +
                    "created configuration with a 'Calculate Build Version' step; takes effect only once templates " +
                    "${config.gradleCompileTemplate}/${config.mavenCompileTemplate} read this parameter in that step",
                componentName,
                buildWorkingDirectory,
            )
        }

        var counter = 0
        val compileConfig = createBuildConf(
            compileTemplate,
            "[${++counter}.0] Compile & UT [AUTO]",
            project.id,
        )
        placement.attach(compileConfig.id, attachOrder, placedRoots)
        applyBuildWorkingDirectory(compileConfig.id, buildWorkingDirectory)
        val defaultJDKVersion = client.getParameter(ConfigurationType.PROJECT, request.parentProjectId, config.jdkVersionParameter)
        component.buildParameters?.javaVersion?.takeIf { it != defaultJDKVersion }?.let { projectJDKVersion ->
            setBuildTypeParameter(compileConfig.id, config.jdkVersionParameter, projectJDKVersion)
        }
        var rcConfigId: String? = null
        var checklistConfigId: String? = null
        val releaseConfig =
            if ((component.distribution?.explicit == true && component.distribution?.external == true) || request.createRcForce) {
                val rcConfig = createBuildConf(
                    config.rcTemplate,
                    "[${++counter}.0] Release Candidate [Manual]",
                    project.id,
                )
                rcConfigId = rcConfig.id
                placement.attach(rcConfig.id, attachOrder, placedRoots)
                applyBuildWorkingDirectory(rcConfig.id, buildWorkingDirectory)

                if (request.createChecklist) {
                    val checklistConfig = createBuildConf(
                        config.checklistTemplate,
                        "[${++counter}.0] Release Checklist Validation [MANUAL]",
                        project.id,
                    )
                    checklistConfigId = checklistConfig.id
                    placement.attach(checklistConfig.id, attachOrder, placedRoots)
                    applyBuildWorkingDirectory(checklistConfig.id, buildWorkingDirectory)
                    addSnapshotDependency(checklistConfig, rcConfig, DependencyFailureAction.CANCEL)
                    setBuildTypeParameter(
                        checklistConfig.id,
                        "BUILD_VERSION",
                        "%dep.${compileConfig.id}.BUILD_VERSION%",
                    )
                }

                val releaseConfig = createBuildConf(
                    config.releaseTemplate,
                    "[${++counter}.0] Release [Manual]",
                    project.id,
                )
                placement.attach(releaseConfig.id, attachOrder, placedRoots)
                applyBuildWorkingDirectory(releaseConfig.id, buildWorkingDirectory)

                addSnapshotDependency(rcConfig, compileConfig, DependencyFailureAction.CANCEL)
                addSnapshotDependency(releaseConfig, rcConfig, DependencyFailureAction.CANCEL)

                setBuildTypeParameter(rcConfig.id, "BUILD_VERSION", "%dep.${compileConfig.id}.BUILD_VERSION%")
                releaseConfig
            } else {
                val releaseConfig = createBuildConf(
                    config.releaseTemplate,
                    "[${++counter}.0] Release [Manual]",
                    project.id,
                )
                placement.attach(releaseConfig.id, attachOrder, placedRoots)
                applyBuildWorkingDirectory(releaseConfig.id, buildWorkingDirectory)
                addSnapshotDependency(releaseConfig, compileConfig, DependencyFailureAction.CANCEL)
                releaseConfig
            }
        disableBuildStep(releaseConfig.id, "IncrementTeamCityBuildConfigurationParameter")
        setBuildTypeParameter(releaseConfig.id, "BUILD_VERSION", "%dep.${compileConfig.id}.BUILD_VERSION%")
        setBuildTypeParameter(releaseConfig.id, "BASE_CONFIGURATION_ID", compileConfig.id)
        setProjectParameter(project.id, "COMPONENT_NAME", componentName)
        setProjectParameter(project.id, "PROJECT_VERSION", request.minorVersion)
        (
            listOfNotNull(component.componentOwner) +
                (component.releaseManager?.split(",") ?: emptyList())
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { assignProjectAdminRoleToUser(project.id, it) }
        return BuildChainResult(
            projectId = project.id,
            compileBuildTypeId = compileConfig.id,
            rcBuildTypeId = rcConfigId,
            checklistBuildTypeId = checklistConfigId,
            releaseBuildTypeId = releaseConfig.id,
        )
    }

    /** Read as `JDK_VERSION` is read today; absent on a parent project means no reserved value to collide with. */
    private fun reservedCheckoutDirectory(parentProjectId: String): String? =
        try {
            client.getParameter(ConfigurationType.PROJECT, parentProjectId, "RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR")
        } catch (e: FeignException.NotFound) {
            null
        }

    /**
     * WORK_DIR/COMPONENT_CONFIG_DIR on every created configuration when a Build Working Directory
     * is set. Also BUILD_VERSION_FORMAT_FILE, but only on a configuration that already has a
     * 'Calculate Build Version' step, which is where the compile templates are expected to read it.
     * The templates do not read it yet, so until they do the parameter is set but has no effect.
     */
    private fun applyBuildWorkingDirectory(
        buildTypeId: String,
        buildWorkingDirectory: String?,
    ) {
        buildWorkingDirectory?.let {
            val value = "%teamcity.build.checkoutDir%/$it"
            setBuildTypeParameter(buildTypeId, "WORK_DIR", value)
            setBuildTypeParameter(buildTypeId, "COMPONENT_CONFIG_DIR", value)
            val hasCalculateBuildVersionStep = client.getBuildSteps(buildTypeId).steps.any { step ->
                step.type ==
                    CALCULATE_BUILD_VERSION_STEP_TYPE
            }
            if (hasCalculateBuildVersionStep) {
                setBuildTypeParameter(buildTypeId, "BUILD_VERSION_FORMAT_FILE", "$it/build-version-format.properties")
            }
        }
    }

    private fun createBuildConf(
        templateId: String,
        name: String,
        projectId: String,
    ) = client.createBuildType(
        TeamcityCreateBuildType(
            template = TeamcityLinkBuildType(id = templateId),
            name = name,
            project = TeamcityLinkProject(id = projectId),
        ),
    )

    private fun addSnapshotDependency(
        buildType: TeamcityBuildType,
        sourceBuildType: TeamcityBuildType,
        onDependencyFailure: DependencyFailureAction,
    ) {
        client.createSnapshotDependency(
            buildType.id,
            TeamcitySnapshotDependency(
                id = requireNotNull(sourceBuildType.name) { "Build type name is null for ${sourceBuildType.id}" },
                type = "snapshot_dependency",
                properties = TeamcityProperties(
                    listOf(
                        TeamcityProperty("run-build-if-dependency-failed", onDependencyFailure.value),
                        TeamcityProperty("run-build-if-dependency-failed-to-start", onDependencyFailure.value),
                        TeamcityProperty("run-build-on-the-same-agent", "false"),
                        TeamcityProperty("take-started-build-with-same-revisions", "true"),
                        TeamcityProperty("take-successful-builds-only", "true"),
                    ),
                ),
                sourceBuildType = TeamcityLinkBuildType(sourceBuildType.id),
            ),
        )
    }

    private fun setBuildTypeParameter(
        buildTypeId: String,
        name: String,
        value: String,
    ) = client.setParameter(ConfigurationType.BUILD_TYPE, buildTypeId, name, value).also {
        log.info("Set parameter $name value $value for build configuration with id $buildTypeId")
    }

    private fun setProjectParameter(
        projectId: String,
        name: String,
        value: String,
    ) = client.setParameter(ConfigurationType.PROJECT, projectId, name, value).also {
        log.info("Set parameter $name value $value for project with id $projectId")
    }

    private fun assignProjectAdminRoleToUser(
        projectId: String,
        username: String,
    ) {
        try {
            client.assignProjectRoleToUser(username, TeamcityRole.PROJECT_ADMIN, projectId)
            log.info("Assigned PROJECT_ADMIN role to user $username for project $projectId")
        } catch (e: FeignException.NotFound) {
            log.warn("Failed to assign PROJECT_ADMIN role to user '{}' for project '{}': user not found in TeamCity", username, projectId)
        }
    }

    private fun disableBuildStep(
        buildTypeId: String,
        stepNameOrType: String,
        disable: Boolean = true,
    ) {
        client
            .getBuildSteps(buildTypeId)
            .steps
            .find { step -> step.name == stepNameOrType || step.type == stepNameOrType }
            ?.let { step -> client.disableBuildStep(buildTypeId, step.id, disable) }
            ?: log.warn("Skip disable build step '{}' not found for build type {}", stepNameOrType, buildTypeId)
    }

    private companion object {
        const val CALCULATE_BUILD_VERSION_STEP_TYPE = "CalculateBuildVersion"
    }
}
