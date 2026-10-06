package org.octopusden.octopus.automation.teamcity.buildchain

import org.octopusden.octopus.components.registry.core.dto.RepositoryType
import org.octopusden.octopus.components.registry.core.dto.VersionControlSystemRootDTO
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityVCSType
import org.octopusden.octopus.infrastructure.teamcity.client.createBuildTypeVcsRootEntry
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityCreateVcsRootEntry
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityLinkVcsRoot
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityVcsRoot
import org.slf4j.Logger

/**
 * Turns a Component's registry VCS Roots and Build Working Directory into TeamCity VCS roots,
 * checkout rules and attach order.
 */
internal class VcsRootPlacement(
    private val client: TeamcityClient,
    private val log: Logger,
    private val componentName: String,
) {
    /** One registry VCS root placed on a created TeamCity VCS root. */
    data class PlacedRoot(
        val position: Int,
        val vcsRoot: TeamcityVcsRoot,
        val checkoutRule: String?,
    )

    /**
     * Checks the shapes the generator does not support: more than one root at the checkout root, a
     * repeated repository, a non-Git root and a Checkout Directory colliding with the project's
     * helper clone directory. Runs before any TeamCity object is created.
     */
    fun validate(
        roots: List<VersionControlSystemRootDTO>,
        reservedCheckoutDirectory: String?,
    ) {
        checkAllGit(roots)
        checkAtMostOneCheckoutRoot(roots)
        checkNoRepeatedRepository(roots)
        checkNoReservedCollision(roots, reservedCheckoutDirectory)
    }

    private fun checkAllGit(roots: List<VersionControlSystemRootDTO>) {
        roots.forEachIndexed { index, root ->
            if (root.type != RepositoryType.GIT) {
                throw UnsupportedVcsTypeException(
                    "Component '$componentName': VCS root at position ${index + 1} ('${root.name}') has " +
                        "unsupported type ${root.type}, only Git roots are supported",
                )
            }
        }
    }

    private fun checkAtMostOneCheckoutRoot(roots: List<VersionControlSystemRootDTO>) {
        val rootsAtCheckoutRoot = roots.withIndex().filter { it.value.checkoutDirectory.isNullOrBlank() }
        if (rootsAtCheckoutRoot.size > 1) {
            throw UnsupportedVcsRootLayoutException(
                "Component '$componentName': more than one VCS root has no Checkout Directory " +
                    "(positions ${rootsAtCheckoutRoot.joinToString { (it.index + 1).toString() }}), " +
                    "at most one root may be checked out at the checkout root",
            )
        }
    }

    private fun checkNoRepeatedRepository(roots: List<VersionControlSystemRootDTO>) {
        roots
            .withIndex()
            .groupBy {
                it.value.vcsPath
                    .trim()
                    .lowercase()
            }.values
            .firstOrNull { it.size > 1 }
            ?.let { duplicates ->
                throw UnsupportedVcsRootLayoutException(
                    "Component '$componentName': VCS roots at positions " +
                        duplicates.joinToString { (it.index + 1).toString() } +
                        " point at the same repository '${duplicates.first().value.vcsPath}'",
                )
            }
    }

    private fun checkNoReservedCollision(
        roots: List<VersionControlSystemRootDTO>,
        reservedCheckoutDirectory: String?,
    ) {
        if (reservedCheckoutDirectory == null) return
        roots.withIndex().firstOrNull { it.value.checkoutDirectory == reservedCheckoutDirectory }?.let { (index, root) ->
            throw UnsupportedVcsRootLayoutException(
                "Component '$componentName': Checkout Directory '$reservedCheckoutDirectory' of VCS root at " +
                    "position ${index + 1} ('${root.name}') collides with the helper clone directory " +
                    "RELEASE_NOTES_REPORT_TEMPLATE_CHECKOUT_DIR",
            )
        }
    }

    /**
     * The root that holds the Build Working Directory, else the root without a Checkout Directory,
     * else registry position 1. Returns registry positions (1-based) in attach order.
     */
    fun attachOrder(
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

    /** One TeamCity VCS root per registry root, named by registry position. */
    fun createAll(
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
                // Unreachable once validate() has run; kept as a safety net for direct callers.
                else -> throw UnsupportedVcsTypeException("Unsupported vcs type: ${rootData.type}")
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

    fun attach(
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
}
