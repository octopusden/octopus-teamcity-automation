package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import feign.FeignException
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import org.octopusden.octopus.components.registry.core.dto.BuildSystem
import org.octopusden.octopus.components.registry.core.dto.DetailedComponent
import org.octopusden.octopus.components.registry.core.dto.RepositoryType
import org.octopusden.octopus.components.registry.core.dto.VersionControlSystemRootDTO
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityRole
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityVCSType
import org.octopusden.octopus.infrastructure.teamcity.client.createBuildTypeVcsRootEntry
import org.octopusden.octopus.infrastructure.teamcity.client.createSnapshotDependency
import org.octopusden.octopus.infrastructure.teamcity.client.disableBuildStep
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
import org.octopusden.octopus.infrastructure.teamcity.client.getBuildSteps
import org.octopusden.octopus.infrastructure.teamcity.client.getProject
import org.slf4j.Logger

class TeamcityCreateBuildChainCommand : CliktCommand(name = COMMAND) {
    private val context by requireObject<MutableMap<String, Any>>()

    private val parentProjectId by option(PARENT, help = "Teamcity parent project Id")
        .convert { it.trim() }
        .required()
        .check("$PARENT is empty") { it.isNotEmpty() }

    private val componentName by option(COMPONENT, help = "Component registry name")
        .convert { it.trim() }
        .required()
        .check("$COMPONENT is empty") { it.isNotEmpty() }

    private val minorVersion by option(VERSION, help = "Minor version")
        .convert { it.trim() }
        .required()
        .check("$VERSION is empty") { it.isNotEmpty() }

    private val componentsRegistryUrl by option(CR, help = "Components Registry service Url")
        .required()
        .check("$CR is empty") { it.isNotEmpty() }

    private val createChecklist by option(CREATE_CHECKLIST, help = "Generate check list validation")
        .convert { it.trim().toBoolean() }
        .default(true)

