package org.octopusden.octopus.automation.teamcity.vcsroot

/** A build configuration's `VCS_BRANCH` as a full ref: `refs/heads/master` when unset, `refs/heads/<raw>` unless already a ref. */
internal fun branchRef(raw: String?): String {
    val branch = raw?.trim()
    return when {
        branch.isNullOrEmpty() -> "refs/heads/master"
        branch.startsWith("refs/") -> branch
        else -> "refs/heads/$branch"
    }
}
