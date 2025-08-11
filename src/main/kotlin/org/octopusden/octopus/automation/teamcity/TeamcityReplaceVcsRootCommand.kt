package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRootEntry
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.BuildTypeLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.PropertyLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.VcsRootInstanceLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.VcsRootLocator
import org.slf4j.Logger
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID

class TeamcityReplaceVcsRootCommand : CliktCommand(name = COMMAND) {

    private val oldVcsRoot by option(OLD_VCS_ROOT, help = "Old Git repository URL")
        .convert { it.trim() }.required()
        .check("$OLD_VCS_ROOT must not be empty") { it.isNotEmpty() }

    private val newVcsRoot by option(NEW_VCS_ROOT, help = "New Git repository URL")
        .convert { it.trim() }.required()
        .check("$NEW_VCS_ROOT must not be empty") { it.isNotEmpty() }

    private val jiraMessageFile by option(JIRA_MESSAGE_FILE, help = "Path to a file where the operation log will be written (defaults to 'build/vcs-replace-messages.txt')")
        .path(mustExist = false, canBeDir = false)
        .default(Paths.get("build", "vcs-replace-messages.txt"))

    private val emulation by option(EMULATION, help = "Debug run")
        .convert { it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$EMULATION must be 'true' or 'false'") }
        .default(true)

    private val context by requireObject<MutableMap<String, Any>>()

    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }

    private val log by lazy { context[TeamcityCommand.LOG] as Logger }