    private val createRcForce by option(CREATE_RC_FORCE, help = "Force generate RC for non EE components")
        .convert { it.trim().toBoolean() }
        .default(false)

    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }
    private val log by lazy { context[TeamcityCommand.LOG] as Logger }

    /** One registry VCS root placed on a created TeamCity VCS root (ADR-001). */
    private data class PlacedRoot(
        val position: Int,
        val vcsRoot: TeamcityVcsRoot,
        val checkoutRule: String?,
    )

    override fun run() {
        log.info("Create build chain")
        val parentProject = client.getProject(parentProjectId)
        val componentsRegistryClient = ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl(): String = componentsRegistryUrl
            },
        )
        val detailedComponent = componentsRegistryClient.getDetailedComponent(componentName, minorVersion)
        createBuildChain(parentProject, detailedComponent)
    }

    private fun createBuildChain(
        parentProject: TeamcityProject,
        component: DetailedComponent,
    ) {
        val registryRoots = component.vcsSettings.versionControlSystemRoots
        validatePlacement(registryRoots, reservedCheckoutDirectory())

        val project = client.createProject(
            TeamcityCreateProject(name = componentName, parentProject = TeamcityLinkProject(id = parentProject.id)),
        )
        val buildWorkingDirectory = component.vcsSettings.buildWorkingDirectory?.takeIf { it.isNotBlank() }
        val placedRoots = createVcsRoots(project.id, registryRoots)
        val attachOrder = attachOrder(registryRoots, buildWorkingDirectory)
        log.info(
            "Attach order for '{}': {}",
            componentName,
            attachOrder.map { placedRoots.getValue(it).vcsRoot.name },
        )
        if (buildWorkingDirectory != null) {
            log.warn(
                "Component '{}': Build Working Directory is '{}'; the 'Calculate Build Version' step still reads " +
                    "build-version-format.properties at the checkout root, not inside the Build Working Directory " +
                    "(ADR-001 open question: version-format file outside the checkout root)",
                componentName,
                buildWorkingDirectory,
            )
        }

        var counter = 0
        val compileConfig = createBuildConf(
            when (component.buildSystem) {
                BuildSystem.MAVEN -> TEMPLATE_MAVEN_COMPILE
                BuildSystem.GRADLE -> TEMPLATE_GRADLE_COMPILE
                BuildSystem.PROVIDED -> TEMPLATE_GRADLE_COMPILE
                BuildSystem.IN_CONTAINER -> TEMPLATE_GRADLE_COMPILE
                else -> throw NotFoundException("Unsupported build system: ${component.buildSystem.name}")
            },
            "[${++counter}.0] Compile & UT [AUTO]",
            project.id,
        )
        attachVcsRoots(compileConfig.id, attachOrder, placedRoots)
        applyBuildWorkingDirectory(compileConfig.id, buildWorkingDirectory)
        val defaultJDKVersion = client.getParameter(ConfigurationType.PROJECT, parentProjectId, "JDK_VERSION")
        component.buildParameters?.javaVersion?.takeIf { it != defaultJDKVersion }?.let { projectJDKVersion ->
            setBuildTypeParameter(compileConfig.id, "JDK_VERSION", projectJDKVersion)
        }
        val releaseConfig =
            if ((component.distribution?.explicit == true && component.distribution?.external == true) || createRcForce) {
                val rcConfig = createBuildConf(
                    TEMPLATE_RC,
                    "[${++counter}.0] Release Candidate [Manual]",
                    project.id,
                )
                attachVcsRoots(rcConfig.id, attachOrder, placedRoots)
                applyBuildWorkingDirectory(rcConfig.id, buildWorkingDirectory)

                if (createChecklist) {
                    val checklistConfig = createBuildConf(
                        TEMPLATE_CHECKLIST,
                        "[${++counter}.0] Release Checklist Validation [MANUAL]",
                        project.id,
                    )
                    attachVcsRoots(checklistConfig.id, attachOrder, placedRoots)
                    applyBuildWorkingDirectory(checklistConfig.id, buildWorkingDirectory)
                    addSnapshotDependency(checklistConfig, rcConfig, DependencyFailureAction.CANCEL)
                    setBuildTypeParameter(
                        checklistConfig.id,
                        "BUILD_VERSION",
                        "%dep.${compileConfig.id}.BUILD_VERSION%",
                    )
                }

                val releaseConfig = createBuildConf(
                    TEMPLATE_RELEASE,
                    "[${++counter}.0] Release [Manual]",
                    project.id,
                )
                attachVcsRoots(releaseConfig.id, attachOrder, placedRoots)
                applyBuildWorkingDirectory(releaseConfig.id, buildWorkingDirectory)

                addSnapshotDependency(rcConfig, compileConfig, DependencyFailureAction.CANCEL)
                addSnapshotDependency(releaseConfig, rcConfig, DependencyFailureAction.CANCEL)

                setBuildTypeParameter(rcConfig.id, "BUILD_VERSION", "%dep.${compileConfig.id}.BUILD_VERSION%")
                releaseConfig
            } else {
                val releaseConfig = createBuildConf(
                    TEMPLATE_RELEASE,
                    "[${++counter}.0] Release [Manual]",
                    project.id,
                )
                attachVcsRoots(releaseConfig.id, attachOrder, placedRoots)
                applyBuildWorkingDirectory(releaseConfig.id, buildWorkingDirectory)
                addSnapshotDependency(releaseConfig, compileConfig, DependencyFailureAction.CANCEL)
                releaseConfig
            }
        disableBuildStep(releaseConfig.id, "IncrementTeamCityBuildConfigurationParameter")
        setBuildTypeParameter(releaseConfig.id, "BUILD_VERSION", "%dep.${compileConfig.id}.BUILD_VERSION%")
        setBuildTypeParameter(releaseConfig.id, "BASE_CONFIGURATION_ID", compileConfig.id)
        setProjectParameter(project.id, "COMPONENT_NAME", componentName)
        setProjectParameter(project.id, "PROJECT_VERSION", minorVersion)
        (
            listOfNotNull(component.componentOwner) +
                (component.releaseManager?.split(",") ?: emptyList())
        ).map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { assignProjectAdminRoleToUser(project.id, it) }
    }

    /**
     * Checks the shapes the generator does not support (ADR-001): more than one root at the checkout
     * root, a repeated repository, a non-Git root and a Checkout Directory colliding with the
     * project's helper clone directory. Runs before any TeamCity object is created.
     */
    private fun validatePlacement(
        roots: List<VersionControlSystemRootDTO>,
        reservedCheckoutDirectory: String?,
    ) {
        roots.forEachIndexed { index, root ->
            if (root.type != RepositoryType.GIT) {
                throw UnsupportedOperationException(
                    "Component '$componentName': VCS root at position ${index + 1} ('${root.name}') has " +
                        "unsupported type ${root.type}, only Git roots are supported",
                )
            }
        }
        val rootsAtCheckoutRoot = roots.withIndex().filter { it.value.checkoutDirectory.isNullOrBlank() }
        if (rootsAtCheckoutRoot.size > 1) {
            throw UnsupportedOperationException(
                "Component '$componentName': more than one VCS root has no Checkout Directory " +
                    "(positions ${rootsAtCheckoutRoot.joinToString { (it.index + 1).toString() }}), " +
                    "at most one root may be checked out at the checkout root",
            )
        }
        roots.withIndex()
            .groupBy { it.value.vcsPath.trim().lowercase() }
            .values
            .firstOrNull { it.size > 1 }
            ?.let { duplicates ->
                throw UnsupportedOperationException(
                    "Component '$componentName': VCS roots at positions " +
                        duplicates.joinToString { (it.index + 1).toString() } +
                        " point at the same repository '${duplicates.first().value.vcsPath}'",
                )
            }
        if (reservedCheckoutDirectory != null) {
            roots.withIndex().firstOrNull { it.value.checkoutDirectory == reservedCheckoutDirectory }?.let { (index, root) ->
                throw UnsupportedOperationException(
                    "Component '$componentName': Checkout Directory '$reservedCheckoutDirectory' of VCS root at " +
                        "position ${index + 1} ('${root.name}') collides with the helper clone directory " +
                        "RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR",
                )
            }
        }
    }

    /** Read as `JDK_VERSION` is read today; absent on a parent project means no reserved value to collide with. */
    private fun reservedCheckoutDirectory(): String? =
        try {
            client.getParameter(ConfigurationType.PROJECT, parentProjectId, "RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR")
        } catch (e: FeignException.NotFound) {
            null
        }

    /**
     * The root that holds the Build Working Directory, else the root without a Checkout Directory,
     * else registry position 1 (ADR-001 decision 6). Returns registry positions (1-based) in attach
     * order.
     */
    private fun attachOrder(
        roots: List<VersionControlSystemRootDTO>,
        buildWorkingDirectory: String?,
    ): List<Int> {
        val positions = roots.indices.map { it + 1 }
        val rootWithoutCheckoutDirectory = positions.firstOrNull { roots[it - 1].checkoutDirectory.isNullOrBlank() }
        val firstPosition = buildWorkingDirectory
            ?.substringBefore("/")
            ?.let { firstSegment -> positions.firstOrNull { roots[it - 1].checkoutDirectory == firstSegment } }
            ?: rootWithoutCheckoutDirectory
            ?: positions.firstOrNull()
            ?: return emptyList()
        return listOf(firstPosition) + positions.filter { it != firstPosition }
    }

    /** No rule with neither field; `+:. => cd`; `+:sp => cd/sp`; `+:sp => sp` with only Source Path. */
    private fun checkoutRule(
        checkoutDirectory: String?,
        sourcePath: String?,
    ): String? {
        val cd = checkoutDirectory?.takeIf { it.isNotBlank() }
        val sp = sourcePath?.takeIf { it.isNotBlank() }
        return when {
            cd == null && sp == null -> null
            cd != null && sp == null -> "+:. => $cd"
            cd != null -> "+:$sp => $cd/$sp"
            else -> "+:$sp => $sp"
        }
    }

    private fun createVcsRoots(
        projectId: String,
        roots: List<VersionControlSystemRootDTO>,
    ): Map<Int, PlacedRoot> =
        roots.withIndex().associate { (index, rootData) ->
            val position = index + 1
            val vcsRootName = if (position == 1) "${projectId}_VCS_ROOT" else "${projectId}_VCS_ROOT_$position"
            val rule = checkoutRule(rootData.checkoutDirectory, rootData.sourcePath)
            val defaultBranch = rootData.branch.substringBefore("|").trim()
            val vcsRoot = when (rootData.type) {
                RepositoryType.GIT -> client.createVcsRoot(
                    TeamcityCreateVcsRoot(
                        name = vcsRootName,
                        vcsName = TeamcityVCSType.GIT.value,
                        projectLocator = projectId,
                        TeamcityProperties(
                            listOf(
                                TeamcityProperty("url", rootData.vcsPath),
                                TeamcityProperty("branch", defaultBranch),
                                TeamcityProperty("teamcity:branchSpec", "+:<default>"),
                                TeamcityProperty("authMethod", "PRIVATE_KEY_DEFAULT"),
                                TeamcityProperty("userForTags", "tcagent"),
                                TeamcityProperty("username", "git"),
                                TeamcityProperty("ignoreKnownHosts", "true"),
                            ),
                        ),
                    ),
                )
                else -> throw NotFoundException("Unsupported vcs type: ${rootData.type}")
            }
            log.info(
                "Created VCS root '{}' (registry position {}) url='{}' branch='{}' checkoutDirectory='{}' " +
                    "sourcePath='{}' -> checkout rule '{}'",
                vcsRoot.name,
                position,
                rootData.vcsPath,
                defaultBranch,
                rootData.checkoutDirectory,
                rootData.sourcePath,
                rule ?: "(none)",
            )
            if (defaultBranch.contains("null")) {
                log.warn(
                    "VCS root '{}': default branch '{}' contains an unresolved placeholder",
                    vcsRoot.name,
                    defaultBranch,
                )
            }
            position to PlacedRoot(position, vcsRoot, rule)
        }

    private fun attachVcsRoots(
        buildTypeId: String,
        attachOrder: List<Int>,
        placedRoots: Map<Int, PlacedRoot>,
    ) {
        if (attachOrder.isEmpty()) {
            log.info("Skip attach vcs root to {}", buildTypeId)
            return
        }
        attachOrder.forEach { position ->
            val placedRoot = placedRoots.getValue(position)
            client.createBuildTypeVcsRootEntry(
                buildTypeId,
                TeamcityCreateVcsRootEntry(
                    id = placedRoot.vcsRoot.id,
                    vcsRoot = TeamcityLinkVcsRoot(placedRoot.vcsRoot.id),
                    checkoutRules = placedRoot.checkoutRule ?: "",
                ),
            )
        }
    }

    private fun applyBuildWorkingDirectory(
        buildTypeId: String,
        buildWorkingDirectory: String?,
    ) {
        buildWorkingDirectory?.let {
            val value = "%teamcity.build.checkoutDir%/$it"
            setBuildTypeParameter(buildTypeId, "WORK_DIR", value)
            setBuildTypeParameter(buildTypeId, "COMPONENT_CONFIG_DIR", value)
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

    companion object {
        const val COMMAND = "create-build-chain"
        const val PARENT = "--parent-project-id"
        const val COMPONENT = "--component"
        const val VERSION = "--minor-version"
        const val CR = "--registry-url"
        const val CREATE_CHECKLIST = "--create-checklist"
        const val CREATE_RC_FORCE = "--create-rc-force"

        const val TEMPLATE_GRADLE_COMPILE = "CDGradleBuild"
        const val TEMPLATE_MAVEN_COMPILE = "CDJavaMavenBuild"
        const val TEMPLATE_RC = "CdReleaseCandidateNew"
        const val TEMPLATE_CHECKLIST = "CdReleaeChecklistValidation"
        const val TEMPLATE_RELEASE = "CDRelease"
    }
}
