package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
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
        VcsRootReplacer(client).replace(oldVcsRoot, newVcsRoot, dryRun)
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