    override fun run() {
        log.info("Executing $COMMAND")
        val messages = mutableListOf<String>()
        updateExplicitGitVcsRoot(oldVcsRoot, newVcsRoot, messages)
        replaceGenericVcsRoots(oldVcsRoot, newVcsRoot, messages)
        Files.createDirectories(jiraMessageFile.parent)
        Files.write(jiraMessageFile, messages.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
    }

    private fun updateExplicitGitVcsRoot(oldVcsRoot: String, newVcsRoot: String, messages: MutableList<String>) {
        val locator = VcsRootLocator(
            property = listOf(PropertyLocator(name = PROPERTY_URL, value = oldVcsRoot, matchType = PropertyLocator.MatchType.EQUALS, ignoreCase = true))
        )
        val roots = client.getVcsRoots(locator).vcsRoots
        if (roots.isNotEmpty()) {
            messages += "(flag) {color:#ff0000} Git VCS Root update report {color}\r\n"
        }
        roots.forEach { root ->
            if (!emulation) {
                client.updateVcsRootProperty(root.id, PROPERTY_URL, newVcsRoot)
                runCatching { client.getVcsRootProperty(root.id, PROPERTY_PUSH_URL) }
                    .onSuccess { client.updateVcsRootProperty(root.id, PROPERTY_PUSH_URL, newVcsRoot) }
            }
            messages += "Updated Git VCS Root: id=${root.id}, name=${root.name}"
        }
    }

    private fun replaceGenericVcsRoots(oldVcsRoot: String, newVcsRoot: String, messages: MutableList<String>) {
        val index = findVcsRootInstancesRootIdsByUrl(oldVcsRoot)
        if (index.rootIds.isEmpty() || index.byBuildType.isEmpty()) {
            log.info("No build configurations referencing $oldVcsRoot found")
            return
        }
        messages += "(flag) {color:#ff0000} VCS Root replacing report {color}\r\n"

        index.byBuildType.forEach { (buildTypeLocator, entriesToDetach) ->
            val buildType = client.getBuildType(buildTypeLocator)
            val projectId = buildType.projectId
            val branch = extractBranchFromBuildType(buildType)
            val newVcs = findOrCreateGitVcsRootInProject(projectId, newVcsRoot, branch, messages)

            val toDetach = client.getBuildTypeVcsRootEntries(buildTypeLocator)
                .entries
                .filter { entriesToDetach.contains(it.id) || entriesToDetach.contains(it.vcsRoot.id) }
            val checkoutRules = toDetach.firstOrNull()?.checkoutRules ?: ""
            val createEntry = TeamcityCreateVcsRootEntry(
                id = newVcs.id,
                vcsRoot = TeamcityLinkVcsRoot(id = newVcs.id),
                checkoutRules = checkoutRules
            )
            if (!emulation) {
                client.createBuildTypeVcsRootEntry(buildTypeLocator, createEntry)
            }
            messages += "Attached VCS Root: buildTypeId=${buildType.id}, buildTypeName=${buildType.name}, vcsRootId=${newVcs.id}, vcsRootName=${newVcs.name}, checkoutRules='${checkoutRules}'"
            migrateVcsLabeling(buildType.id, toDetach.map { it.vcsRoot.id }.toSet(), newVcs.id, messages)
            toDetach.forEach { oldEntry ->
                if (!emulation) {
                    client.deleteBuildTypeVcsRootEntry(buildTypeLocator, oldEntry.id)
                }
                messages += "Detached VCS Root: buildTypeId=${buildType.id}, buildTypeName=${buildType.name}, vcsRootId=${oldEntry.vcsRoot.id}, vcsRootName=${oldEntry.vcsRoot.name}"
            }
            log.info("Switched VCS: buildType=${buildType.id}-${buildType.name} -> root=${newVcs.id}-${newVcs.name}, checkoutRules='$checkoutRules' (emulation = $emulation)")
        }
    }

    private fun findVcsRootInstancesRootIdsByUrl(url: String): InstancesIndex {
        val propertyLocator = PropertyLocator(name = PROPERTY_URL, value = url, matchType = PropertyLocator.MatchType.EQUALS, ignoreCase = true)
        val allInstances = client.getVcsRootInstances(VcsRootInstanceLocator(property = listOf(propertyLocator))).vcsRootInstances
        val rootIds = allInstances.map { it.vcsRootId }.toSet()
        if (rootIds.isEmpty()) {
            return InstancesIndex(emptySet(), emptyMap())
        }
        val fields = "buildType(id,name,projectId,projectName,webUrl,vcs-root-entries(id,vcs-root(id,name,href),checkout-rules))"
        val buildTypes = client.getBuildTypesWithVcsRootInstanceLocatorAndFields(VcsRootInstanceLocator(property = listOf(propertyLocator)), fields)
            .buildTypes
            .distinctBy { it.id }
        val byBuild = buildTypes.associateBy(
            { BuildTypeLocator(id = it.id) },
            { buildType ->
                (buildType.vcsRoots?.entries ?: emptyList())
                    .filter { e -> rootIds.contains(e.vcsRoot.id) || rootIds.contains(e.id) }
                    .map { it.id }
                    .toSet()
            }
        ).filterValues { it.isNotEmpty() }
        return InstancesIndex(rootIds, byBuild)
    }

    private fun extractBranchFromBuildType(bt: TeamcityBuildType): String {
        val raw = bt.parameters?.properties?.firstOrNull { it.name == PROPERTY_BUILD_TYPE_BRANCH }?.value?.trim()
        if (raw.isNullOrEmpty()) {
            return "refs/heads/master"
        }
        return if (raw.startsWith("refs/")) raw else "refs/heads/$raw"
    }

    private fun findOrCreateGitVcsRootInProject(projectId: String, newVcsUrl: String, branch: String, messages: MutableList<String>): TeamcityVcsRoot {
        val candidates = client.getVcsRoots(
            VcsRootLocator(
                project = ProjectLocator(id = projectId),
                property = listOf(
                    PropertyLocator(name = PROPERTY_URL, value = newVcsUrl, matchType = PropertyLocator.MatchType.EQUALS, ignoreCase = true),
                    PropertyLocator(name = PROPERTY_BRANCH, value = branch, matchType = PropertyLocator.MatchType.EQUALS, ignoreCase = true)
                )
            )
        ).vcsRoots
        if (candidates.isNotEmpty()) {
            val existing = client.getVcsRoot(VcsRootLocator(id = candidates.first().id))
            messages += "Found existing VCS Root: projectId=$projectId, vcsRootId=${existing.id}, vcsRootName=${existing.name}, branch=$branch"
            return existing
        }
        val name = generateVcsRootName(newVcsUrl)
        val props = TeamcityProperties(
            properties = mutableListOf(
                TeamcityProperty(PROPERTY_URL, newVcsUrl),
                TeamcityProperty(PROPERTY_BRANCH, branch),
                TeamcityProperty(PROPERTY_BRANCH_SPEC, DEFAULT_BRANCH_SPEC),
                TeamcityProperty(PROPERTY_USERNAME, DEFAULT_GIT_USERNAME),
                TeamcityProperty(PROPERTY_AUTH_METHOD, DEFAULT_AUTH_PRIVATE_KEY),
                TeamcityProperty(PROPERTY_USERNAME_STYLE, USERNAME_STYLE_USERID),
                TeamcityProperty(PROPERTY_SUBMODULE_CHECKOUT, SUBMODULE_IGNORE),
                TeamcityProperty(PROPERTY_IGNORE_KNOWN_HOSTS, TRUE),
                TeamcityProperty(PROPERTY_AGENT_CLEAN_FILES_POLICY, CLEAN_FILES_ALL_UNTRACKED),
                TeamcityProperty(PROPERTY_AGENT_CLEAN_POLICY, CLEAN_ON_BRANCH_CHANGE),
            )
        )
        if (emulation) {
            val fake = TeamcityVcsRoot(
                id = "emulated",
                name = "${name}_emulated",
                vcsName = VCS_JETBRAINS_GIT,
                href = "",
                project = TeamcityProject(id = projectId, name = "Project Name emulated", href = "", webUrl = "")
            )
            messages += "Created new VCS Root (emulated): projectId=$projectId, vcsRootId=${fake.id}, vcsRootName=${fake.name}, branch=$branch"
            return fake
        } else {
            val created = client.createVcsRoot(
                TeamcityCreateVcsRoot(
                    name = name,
                    vcsName = VCS_JETBRAINS_GIT,
                    projectLocator = "id:$projectId",
                    properties = props
                )
            )
            messages += "Created new VCS Root: projectId=$projectId, vcsRootId=${created.id}, vcsRootName=${created.name}, branch=$branch"
            return created
        }
    }

    private fun migrateVcsLabeling(buildTypeId: String, oldRootIds: Set<String>, newRootId: String, messages: MutableList<String>) {
        val features = client.getBuildTypeFeatures(BuildTypeLocator(buildTypeId))
        features.features
            .filter { it.type == FEATURE_VCS_LABELING }
            .forEach { feature ->
                val bound = feature.properties.properties.firstOrNull { it.name == PROPERTY_VCS_ROOT_ID }?.value
                if (bound != null && oldRootIds.contains(bound)) {
                    if (!emulation) {
                        client.updateBuildTypeFeatureParameter(BuildTypeLocator(buildTypeId), feature.id, PROPERTY_VCS_ROOT_ID, newRootId)
                    }
                    messages += "Updated VCS labeling: buildTypeId=$buildTypeId, featureId=${feature.id}, from=$bound, to=$newRootId"
                }
            }
    }

    private fun generateVcsRootName(vcsUrl: String): String {
        val base = URI(vcsUrl)
            .path
            .removePrefix("/")
            .removeSuffix(".git")
            .replace("/", "_")
            .replace("-", "_")
            .split("_")
            .filter { it.isNotBlank() }
            .joinToString("_") { it.replaceFirstChar { c -> c.titlecase() } }
        return "${base}_${UUID.randomUUID()}"
    }

    private data class InstancesIndex(
        val rootIds: Set<String>,
        val byBuildType: Map<BuildTypeLocator, Set<String>>
    )

    companion object {
        const val COMMAND = "replace-vcs-root"
        const val OLD_VCS_ROOT = "--old-vcs-root"
        const val NEW_VCS_ROOT = "--new-vcs-root"
        const val EMULATION = "--emulation"
        const val JIRA_MESSAGE_FILE = "--jira-message-file"

        // Teamcity properties
        const val PROPERTY_URL = "url"
        const val PROPERTY_PUSH_URL = "push_url"
        const val PROPERTY_BRANCH = "branch"
        const val PROPERTY_BRANCH_SPEC = "teamcity:branchSpec"
        const val PROPERTY_USERNAME = "username"
        const val PROPERTY_AUTH_METHOD = "authMethod"
        const val PROPERTY_USERNAME_STYLE = "usernameStyle"
        const val PROPERTY_SUBMODULE_CHECKOUT = "submoduleCheckout"
        const val PROPERTY_IGNORE_KNOWN_HOSTS = "ignoreKnownHosts"
        const val PROPERTY_AGENT_CLEAN_FILES_POLICY = "agentCleanFilesPolicy"
        const val PROPERTY_AGENT_CLEAN_POLICY = "agentCleanPolicy"
        const val PROPERTY_VCS_ROOT_ID = "vcsRootId"
        const val PROPERTY_BUILD_TYPE_BRANCH = "VCS_BRANCH"

        // Teamcity default values
        const val VCS_JETBRAINS_GIT = "jetbrains.git"
        const val DEFAULT_BRANCH_SPEC = "+:refs/heads/*"
        const val DEFAULT_GIT_USERNAME = "git"
        const val DEFAULT_AUTH_PRIVATE_KEY = "PRIVATE_KEY_DEFAULT"
        const val USERNAME_STYLE_USERID = "USERID"
        const val SUBMODULE_IGNORE = "IGNORE"
        const val CLEAN_FILES_ALL_UNTRACKED = "ALL_UNTRACKED"
        const val CLEAN_ON_BRANCH_CHANGE = "ON_BRANCH_CHANGE"
        const val TRUE = "true"

        const val FEATURE_VCS_LABELING = "VcsLabeling"
    }
}