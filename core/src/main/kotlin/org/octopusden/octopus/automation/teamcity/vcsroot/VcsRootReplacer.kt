package org.octopusden.octopus.automation.teamcity.vcsroot

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
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.UUID

/**
 * Moves every build configuration that uses a Git repository onto another repository URL: attaches
 * a Git VCS root for the new URL (reusing one in the same project with the same URL and branch),
 * keeps the checkout rules, moves VCS labeling, detaches the old roots, and finally rewrites the URL
 * of Git VCS roots that still point at the old repository.
 */
class VcsRootReplacer(
    private val client: TeamcityClient,
) {
    private val log = LoggerFactory.getLogger(VcsRootReplacer::class.java)

    /** With [dryRun] nothing is changed in TeamCity; the same report is logged. */
    fun replace(
        oldVcsRoot: String,
        newVcsRoot: String,
        dryRun: Boolean,
    ) {
        require(isValidGitUrl(oldVcsRoot)) { "oldVcsRoot must be a valid lowercase Git URL: '$oldVcsRoot'" }
        require(isValidGitUrl(newVcsRoot)) { "newVcsRoot must be a valid lowercase Git URL: '$newVcsRoot'" }
        Replacement(oldVcsRoot, newVcsRoot, dryRun).run()
    }

    private inner class Replacement(
        private val oldVcsRoot: String,
        private val newVcsRoot: String,
        private val dryRun: Boolean,
    ) {
        fun run() {
            replaceGenericVcsRoots(oldVcsRoot, newVcsRoot)
            updateExplicitGitVcsRoot(oldVcsRoot, newVcsRoot)
        }

        private fun updateExplicitGitVcsRoot(
            oldVcsRoot: String,
            newVcsRoot: String,
        ) {
            val locator = VcsRootLocator(
                property = listOf(
                    PropertyLocator(
                        name = PROPERTY_URL,
                        value = oldVcsRoot,
                        matchType = PropertyLocator.MatchType.EQUALS,
                        ignoreCase = true,
                    ),
                ),
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

        private fun replaceGenericVcsRoots(
            oldVcsRoot: String,
            newVcsRoot: String,
        ) {
            val index = findVcsRootInstancesRootIdsByUrl(oldVcsRoot)
            if (index.rootIds.isEmpty() || index.byBuildType.isEmpty()) {
                log.info("No build configurations referencing $oldVcsRoot found")
                return
            }
            log.info("Git VCS Root replace report")

            index.byBuildType.forEach { (buildTypeLocator, entriesToDetach) ->
                val buildType = client.getBuildType(buildTypeLocator)
                val projectId = requireNotNull(buildType.projectId) {
                    "Build type ${buildType.id} ('${buildType.name}') has no projectId; cannot replace VCS roots"
                }

                val branch = extractBranchFromBuildType(buildType)
                val newVcs = findOrCreateGitVcsRootInProject(projectId, newVcsRoot, branch)

                val toDetach = client
                    .getBuildTypeVcsRootEntries(buildTypeLocator)
                    .entries
                    .filter { entriesToDetach.contains(it.id) || entriesToDetach.contains(it.vcsRoot.id) }
                val checkoutRules = toDetach.firstOrNull()?.checkoutRules ?: ""
                val createEntry = TeamcityCreateVcsRootEntry(
                    id = newVcs.id,
                    vcsRoot = TeamcityLinkVcsRoot(id = newVcs.id),
                    checkoutRules = checkoutRules,
                )
                if (!dryRun) {
                    client.createBuildTypeVcsRootEntry(buildTypeLocator, createEntry)
                }
                log.info(
                    "Attached VCS Root: buildTypeId=${buildType.id}, buildTypeName=${buildType.name}, " +
                        "vcsRootId=${newVcs.id}, vcsRootName=${newVcs.name}, checkoutRules='$checkoutRules'",
                )
                migrateVcsLabeling(buildType.id, toDetach.map { it.vcsRoot.id }.toSet(), newVcs.id)
                toDetach.forEach { oldEntry ->
                    if (!dryRun) {
                        client.deleteBuildTypeVcsRootEntry(buildTypeLocator, oldEntry.id)
                    }
                    log.info(
                        "Detached VCS Root: buildTypeId=${buildType.id}, buildTypeName=${buildType.name}, " +
                            "vcsRootId=${oldEntry.vcsRoot.id}, vcsRootName=${oldEntry.vcsRoot.name}",
                    )
                }
                log.info(
                    "Switched VCS: buildType=${buildType.id}-${buildType.name} -> root=${newVcs.id}-${newVcs.name}, " +
                        "checkoutRules='$checkoutRules' (dryRun = $dryRun)",
                )
            }
        }

        private fun findVcsRootInstancesRootIdsByUrl(url: String): InstancesIndex {
            val propertyLocator =
                PropertyLocator(name = PROPERTY_URL, value = url, matchType = PropertyLocator.MatchType.EQUALS, ignoreCase = true)
            val allInstances = client.getVcsRootInstances(VcsRootInstanceLocator(property = listOf(propertyLocator))).vcsRootInstances
            val rootIds = allInstances.map { it.vcsRootId }.toSet()
            if (rootIds.isEmpty()) {
                return InstancesIndex(emptySet(), emptyMap())
            }
            val fields =
                "buildType(id,name,projectId,projectName,webUrl,href," +
                    "vcs-root-entries(vcs-root-entry(id,vcs-root(id,name,href),checkout-rules)))"
            val buildTypes = client
                .getBuildTypesWithVcsRootInstanceLocatorAndFields(
                    VcsRootInstanceLocator(property = listOf(propertyLocator)),
                    fields,
                ).buildTypes
                .distinctBy { it.id }
            val byBuild = buildTypes
                .associateBy(
                    { BuildTypeLocator(id = it.id) },
                    { buildType ->
                        (buildType.vcsRoots?.entries ?: emptyList())
                            .filter { e -> rootIds.contains(e.vcsRoot.id) || rootIds.contains(e.id) }
                            .map { it.id }
                            .toSet()
                    },
                ).filterValues { it.isNotEmpty() }
            return InstancesIndex(rootIds, byBuild)
        }

        private fun extractBranchFromBuildType(bt: TeamcityBuildType): String {
            val raw = bt.parameters
                ?.properties
                ?.firstOrNull { it.name == PROPERTY_BUILD_TYPE_BRANCH }
                ?.value
                ?.trim()
            if (raw.isNullOrEmpty()) {
                return "refs/heads/master"
            }
            return if (raw.startsWith("refs/")) raw else "refs/heads/$raw"
        }

        private fun findOrCreateGitVcsRootInProject(
            projectId: String,
            newVcsUrl: String,
            branch: String,
        ): TeamcityVcsRoot {
            val candidates = client
                .getVcsRoots(
                    VcsRootLocator(
                        project = ProjectLocator(id = projectId),
                        property = listOf(
                            PropertyLocator(
                                name = PROPERTY_URL,
                                value = newVcsUrl,
                                matchType = PropertyLocator.MatchType.EQUALS,
                                ignoreCase = true,
                            ),
                            PropertyLocator(
                                name = PROPERTY_BRANCH,
                                value = branch,
                                matchType = PropertyLocator.MatchType.EQUALS,
                                ignoreCase = true,
                            ),
                        ),
                    ),
                ).vcsRoots
            if (candidates.isNotEmpty()) {
                val existing = client.getVcsRoot(VcsRootLocator(id = candidates.first().id))
                log.info(
                    "Found existing VCS Root: projectId=$projectId, vcsRootId=${existing.id}, vcsRootName=${existing.name}, branch=$branch",
                )
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
                ),
            )
            if (dryRun) {
                val fake = TeamcityVcsRoot(
                    id = "dryRun",
                    name = "${name}_dryRun",
                    vcsName = VCS_JETBRAINS_GIT,
                    href = "",
                    project = TeamcityProject(id = projectId, name = "Project Name dryRun", href = "", webUrl = ""),
                )
                log.info(
                    "Created new VCS Root (dryRun): projectId=$projectId, vcsRootId=${fake.id}, vcsRootName=${fake.name}, branch=$branch",
                )
                return fake
            } else {
                val created = client.createVcsRoot(
                    TeamcityCreateVcsRoot(
                        name = name,
                        vcsName = VCS_JETBRAINS_GIT,
                        projectLocator = "id:$projectId",
                        properties = props,
                    ),
                )
                log.info("Created new VCS Root: projectId=$projectId, vcsRootId=${created.id}, vcsRootName=${created.name}, branch=$branch")
                return created
            }
        }

        private fun migrateVcsLabeling(
            buildTypeId: String,
            oldRootIds: Set<String>,
            newRootId: String,
        ) {
            val features = client.getBuildTypeFeatures(BuildTypeLocator(buildTypeId))
            features.features
                .filter { it.type == FEATURE_VCS_LABELING }
                .forEach { feature ->
                    val bound = feature.properties.properties
                        .firstOrNull { it.name == PROPERTY_VCS_ROOT_ID }
                        ?.value
                    if (bound != null && oldRootIds.contains(bound)) {
                        if (!dryRun) {
                            client.updateBuildTypeFeatureParameter(
                                BuildTypeLocator(buildTypeId),
                                feature.id,
                                PROPERTY_VCS_ROOT_ID,
                                newRootId,
                            )
                        }
                        log.info("Updated VCS labeling: buildTypeId=$buildTypeId, featureId=${feature.id}, from=$bound, to=$newRootId")
                    }
                }
        }

        private fun generateVcsRootName(vcsUrl: String): String {
            val path = extractPathFromGitUrl(vcsUrl)
                .removeSuffix(".git")
                .replace("/", "_")
                .replace("-", "_")
                .split("_")
                .filter { it.isNotBlank() }
                .joinToString("_") { it.replaceFirstChar { c -> c.titlecase() } }
            return "${path}_${UUID.randomUUID()}"
        }

        private fun extractPathFromGitUrl(vcsUrl: String): String =
            when {
                vcsUrl.startsWith("ssh://", ignoreCase = true) || vcsUrl.startsWith("https://", ignoreCase = true) -> {
                    URI(vcsUrl).path.removePrefix("/")
                }
                else -> {
                    vcsUrl.substring(vcsUrl.indexOf(':') + 1)
                }
            }
    }

    private data class InstancesIndex(
        val rootIds: Set<String>,
        val byBuildType: Map<BuildTypeLocator, Set<String>>,
    )

    companion object {
        // Teamcity properties
        internal const val PROPERTY_URL = "url"
        internal const val PROPERTY_PUSH_URL = "push_url"
        internal const val PROPERTY_BRANCH = "branch"
        internal const val PROPERTY_BRANCH_SPEC = "teamcity:branchSpec"
        internal const val PROPERTY_USERNAME = "username"
        internal const val PROPERTY_AUTH_METHOD = "authMethod"
        internal const val PROPERTY_USERNAME_STYLE = "usernameStyle"
        internal const val PROPERTY_SUBMODULE_CHECKOUT = "submoduleCheckout"
        internal const val PROPERTY_IGNORE_KNOWN_HOSTS = "ignoreKnownHosts"
        internal const val PROPERTY_AGENT_CLEAN_FILES_POLICY = "agentCleanFilesPolicy"
        internal const val PROPERTY_AGENT_CLEAN_POLICY = "agentCleanPolicy"
        internal const val PROPERTY_VCS_ROOT_ID = "vcsRootId"
        internal const val PROPERTY_BUILD_TYPE_BRANCH = "VCS_BRANCH"

        // Teamcity default values
        internal const val VCS_JETBRAINS_GIT = "jetbrains.git"
        internal const val PROPERTY_VALUE_BRANCH_SPEC = "+:refs/heads/*"
        internal const val PROPERTY_VALUE_USERNAME = "git"
        internal const val PROPERTY_VALUE_AUTH_METHOD = "PRIVATE_KEY_DEFAULT"
        internal const val PROPERTY_VALUE_USERNAME_STYLE = "USERID"
        internal const val PROPERTY_VALUE_SUBMODULE_CHECKOUT = "IGNORE"
        internal const val PROPERTY_VALUE_CLEAN_FILES_POLICY = "ALL_UNTRACKED"
        internal const val PROPERTY_VALUE_CLEAN_POLICY = "ON_BRANCH_CHANGE"
        internal const val TRUE = "true"

        internal const val FEATURE_VCS_LABELING = "VcsLabeling"

        /** A lowercase `ssh://user@host/path.git`, `user@host:path.git` or `https://host/path.git` URL. */
        fun isValidGitUrl(url: String): Boolean {
            if (url != url.lowercase()) return false
            val baseSshScheme = Regex("""^ssh://[\w.-]+@[\w.-]+/[\w./~\-+%]+(\.git)$""")
            val githubSshScheme = Regex("""^[\w.-]+@[\w.-]+:[\w./~\-+%]+(\.git)$""")
            val httpsScheme = Regex("""^https://[\w.-]+/[\w./~\-+%]+(\.git)$""")

            return baseSshScheme.matches(url) ||
                githubSshScheme.matches(url) ||
                httpsScheme.matches(url)
        }
    }
}
