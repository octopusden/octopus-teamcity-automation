package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.automation.teamcity.client.ComponentsRegistryApiClient
import org.octopusden.octopus.components.registry.core.dto.ComponentV3
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcitySnapshotDependencies
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcitySnapshotDependency
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.PropertyLocator
import org.slf4j.Logger

class TeamcityCreateEscrowConfigCommand : CliktCommand(name = COMMAND) {

    private val componentsRegistryUrl by option(COMPONENTS_REGISTRY_URL, help = "Components registry service URL")
        .convert { it.trim() }.required()
        .check("$COMPONENTS_REGISTRY_URL must not be empty") { it.isNotEmpty() }

    private val skip by option(SKIP, help = "Skip execution")
        .convert { it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$SKIP must be 'true' or 'false'") }
        .default(false)

    private val emulation by option(EMULATION, help = "Debug run")
        .convert { it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$EMULATION must be 'true' or 'false'") }
        .default(false)

    private val context by requireObject<MutableMap<String, Any>>()

    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }

    private val log by lazy { context[TeamcityCommand.LOG] as Logger }

    override fun run() {
        log.info("Executing $COMMAND")
        if (skip) {
            log.info("Skip task")
            return
        }
        if (emulation) {
            log.info("Emulation run")
        }
        val componentsRegistryApiClient = ComponentsRegistryApiClient(componentsRegistryUrl)
        componentsRegistryApiClient.getNotArchivedComponents().filter {
            val distribution = componentsRegistryApiClient.getComponent(it.component.id).distribution
            distribution?.external ?: false
        }.forEach { component ->
            val teamcityProjectWithSubprojects = getProjectByParametersWithSubprojects(component)
            val cdReleaseProjects: MutableMap<String, TeamcityProject> = HashMap()
            if (teamcityProjectWithSubprojects != null) {
                findAllProjectsWithCdRelease(teamcityProjectWithSubprojects, cdReleaseProjects)
            }
            cdReleaseProjects.entries
                .filter { filterComponents(it.value.id) }
                .forEach { teamcityProjects ->
                    if (!hasEscrowConfiguration(teamcityProjects.value)) {
                        log.info("Generate escrow configuration for projectId=${teamcityProjects.value.id}")
                        if (!emulation) {
                            val escrowConfig =
                                client.createBuildType(
                                    prepareEscrowBuildType(
                                        teamcityProjects.value,
                                        component.component.id
                                    )
                                )
                            log.info("Escrow configuration generated, escrow configurationId=${escrowConfig.id}")
                        }
                    }
                }
        }
    }

    private fun findAllProjectsWithCdRelease(teamcityProject: TeamcityProject, map: MutableMap<String, TeamcityProject>) {
        teamcityProject.projects?.projects?.forEach { findAllProjectsWithCdRelease(it, map) }
        if (teamcityProject.archived != true) {
            val hasCdRelease = teamcityProject.buildTypes
                ?.buildTypes
                ?.any { it.template?.id == CD_RELEASE_TEMPLATE_ID } == true
            if (hasCdRelease) {
                map[teamcityProject.id] = teamcityProject
            }
        }
    }

    private fun getProjectByParametersWithSubprojects(component: ComponentV3): TeamcityProject? {
        val propertyLocator = PropertyLocator(COMPONENT_NAME, component.component.id, PropertyLocator.MatchType.EQUALS, true)
        val fields = "project(id,name,webUrl,archived,href," +
                "buildTypes(buildType(id,name,projectId,projectName,href,template,vcs-root-entries))," +
                "projects(project(id,name,webUrl,archived,href," +
                "projects(project(id,name,webUrl,archived,href," +
                "buildTypes(buildType(id,name,projectId,projectName,href,template,vcs-root-entries)))))))"
        return client.getProjectsWithLocatorAndFields(ProjectLocator(parameter = listOf(propertyLocator)), fields).projects.firstOrNull()
    }

    private fun hasEscrowConfiguration(teamcityProject: TeamcityProject): Boolean {
        return teamcityProject.buildTypes
            ?.buildTypes?.any { it.template?.id == ESCROW_TEMPLATE_ID } ?: false
    }

    private fun prepareEscrowBuildType(teamcityProject: TeamcityProject, moduleName: String): TeamcityCreateBuildType {
        val escrowName = "Escrow Test ${teamcityProject.name} [AUTO]"
        val releaseBuildType = requireNotNull(
            teamcityProject.buildTypes
                ?.buildTypes
                ?.firstOrNull { it.template?.id == CD_RELEASE_TEMPLATE_ID }
        ) { "In project ${teamcityProject.id} buildType not found with template $CD_RELEASE_TEMPLATE_ID" }
        val snapshotDependencyProperties = TeamcityProperties(
            properties = listOf(
                TeamcityProperty(SNAPSHOT_PROPERTY_FAIL, MAKE_FAILED_TO_START),
                TeamcityProperty(SNAPSHOT_PROPERTY_FAIL_TO_START, MAKE_FAILED_TO_START),
                TeamcityProperty(SNAPSHOT_PROPERTY_SAME_AGENT, FALSE),
                TeamcityProperty(SNAPSHOT_PROPERTY_SUCCESS_ONLY, TRUE)
            )
        )
        val snapshotDependency = TeamcitySnapshotDependency(
            id = releaseBuildType.id,
            type = SNAPSHOT_DEPENDENCY_TYPE,
            sourceBuildType = TeamcityLinkBuildType(id = releaseBuildType.id),
            properties = snapshotDependencyProperties
        )
        val snapshotDependencies = TeamcitySnapshotDependencies(
            snapshotDependencies = listOf(snapshotDependency)
        )
        val params = TeamcityProperties(
            properties = listOf(
                TeamcityProperty(PROPERTY_BUILD_VERSION, "%dep.${releaseBuildType.id}.$PROPERTY_BUILD_VERSION%"),
                TeamcityProperty(PROPERTY_MODULES, "$moduleName:%$PROPERTY_BUILD_VERSION%"),
                TeamcityProperty(PROPERTY_PROJECT_NAME, PROPERTY_PROJECT_NAME_VALUE),
                TeamcityProperty(PROPERTY_VCS_RELATIVE_PATH, PROPERTY_VCS_RELATIVE_PATH_VALUE)
            )
        )
        return TeamcityCreateBuildType(
            name = escrowName,
            project = TeamcityLinkProject(id = teamcityProject.id),
            template = TeamcityLinkBuildType(id = ESCROW_TEMPLATE_ID),
            parameters = params,
            snapshotDependencies = snapshotDependencies
        )
    }

    private fun filterComponents(componentId: String): Boolean {
        return !(componentId.contains("docbot", true) ||
                componentId.contains("hotfix", true) ||
                componentId.contains("RnDProcessesAutomation_Way4", true))
    }

    companion object {
        const val COMMAND = "create-escrow-config"
        const val COMPONENTS_REGISTRY_URL = "--components-registry-url"
        const val SKIP = "--skip"
        const val EMULATION = "--emulation"

        // Teamcity properties
        const val SNAPSHOT_PROPERTY_FAIL = "run-build-if-dependency-failed"
        const val SNAPSHOT_PROPERTY_FAIL_TO_START = "run-build-if-dependency-failed-to-start"
        const val SNAPSHOT_PROPERTY_SAME_AGENT = "run-build-on-the-same-agent"
        const val SNAPSHOT_PROPERTY_SUCCESS_ONLY = "take-successful-builds-only"
        const val PROPERTY_BUILD_VERSION = "BUILD_VERSION"
        const val PROPERTY_MODULES = "MODULES"
        const val PROPERTY_PROJECT_NAME = "PROJECT_NAME"
        const val PROPERTY_VCS_RELATIVE_PATH = "VCS_RELATIVE_PATH"

        // Teamcity default values
        const val MAKE_FAILED_TO_START = "MAKE_FAILED_TO_START"
        const val TRUE = "true"
        const val FALSE = "false"
        const val PROPERTY_PROJECT_NAME_VALUE = "escrow-runner"
        const val PROPERTY_VCS_RELATIVE_PATH_VALUE = "f1/escrow-generator"

        // Teamcity template ids
        const val CD_RELEASE_TEMPLATE_ID = "CDRelease"
        const val ESCROW_TEMPLATE_ID = "EscrowAutomation_EscrowRunnerTemplate"

        const val COMPONENT_NAME = "COMPONENT_NAME"
        const val SNAPSHOT_DEPENDENCY_TYPE = "snapshot_dependency"
    }
}