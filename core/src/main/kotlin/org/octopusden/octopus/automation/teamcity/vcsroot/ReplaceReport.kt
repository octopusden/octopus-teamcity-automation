package org.octopusden.octopus.automation.teamcity.vcsroot

/** Everything a replacement did, or in a dry run would do. */
data class ReplaceReport(
    val dryRun: Boolean,
    val events: List<ReplaceEvent>,
) {
    val attached: List<ReplaceEvent.RootAttached> get() = events.filterIsInstance<ReplaceEvent.RootAttached>()
    val detached: List<ReplaceEvent.RootDetached> get() = events.filterIsInstance<ReplaceEvent.RootDetached>()
    val createdRoots: List<ReplaceEvent.RootCreated> get() = events.filterIsInstance<ReplaceEvent.RootCreated>()
    val labelingMoved: List<ReplaceEvent.LabelingMoved> get() = events.filterIsInstance<ReplaceEvent.LabelingMoved>()
    val updatedRoots: List<ReplaceEvent.RootUrlUpdated> get() = events.filterIsInstance<ReplaceEvent.RootUrlUpdated>()
}
