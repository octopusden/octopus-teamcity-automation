package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.automation.teamcity.vcsroot.ReplaceEvent
import org.octopusden.octopus.automation.teamcity.vcsroot.VcsRootReplaceRequest
import org.octopusden.octopus.automation.teamcity.vcsroot.VcsRootReplacer
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.slf4j.Logger

class TeamcityReplaceVcsRootCommand : CliktCommand(name = COMMAND) {
    private val oldVcsRoot by option(OLD_VCS_ROOT, help = "Old Git repository URL")
        .convert { it.trim() }
        .required()
        .check("$OLD_VCS_ROOT $GIT_URL_RULE") { VcsRootReplacer.isValidGitUrl(it) }

    private val newVcsRoot by option(NEW_VCS_ROOT, help = "New Git repository URL")
        .convert { it.trim() }
        .required()
        .check("$NEW_VCS_ROOT $GIT_URL_RULE") { VcsRootReplacer.isValidGitUrl(it) }

    private val dryRun by option(DRY_RUN, help = "Dry run only, do not apply")
        .convert { it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("$DRY_RUN must be 'true' or 'false'") }
        .required()

    private val context by requireObject<MutableMap<String, Any>>()

    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }

    private val log by lazy { context[TeamcityCommand.LOG] as Logger }

    override fun run() {
        log.info("Executing $COMMAND")
        VcsRootReplacer(client).replace(VcsRootReplaceRequest(oldVcsRoot, newVcsRoot, dryRun)) { log.info(it.logLine()) }
    }

    private fun ReplaceEvent.logLine(): String =
        when (this) {
            is ReplaceEvent.NothingToReplace -> "No build configurations referencing $url found"
            is ReplaceEvent.ReplaceStarted -> "Git VCS Root replace report"
            is ReplaceEvent.RootReused ->
                "Found existing VCS Root: projectId=$projectId, vcsRootId=${root.id}, vcsRootName=${root.name}, branch=$branch"
            is ReplaceEvent.RootCreated ->
                "Created new VCS Root${if (dryRun) " (dryRun)" else ""}: projectId=$projectId, vcsRootId=${root.id}, " +
                    "vcsRootName=${root.name}, branch=$branch"
            is ReplaceEvent.RootAttached ->
                "Attached VCS Root: buildTypeId=${buildType.id}, buildTypeName=${buildType.name}, " +
                    "vcsRootId=${root.id}, vcsRootName=${root.name}, checkoutRules='$checkoutRules'"
            is ReplaceEvent.LabelingMoved ->
                "Updated VCS labeling: buildTypeId=$buildTypeId, featureId=$featureId, from=$fromRootId, to=$toRootId"
            is ReplaceEvent.RootDetached ->
                "Detached VCS Root: buildTypeId=${buildType.id}, buildTypeName=${buildType.name}, " +
                    "vcsRootId=${root.id}, vcsRootName=${root.name}"
            is ReplaceEvent.BuildTypeSwitched ->
                "Switched VCS: buildType=${buildType.id}-${buildType.name} -> root=${root.id}-${root.name}, " +
                    "checkoutRules='$checkoutRules' (dryRun = $dryRun)"
            is ReplaceEvent.UpdateStarted -> "Git VCS Root update report"
            is ReplaceEvent.RootUrlUpdated -> "Updated Git VCS Root: id=${root.id}, name=${root.name}"
        }

    companion object {
        const val COMMAND = "replace-vcs-root"
        const val OLD_VCS_ROOT = "--old-vcs-root"
        const val NEW_VCS_ROOT = "--new-vcs-root"
        const val DRY_RUN = "--dry-run"

        private const val GIT_URL_RULE =
            "must be a valid Git URL (e.g. ssh://git@host/org/repo.git or git@host:org/repo.git or " +
                "https://host/org/repo.git). Use only lowercase."
    }
}
