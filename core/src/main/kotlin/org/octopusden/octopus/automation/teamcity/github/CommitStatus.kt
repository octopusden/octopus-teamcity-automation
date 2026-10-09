package org.octopusden.octopus.automation.teamcity.github

/** A commit status: [context] is the check name a branch protection rule requires. */
data class CommitStatus(
    val owner: String,
    val repo: String,
    val commit: String,
    val state: CommitState,
    val context: String = DEFAULT_CONTEXT,
    val description: String? = null,
) {
    init {
        require(owner.isNotBlank()) { "owner is blank" }
        require(repo.isNotBlank()) { "repo is blank" }
        require(commit.isNotBlank()) { "commit is blank" }
    }

    companion object {
        const val DEFAULT_CONTEXT = "TeamCity / build"
    }
}
