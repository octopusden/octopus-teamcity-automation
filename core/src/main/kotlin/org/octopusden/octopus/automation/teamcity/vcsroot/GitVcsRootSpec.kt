package org.octopusden.octopus.automation.teamcity.vcsroot

import org.octopusden.octopus.automation.teamcity.vcsroot.GitVcsProperty.AGENT_CLEAN_FILES_POLICY
import org.octopusden.octopus.automation.teamcity.vcsroot.GitVcsProperty.AGENT_CLEAN_POLICY
import org.octopusden.octopus.automation.teamcity.vcsroot.GitVcsProperty.AUTH_METHOD
import org.octopusden.octopus.automation.teamcity.vcsroot.GitVcsProperty.BRANCH
import org.octopusden.octopus.automation.teamcity.vcsroot.GitVcsProperty.BRANCH_SPEC
import org.octopusden.octopus.automation.teamcity.vcsroot.GitVcsProperty.IGNORE_KNOWN_HOSTS
import org.octopusden.octopus.automation.teamcity.vcsroot.GitVcsProperty.SUBMODULE_CHECKOUT
import org.octopusden.octopus.automation.teamcity.vcsroot.GitVcsProperty.URL
import org.octopusden.octopus.automation.teamcity.vcsroot.GitVcsProperty.USERNAME
import org.octopusden.octopus.automation.teamcity.vcsroot.GitVcsProperty.USERNAME_STYLE
import org.octopusden.octopus.automation.teamcity.vcsroot.GitVcsProperty.USER_FOR_TAGS
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityVCSType
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty

/**
 * The Git VCS roots this module creates. Both kinds share the connection settings (URL, default branch,
 * private-key authentication as `git`, no known-hosts check) and differ in what they watch and how agents
 * check out, because they serve different jobs. Unifying them would change what one of the commands creates.
 */
internal enum class GitVcsRootSpec(
    private val specific: List<Pair<String, String>>,
) {
    /**
     * A new component's release chain (`create-build-chain`). It builds the registry's default branch only,
     * which was set deliberately (#50), and the release tags it pushes are attributed to `tcagent`.
     */
    BUILD_CHAIN(
        listOf(
            BRANCH_SPEC to "+:<default>",
            USER_FOR_TAGS to "tcagent",
        ),
    ),

    /**
     * The replacement for an existing build configuration's root (`replace-vcs-root`). Those configurations
     * may build any branch, so it watches every branch, and it keeps the checkout settings this command has
     * always created (#43): submodules ignored, untracked files cleaned when the branch changes.
     */
    REPLACEMENT(
        listOf(
            BRANCH_SPEC to "+:refs/heads/*",
            USERNAME_STYLE to "USERID",
            SUBMODULE_CHECKOUT to "IGNORE",
            AGENT_CLEAN_FILES_POLICY to "ALL_UNTRACKED",
            AGENT_CLEAN_POLICY to "ON_BRANCH_CHANGE",
        ),
    ),
    ;

    fun properties(
        url: String,
        branch: String,
    ) = TeamcityProperties(
        (
            listOf(
                URL to url,
                BRANCH to branch,
                AUTH_METHOD to "PRIVATE_KEY_DEFAULT",
                USERNAME to "git",
                IGNORE_KNOWN_HOSTS to "true",
            ) + specific
        ).map { (name, value) -> TeamcityProperty(name, value) },
    )
}

/** Git VCS root property names, and the VCS type every Git root is created with. */
internal object GitVcsProperty {
    val VCS_NAME = TeamcityVCSType.GIT.value

    const val URL = "url"
    const val PUSH_URL = "push_url"
    const val BRANCH = "branch"
    const val BRANCH_SPEC = "teamcity:branchSpec"
    const val AUTH_METHOD = "authMethod"
    const val USERNAME = "username"
    const val USER_FOR_TAGS = "userForTags"
    const val USERNAME_STYLE = "usernameStyle"
    const val SUBMODULE_CHECKOUT = "submoduleCheckout"
    const val IGNORE_KNOWN_HOSTS = "ignoreKnownHosts"
    const val AGENT_CLEAN_FILES_POLICY = "agentCleanFilesPolicy"
    const val AGENT_CLEAN_POLICY = "agentCleanPolicy"
}
