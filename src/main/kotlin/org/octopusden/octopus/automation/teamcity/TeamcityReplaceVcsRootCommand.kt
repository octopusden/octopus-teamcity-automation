package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
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
import java.util.UUID

class TeamcityReplaceVcsRootCommand : CliktCommand(name = COMMAND) {

    private val oldVcsRoot by option(OLD_VCS_ROOT, help = "Old Git repository URL")
        .convert { it.trim() }.required()
        .check("$OLD_VCS_ROOT must be a valid Git URL (e.g. ssh://git@host/org/repo.git)") { it.isValidGitUrl() }

    private val newVcsRoot by option(NEW_VCS_ROOT, help = "New Git repository URL")
        .convert { it.trim() }.required()
        .check("$NEW_VCS_ROOT must be a valid Git URL (e.g. ssh://git@host/org/repo.git)") { it.isValidGitUrl() }

    private val dryRun by option(DRY_RUN, help = "Dry run only, do not apply")
        .convert { it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$DRY_RUN must be 'true' or 'false'") }
        .required()

    private val context by requireObject<MutableMap<String, Any>>()

    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }

    private val log by lazy { context[TeamcityCommand.LOG] as Logger }

    override fun run() {
        log.info("Executing $COMMAND")
        updateExplicitGitVcsRoot(oldVcsRoot, newVcsRoot)
        replaceGenericVcsRoots(oldVcsRoot, newVcsRoot)
    }

    private fun updateExplicitGitVcsRoot(oldVcsRoot: String, newVcsRoot: String) {
        val locator = VcsRootLocator(
            property = listOf(PropertyLocator(name = PROPERTY_URL, value = oldVcsRoot, matchType = PropertyLocator.MatchType.EQUALS, ignoreCase = true))
        )
        val roots = client.getVcsRoots(locator).vcsRoots
        if (roots.isNotEmpty()) {
            log.info("Git VCS Root update report")
        }
        roots.forEach { root ->
            if (!dryRun) {
                client.updateVcsRootProperty(root.id, PROPERTY_URL, newVcsRoot)
                runCatching { client.getVcsRootProperty(root.id, PROPERTY_PUSH_URL) }
                    .onSuccess { client.updateVcsRootProperty(root.id, PROPERTY_PUSH_URL, newVcsRoot) }
            }
            log.info("Updated Git VCS Root: id=${root.id}, name=${root.name}")
        }
    }

    private fun replaceGenericVcsRoots(oldVcsRoot: String, newVcsRoot: String) {
        val index = findVcsRootInstancesRootIdsByUrl(oldVcsRoot)
        if (index.rootIds.isEmpty() || index.byBuildType.isEmpty()) {
            log.info("No build configurations referencing $oldVcsRoot found")
            return
        }
        log.info("Git VCS Root replace report")

        index.byBuildType.forEach { (buildTypeLocator, entriesToDetach) ->
            val buildType = client.getBuildType(buildTypeLocator)
            val projectId = buildType.projectId
            val branch = extractBranchFromBuildType(buildType)
            val newVcs = findOrCreateGitVcsRootInProject(projectId, newVcsRoot, branch)

            val toDetach = client.getBuildTypeVcsRootEntries(buildTypeLocator)
                .entries
                .filter { entriesToDetach.contains(it.id) || entriesToDetach.contains(it.vcsRoot.id) }
            val checkoutRules = toDetach.firstOrNull()?.checkoutRules ?: ""
            val createEntry = TeamcityCreateVcsRootEntry(
                id = newVcs.id,
                vcsRoot = TeamcityLinkVcsRoot(id = newVcs.id),
                checkoutRules = checkoutRules
            )
            if (!dryRun) {
                client.createBuildTypeVcsRootEntry(buildTypeLocator, createEntry)
            }
            log.info("Attached VCS Root: buildTypeId=${buildType.id}, buildTypeName=${buildType.name}, vcsRootId=${newVcs.id}, vcsRootName=${newVcs.name}, checkoutRules='${checkoutRules}'")
            migrateVcsLabeling(buildType.id, toDetach.map { it.vcsRoot.id }.toSet(), newVcs.id)
            toDetach.forEach { oldEntry ->
                if (!dryRun) {
                    client.deleteBuildTypeVcsRootEntry(buildTypeLocator, oldEntry.id)
                }
                log.info("Detached VCS Root: buildTypeId=${buildType.id}, buildTypeName=${buildType.name}, vcsRootId=${oldEntry.vcsRoot.id}, vcsRootName=${oldEntry.vcsRoot.name}")
            }
            log.info("Switched VCS: buildType=${buildType.id}-${buildType.name} -> root=${newVcs.id}-${newVcs.name}, checkoutRules='$checkoutRules' (dryRun = $dryRun)")
        }
    }

