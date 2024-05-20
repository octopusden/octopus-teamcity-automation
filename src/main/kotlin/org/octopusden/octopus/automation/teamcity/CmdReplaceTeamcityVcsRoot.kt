package org.octopusden.octopus.automation.teamcity

import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.required
import org.jetbrains.teamcity.rest.ProjectId
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@kotlinx.cli.ExperimentalCli
class CmdReplaceTeamcityVcsRoot(val application: Application) : Subcommand(
    name = "replaceTeamcityVcsRoot",
    actionDescription = "Replace Teamcity VCS root"
) {
    private val logger: Logger = LoggerFactory.getLogger(CmdReplaceTeamcityVcsRoot::class.java)

    private val sourceRoot by option(
        type = ArgType.String,
        fullName = "source.vcs.url",
        description = "Source VCS URL"
    ).required()

    private val targetRoot by option(
        type = ArgType.String,
        fullName = "target.vcs.url",
        description = "Target VCS URL"
    ).required()

    override fun execute() {
//        val tc = application.getTeamCityInstance()
        TODO("replaceTeamcityVcsRoot - Not yet implemented")
    }

}