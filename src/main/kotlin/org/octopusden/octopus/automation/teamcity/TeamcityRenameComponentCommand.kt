package org.octopusden.octopus.automation.teamcity

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.automation.teamcity.client.ComponentsRegistryApiClient
import org.octopusden.octopus.automation.teamcity.client.DmsApiClient
import org.octopusden.octopus.automation.teamcity.client.ReleaseManagementApiClient
import org.octopusden.octopus.automation.teamcity.client.JiraSdApiClient
import org.octopusden.octopus.automation.teamcity.client.VcsFacadeApiClient
import org.octopusden.octopus.infrastructure.teamcity.client.ConfigurationType
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.PropertyLocator
import org.octopusden.octopus.infrastructure.teamcity.client.getProject
import org.slf4j.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class TeamcityRenameComponentCommand : CliktCommand(name = COMMAND) {

    private val componentName by option(COMPONENT_NAME, help = "Component name")
        .convert { it.trim() }.required()
        .check("$COMPONENT_NAME must not be empty") { it.isNotEmpty() }

    private val componentNewName by option(COMPONENT_NEW_NAME, help = "Component new name (must be registered in Component Registry)")
        .convert { it.trim() }.required()
        .check("$COMPONENT_NEW_NAME must not be empty") { it.isNotEmpty() }

    private val releaseManagementUrl by option(RELEASE_MANAGEMENT_URL, help = "Release management service URL")
        .convert { it.trim() }.required()
        .check("$RELEASE_MANAGEMENT_URL must not be empty") { it.isNotEmpty() }

    private val componentsRegistryUrl by option(COMPONENTS_REGISTRY_URL, help = "Components registry service URL")
        .convert { it.trim() }.required()
        .check("$COMPONENTS_REGISTRY_URL must not be empty") { it.isNotEmpty() }

    private val dmsUrl by option(DMS_URL, help = "DMS service URL")
        .convert { it.trim() }.required()
        .check("$DMS_URL must not be empty") { it.isNotEmpty() }

    private val dmsUsername by option(DMS_USERNAME, help = "DMS service username")
        .convert { it.trim() }.required()
        .check("$DMS_USERNAME must not be empty") { it.isNotEmpty() }

    private val dmsPassword by option(DMS_PASSWORD, help = "DMS service password")
        .convert { it.trim() }.required()
        .check("$DMS_PASSWORD must not be empty") { it.isNotEmpty() }

    private val gitServerUrl by option(GIT_SERVER_URL, help = "Git server service URL")
        .convert { it.trim() }.required()
        .check("$GIT_SERVER_URL must not be empty") { it.isNotEmpty() }

    private val vcsFacadeUrl by option(VCS_FACADE_URL, help = "VCS facade service URL")
        .convert { it.trim() }.required()
        .check("$VCS_FACADE_URL must not be empty") { it.isNotEmpty() }

    private val sdUrl by option(SD_URL, help = "Jira SD URL")
        .convert { it.trim() }.required()
        .check("$SD_URL must not be empty") { it.isNotEmpty() }

    private val sdUsername by option(SD_USERNAME, help = "Jira SD username")
        .convert { it.trim() }.required()
        .check("$SD_USERNAME must not be empty") { it.isNotEmpty() }

    private val sdPassword by option(SD_PASSWORD, help = "Jira SD password")
        .convert { it.trim() }.required()
        .check("$SD_PASSWORD must not be empty") { it.isNotEmpty() }

    private val infraGitUrl by option(INFRA_GIT_URL, help = "Git URL for ci-cd-infra-selfservice repository")
        .convert { it.trim() }.required()
        .check("$INFRA_GIT_URL must not be empty") { it.isNotEmpty() }

    private val infraConfigPath by option(INFRA_CONFIG_PATH, help = "Path to config file in ci-cd-infra-selfservice repository")
        .convert { it.trim() }.required()
        .check("$INFRA_CONFIG_PATH must not be empty") { it.isNotEmpty() }

    private val gitUsername by option(GIT_USERNAME, help = "Git username")
        .convert { it.trim() }
        .default("git")

    private val prTargetBranch by option(PR_TARGET_BRANCH, help = "Target branch name for PR")
        .convert { it.trim() }
        .default("master")

    private val reRun by option(RE_RUN, help = "Allow re-run if some steps were completed but some failed")
        .convert { it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$RE_RUN must be 'true' or 'false'") }
        .default(false)

    private val context by requireObject<MutableMap<String, Any>>()

    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }

    private val log by lazy { context[TeamcityCommand.LOG] as Logger }

    override fun run() {
        log.info("Executing $COMMAND")
        val componentsRegistryApiClient = ComponentsRegistryApiClient(componentsRegistryUrl)
        val dmsApiClient = DmsApiClient(dmsUrl, dmsUsername, dmsPassword)
        val jiraSdApiClient = JiraSdApiClient(sdUrl, sdUsername, sdPassword)
        val releaseManagementApiClient = ReleaseManagementApiClient(releaseManagementUrl)
        val vcsFacadeApiClient = VcsFacadeApiClient(vcsFacadeUrl)

        log.info("Search $componentName in components registry service")
        val component = safeCall("ComponentsRegistry") { componentsRegistryApiClient.getComponent(componentName) } ?: return
        log.info("Found component: ${component.name}")

        log.info("Renaming in release management service")
        safeCall("ReleaseManagement") { releaseManagementApiClient.renameComponent(componentName, componentNewName) }

        log.info("Renaming in Teamcity")
        safeCall("Teamcity") { renameComponentInTeamcity(componentName, componentNewName) }

        log.info("Renaming in DMS")
        val dmsSucceeded = safeCall("DMS") {
            dmsApiClient.renameComponent(componentName, componentNewName)
            true
        } ?: false
        if (dmsSucceeded && component.distribution!!.external && component.distribution!!.explicit ) {
            log.info("Notifying CDT about rename")
            val description = buildJsonDescription(componentName, componentNewName)
            val summary = "Delivery Tool: rename component $componentName to $componentNewName"
            val issueKey = jiraSdApiClient.createSdIssue(summary, description)
            if (doGitCommands(componentName, componentNewName, issueKey)) {
                Thread.sleep(10000L)
                val pullRequest = vcsFacadeApiClient.createPullRequest(
                    infraGitUrl,
                    issueKey,
                    prTargetBranch,
                    "$issueKey rename component $componentName to $componentNewName",
                    ""
                )
                val newDescription = description + "\nsee proposed PR ${pullRequest.link} as a possible source of changes\""
                jiraSdApiClient.updateIssueDescription(issueKey, newDescription)
            }
        }
    }

    private fun renameComponentInTeamcity(componentName: String, componentNewName: String) {
        client.getProjects(
            ProjectLocator(parameter = listOf(PropertyLocator(name = COMPONENT_NAME_PARAMETER, value = componentName)))
        ).projects.forEach { project ->
            val oldName = client.getParameter(ConfigurationType.PROJECT, project.id, COMPONENT_NAME_PARAMETER)
            if (oldName == componentName) {
                updateProjectParameterAndNameInTeamcity(project, componentName, componentNewName)
            } else {
                log.info("Project ${project.id} parameter $COMPONENT_NAME_PARAMETER is not $componentName")
            }
        }
    }

    private fun updateProjectParameterAndNameInTeamcity(project: TeamcityProject, parameterValue: String, parameterNewValue: String) {
        try {
            client.setParameter(ConfigurationType.PROJECT, project.id, COMPONENT_NAME_PARAMETER, parameterNewValue)
            log.info("Project ${project.id} parameter $COMPONENT_NAME_PARAMETER updated from $parameterValue to $parameterNewValue")
            val currentProject = client.getProject(project.id)
            val currentProjectName = currentProject.name
            if (currentProjectName.contains(parameterValue, ignoreCase = true)) {
                val newProjectName = currentProjectName.replace(parameterValue, parameterNewValue, ignoreCase = true)
                client.setParameter(ConfigurationType.PROJECT, project.id, PROJECT_NAME_PARAMETER, newProjectName)
                log.info("Project ${project.id} renamed from $currentProjectName to $newProjectName")
            }
        } catch (e: Exception) {
            log.error("Error renaming, it might be caused by read-only parameter - ${e.message}. Skipping TC project ${project.id}")
        }
    }

    private fun doGitCommands(componentName: String, newName: String, branch: String): Boolean {
        exec(arrayOf("git", "clone", infraGitUrl, REPOSITORY_DIR, "--config", "user.name=$gitUsername"), ".")
        exec(arrayOf("git", "checkout", "-b", prTargetBranch))

        editFile(componentName, newName)

        exec(arrayOf("git", "add", "."))
        val commitResult =
            exec(arrayOf("git", "commit", "-m", "$branch rename component $componentName to $newName")).exitCode
        if (commitResult == 0) {
            exec(arrayOf("git", "push", "origin", branch))
            return true
        } else {
            log.info("No changes in the file, nothing to commit")
            return false
        }
    }

    private fun exec(args: Array<String>, dir: String = REPOSITORY_DIR): ExecutionResult {
        log.info("Exec " + args.joinToString(" "))
        val process = ProcessBuilder(args.toList()).directory(File(dir)).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = ArrayList<String>()
        reader.forEachLine {
            log.info("..  $it")
            output.add(it)
        }
        process.waitFor()
        return ExecutionResult(process.exitValue(), output)
    }

    private fun editFile(componentName: String, newName: String) {
        val strToReplace = "\"$componentName\": {"
        val strReplacer = "\"$newName\": {"
        val file = File("$REPOSITORY_DIR/$infraConfigPath")
        val text = file.readText()
        val newText = text.replace(strToReplace, strReplacer)
        file.writeText(newText)
    }

    private fun <T> safeCall(name: String, func: () -> T): T? {
        return runCatching(func)
            .onFailure { e -> log.error("Error $name: ${e.message}", e) }
            .getOrElse { e ->
                if (!reRun) throw e
                null
            }
    }

    private fun buildJsonDescription(oldName: String, newName: String): String {
        val json = ObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(mapOf(oldName to newName))
        return "{noformat}$json{noformat}"
    }

    companion object {
        const val COMMAND = "rename-component"
        const val COMPONENT_NAME = "--component-name"
        const val COMPONENT_NEW_NAME = "--component-new-name"
        const val RELEASE_MANAGEMENT_URL = "--release-management-url"
        const val COMPONENTS_REGISTRY_URL = "--components-registry-url"
        const val DMS_URL = "--dms-url"
        const val DMS_USERNAME = "--dms-username"
        const val DMS_PASSWORD = "--dms-password"
        const val GIT_SERVER_URL = "--git-server-url"
        const val VCS_FACADE_URL = "--vcs-facade-url"
        const val SD_URL = "--sd-url"
        const val SD_USERNAME = "--sd-username"
        const val SD_PASSWORD = "--sd-password"
        const val INFRA_GIT_URL = "--infra-git-url"
        const val INFRA_CONFIG_PATH = "--infra-config-path"
        const val GIT_USERNAME = "--git-username"
        const val PR_TARGET_BRANCH = "--pr-target-branch"
        const val RE_RUN = "--re-run"

        const val REPOSITORY_DIR = "cloned-repo"
        const val COMPONENT_NAME_PARAMETER = "COMPONENT_NAME"
        const val PROJECT_NAME_PARAMETER = "name"

        data class ExecutionResult(val exitCode: Int, val output: List<String>)
    }
}