    private fun findVcsRootInstancesRootIdsByUrl(url: String): InstancesIndex {
        val propertyLocator = PropertyLocator(name = PROPERTY_URL, value = url, matchType = PropertyLocator.MatchType.EQUALS, ignoreCase = true)
        val allInstances = client.getVcsRootInstances(VcsRootInstanceLocator(property = listOf(propertyLocator))).vcsRootInstances
        val rootIds = allInstances.map { it.vcsRootId }.toSet()
        if (rootIds.isEmpty()) {
            return InstancesIndex(emptySet(), emptyMap())
        }
        val fields = "buildType(id,name,projectId,projectName,webUrl,href,vcs-root-entries(id,vcs-root(id,name,href),checkout-rules))"
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

    private fun findOrCreateGitVcsRootInProject(projectId: String, newVcsUrl: String, branch: String): TeamcityVcsRoot {
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
            log.info("Found existing VCS Root: projectId=$projectId, vcsRootId=${existing.id}, vcsRootName=${existing.name}, branch=$branch")
            return existing
        }
        val name = generateVcsRootName(newVcsUrl)
        val props = TeamcityProperties(
            properties = mutableListOf(
                TeamcityProperty(PROPERTY_URL, newVcsUrl),
                TeamcityProperty(PROPERTY_BRANCH, branch),
                TeamcityProperty(PROPERTY_BRANCH_SPEC, PROPERTY_VALUE_BRANCH_SPEC),
                TeamcityProperty(PROPERTY_USERNAME, PROPERTY_VALUE_USERNAME),
                TeamcityProperty(PROPERTY_AUTH_METHOD, PROPERTY_VALUE_AUTH_METHOD),
                TeamcityProperty(PROPERTY_USERNAME_STYLE, PROPERTY_VALUE_USERNAME_STYLE),
                TeamcityProperty(PROPERTY_SUBMODULE_CHECKOUT, PROPERTY_VALUE_SUBMODULE_CHECKOUT),
                TeamcityProperty(PROPERTY_IGNORE_KNOWN_HOSTS, TRUE),
                TeamcityProperty(PROPERTY_AGENT_CLEAN_FILES_POLICY, PROPERTY_VALUE_CLEAN_FILES_POLICY),
                TeamcityProperty(PROPERTY_AGENT_CLEAN_POLICY, PROPERTY_VALUE_CLEAN_POLICY),
            )
        )
        if (dryRun) {
            val fake = TeamcityVcsRoot(
                id = "dryRun",
                name = "${name}_dryRun",
                vcsName = VCS_JETBRAINS_GIT,
                href = "",
                project = TeamcityProject(id = projectId, name = "Project Name dryRun", href = "", webUrl = "")
            )
            log.info("Created new VCS Root (dryRun): projectId=$projectId, vcsRootId=${fake.id}, vcsRootName=${fake.name}, branch=$branch")
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
            log.info("Created new VCS Root: projectId=$projectId, vcsRootId=${created.id}, vcsRootName=${created.name}, branch=$branch")
            return created
        }
    }

    private fun migrateVcsLabeling(buildTypeId: String, oldRootIds: Set<String>, newRootId: String) {
        val features = client.getBuildTypeFeatures(BuildTypeLocator(buildTypeId))
        features.features
            .filter { it.type == FEATURE_VCS_LABELING }
            .forEach { feature ->
                val bound = feature.properties.properties.firstOrNull { it.name == PROPERTY_VCS_ROOT_ID }?.value
                if (bound != null && oldRootIds.contains(bound)) {
                    if (!dryRun) {
                        client.updateBuildTypeFeatureParameter(BuildTypeLocator(buildTypeId), feature.id, PROPERTY_VCS_ROOT_ID, newRootId)
                    }
                    log.info("Updated VCS labeling: buildTypeId=$buildTypeId, featureId=${feature.id}, from=$bound, to=$newRootId")
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

    private fun String.isValidGitUrl(): Boolean {
        val sshScheme = Regex("""^ssh://[\w.-]+@[\w.-]+/[\w./~\-+%]+(\.git)$""")
        return sshScheme.matches(this)
    }

    private data class InstancesIndex(
        val rootIds: Set<String>,
        val byBuildType: Map<BuildTypeLocator, Set<String>>
    )

    companion object {
        const val COMMAND = "replace-vcs-root"
        const val OLD_VCS_ROOT = "--old-vcs-root"
        const val NEW_VCS_ROOT = "--new-vcs-root"
        const val DRY_RUN = "--dry-run"

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
        const val PROPERTY_VALUE_BRANCH_SPEC = "+:refs/heads/*"
        const val PROPERTY_VALUE_USERNAME = "git"
        const val PROPERTY_VALUE_AUTH_METHOD = "PRIVATE_KEY_DEFAULT"
        const val PROPERTY_VALUE_USERNAME_STYLE = "USERID"
        const val PROPERTY_VALUE_SUBMODULE_CHECKOUT = "IGNORE"
        const val PROPERTY_VALUE_CLEAN_FILES_POLICY = "ALL_UNTRACKED"
        const val PROPERTY_VALUE_CLEAN_POLICY = "ON_BRANCH_CHANGE"
        const val TRUE = "true"

        const val FEATURE_VCS_LABELING = "VcsLabeling"
    }
}