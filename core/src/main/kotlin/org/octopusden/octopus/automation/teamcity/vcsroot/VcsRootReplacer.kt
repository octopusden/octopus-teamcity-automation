package org.octopusden.octopus.automation.teamcity.vcsroot

import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityBuildType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRootEntry
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProject
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.BuildTypeLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.ProjectLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.PropertyLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.VcsRootInstanceLocator
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.VcsRootLocator

/**
 * Moves every build configuration that uses a Git repository onto another repository URL: attaches
 * a Git VCS root for the new URL (reusing one in the same project with the same URL and branch),
 * keeps the checkout rules, moves VCS labeling, detaches the old roots, and finally rewrites the URL
 * of Git VCS roots that still point at the old repository.
 */
class VcsRootReplacer(
    private val client: TeamcityClient,
) {
    /**
     * [onEvent] receives each step as it happens, so a caller can report progress that survives a later
     * failure; the same steps are returned in the report.
     */
    fun replace(
        request: VcsRootReplaceRequest,
        onEvent: (ReplaceEvent) -> Unit = {},
    ): ReplaceReport {
        val events = mutableListOf<ReplaceEvent>()
        Replacement(request) { event ->
            events += event
            onEvent(event)
        }.run()
        return ReplaceReport(request.dryRun, events)
    }

    private inner class Replacement(
        request: VcsRootReplaceRequest,
        private val emit: (ReplaceEvent) -> Unit,
    ) {
        private val oldUrl = request.oldUrl
        private val newUrl = request.newUrl
        private val dryRun = request.dryRun

        fun run() {
            replaceGenericVcsRoots()
            updateExplicitGitVcsRoots()
        }

        private fun urlEquals(url: String) =
            PropertyLocator(name = GitVcsProperty.URL, value = url, matchType = PropertyLocator.MatchType.EQUALS, ignoreCase = true)

        private fun updateExplicitGitVcsRoots() {
            val roots = client.getVcsRoots(VcsRootLocator(property = listOf(urlEquals(oldUrl)))).vcsRoots
            if (roots.isNotEmpty()) {
                emit(ReplaceEvent.UpdateStarted(roots.size))
            }
            roots.forEach { root ->
                if (!dryRun) {
                    client.updateVcsRootProperty(root.id, GitVcsProperty.URL, newUrl)
                    runCatching { client.getVcsRootProperty(root.id, GitVcsProperty.PUSH_URL) }
                        .onSuccess { client.updateVcsRootProperty(root.id, GitVcsProperty.PUSH_URL, newUrl) }
                }
                emit(ReplaceEvent.RootUrlUpdated(VcsRootRef(root.id, root.name)))
            }
        }

        private fun replaceGenericVcsRoots() {
            val index = findVcsRootInstancesRootIdsByUrl(oldUrl)
            if (index.rootIds.isEmpty() || index.byBuildType.isEmpty()) {
                emit(ReplaceEvent.NothingToReplace(oldUrl))
                return
            }
            emit(ReplaceEvent.ReplaceStarted(index.byBuildType.size))

            index.byBuildType.forEach { (buildTypeLocator, entriesToDetach) ->
                val buildType = client.getBuildType(buildTypeLocator)
                val buildTypeRef = BuildTypeRef(buildType.id, buildType.name)
                val projectId = requireNotNull(buildType.projectId) {
                    "Build type ${buildType.id} ('${buildType.name}') has no projectId; cannot replace VCS roots"
                }

                val branch = extractBranchFromBuildType(buildType)
                val newVcs = findOrCreateGitVcsRootInProject(projectId, branch)
                val newRootRef = VcsRootRef(newVcs.id, newVcs.name)

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
                emit(ReplaceEvent.RootAttached(buildTypeRef, newRootRef, checkoutRules))
                migrateVcsLabeling(buildType.id, toDetach.map { it.vcsRoot.id }.toSet(), newVcs.id)
                toDetach.forEach { oldEntry ->
                    if (!dryRun) {
                        client.deleteBuildTypeVcsRootEntry(buildTypeLocator, oldEntry.id)
                    }
                    emit(ReplaceEvent.RootDetached(buildTypeRef, VcsRootRef(oldEntry.vcsRoot.id, oldEntry.vcsRoot.name)))
                }
                emit(ReplaceEvent.BuildTypeSwitched(buildTypeRef, newRootRef, checkoutRules, dryRun))
            }
        }

        private fun findVcsRootInstancesRootIdsByUrl(url: String): InstancesIndex {
            val allInstances = client.getVcsRootInstances(VcsRootInstanceLocator(property = listOf(urlEquals(url)))).vcsRootInstances
            val rootIds = allInstances.map { it.vcsRootId }.toSet()
            if (rootIds.isEmpty()) {
                return InstancesIndex(emptySet(), emptyMap())
            }
            val fields =
                "buildType(id,name,projectId,projectName,webUrl,href," +
                    "vcs-root-entries(vcs-root-entry(id,vcs-root(id,name,href),checkout-rules)))"
            val buildTypes = client
                .getBuildTypesWithVcsRootInstanceLocatorAndFields(
                    VcsRootInstanceLocator(property = listOf(urlEquals(url))),
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

        private fun extractBranchFromBuildType(bt: TeamcityBuildType): String =
            branchRef(
                bt.parameters
                    ?.properties
                    ?.firstOrNull { it.name == BUILD_TYPE_BRANCH_PARAMETER }
                    ?.value,
            )

        private fun findOrCreateGitVcsRootInProject(
            projectId: String,
            branch: String,
        ): TeamcityVcsRoot {
            val candidates = client
                .getVcsRoots(
                    VcsRootLocator(
                        project = ProjectLocator(id = projectId),
                        property = listOf(
                            urlEquals(newUrl),
                            PropertyLocator(
                                name = GitVcsProperty.BRANCH,
                                value = branch,
                                matchType = PropertyLocator.MatchType.EQUALS,
                                ignoreCase = true,
                            ),
                        ),
                    ),
                ).vcsRoots
            if (candidates.isNotEmpty()) {
                val existing = client.getVcsRoot(VcsRootLocator(id = candidates.first().id))
                emit(ReplaceEvent.RootReused(projectId, VcsRootRef(existing.id, existing.name), branch))
                return existing
            }
            val name = VcsRootNaming.nameFor(newUrl)
            val root = if (dryRun) {
                TeamcityVcsRoot(
                    id = "dryRun",
                    name = "${name}_dryRun",
                    vcsName = GitVcsProperty.VCS_NAME,
                    href = "",
                    project = TeamcityProject(id = projectId, name = "Project Name dryRun", href = "", webUrl = ""),
                )
            } else {
                client.createVcsRoot(
                    TeamcityCreateVcsRoot(
                        name = name,
                        vcsName = GitVcsProperty.VCS_NAME,
                        projectLocator = "id:$projectId",
                        properties = GitVcsRootSpec.REPLACEMENT.properties(newUrl, branch),
                    ),
                )
            }
            emit(ReplaceEvent.RootCreated(projectId, VcsRootRef(root.id, root.name), branch, dryRun))
            return root
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
                        .firstOrNull { it.name == LABELING_VCS_ROOT_ID }
                        ?.value
                    if (bound != null && oldRootIds.contains(bound)) {
                        if (!dryRun) {
                            client.updateBuildTypeFeatureParameter(
                                BuildTypeLocator(buildTypeId),
                                feature.id,
                                LABELING_VCS_ROOT_ID,
                                newRootId,
                            )
                        }
                        emit(ReplaceEvent.LabelingMoved(buildTypeId, feature.id, bound, newRootId))
                    }
                }
        }
    }

    private data class InstancesIndex(
        val rootIds: Set<String>,
        val byBuildType: Map<BuildTypeLocator, Set<String>>,
    )

    companion object {
        private const val BUILD_TYPE_BRANCH_PARAMETER = "VCS_BRANCH"
        private const val FEATURE_VCS_LABELING = "VcsLabeling"
        private const val LABELING_VCS_ROOT_ID = "vcsRootId"

        /** A lowercase `ssh://user@host/path.git`, `user@host:path.git` or `https://host/path.git` URL. */
        fun isValidGitUrl(url: String) = GitUrl.isValid(url)
    }
}
