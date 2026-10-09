package org.octopusden.octopus.automation.teamcity.vcsroot

data class VcsRootRef(
    val id: String,
    val name: String?,
)

data class BuildTypeRef(
    val id: String,
    val name: String?,
)

/** One step of a replacement, in the order it happened. In a dry run these describe what would happen. */
sealed interface ReplaceEvent {
    /** No build configuration uses [url]. */
    data class NothingToReplace(
        val url: String,
    ) : ReplaceEvent

    /** [buildTypeCount] build configurations use the old URL and are about to be moved. */
    data class ReplaceStarted(
        val buildTypeCount: Int,
    ) : ReplaceEvent

    /** A root for the new URL and [branch] already existed in [projectId] and is reused. */
    data class RootReused(
        val projectId: String,
        val root: VcsRootRef,
        val branch: String,
    ) : ReplaceEvent

    /** A root for the new URL and [branch] was created in [projectId]; in a dry run its id is `dryRun`. */
    data class RootCreated(
        val projectId: String,
        val root: VcsRootRef,
        val branch: String,
        val dryRun: Boolean,
    ) : ReplaceEvent

    data class RootAttached(
        val buildType: BuildTypeRef,
        val root: VcsRootRef,
        val checkoutRules: String,
    ) : ReplaceEvent

    /** A VCS labeling feature of [buildTypeId] was rebound from root [fromRootId] to [toRootId]. */
    data class LabelingMoved(
        val buildTypeId: String,
        val featureId: String,
        val fromRootId: String,
        val toRootId: String,
    ) : ReplaceEvent

    data class RootDetached(
        val buildType: BuildTypeRef,
        val root: VcsRootRef,
    ) : ReplaceEvent

    /** [buildType] now uses [root] instead of the old roots. */
    data class BuildTypeSwitched(
        val buildType: BuildTypeRef,
        val root: VcsRootRef,
        val checkoutRules: String,
        val dryRun: Boolean,
    ) : ReplaceEvent

    /** [rootCount] Git roots still point at the old URL and are about to be rewritten. */
    data class UpdateStarted(
        val rootCount: Int,
    ) : ReplaceEvent

    /** A Git root that still pointed at the old URL now points at the new one. */
    data class RootUrlUpdated(
        val root: VcsRootRef,
    ) : ReplaceEvent
}
