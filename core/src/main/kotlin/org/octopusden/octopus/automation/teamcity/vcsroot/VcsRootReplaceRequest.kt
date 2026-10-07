package org.octopusden.octopus.automation.teamcity.vcsroot

/** Moves build configurations from [oldUrl] to [newUrl]; with [dryRun] nothing changes in TeamCity. */
data class VcsRootReplaceRequest(
    val oldUrl: String,
    val newUrl: String,
    val dryRun: Boolean,
) {
    init {
        require(GitUrl.isValid(oldUrl)) { "oldUrl must be a valid lowercase Git URL: '$oldUrl'" }
        require(GitUrl.isValid(newUrl)) { "newUrl must be a valid lowercase Git URL: '$newUrl'" }
    }
}
