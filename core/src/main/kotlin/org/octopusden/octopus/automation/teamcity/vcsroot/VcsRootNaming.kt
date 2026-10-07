package org.octopusden.octopus.automation.teamcity.vcsroot

import java.util.UUID

/** Names of the VCS roots `replace-vcs-root` creates, with no TeamCity calls. */
internal object VcsRootNaming {
    /** `Org_Repo_Name_<suffix>` for `.../org/repo-name.git`: path segments and dash-separated words capitalised, joined by `_`. */
    fun nameFor(
        url: String,
        suffix: String = UUID.randomUUID().toString(),
    ): String {
        val path = GitUrl
            .path(url)
            .removeSuffix(".git")
            .replace("/", "_")
            .replace("-", "_")
            .split("_")
            .filter { it.isNotBlank() }
            .joinToString("_") { it.replaceFirstChar { c -> c.titlecase() } }
        return "${path}_$suffix"
    }
}
