package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.subcommands


fun main(args: Array<String>) {
    TeamcityCommand().subcommands(
        TeamcityCreateBuildChainCommand(),
        TeamcityReplaceVcsRootCommand(),
        TeamcityUpdateParameterCommand().subcommands(
            TeamcityUpdateParameterSetCommand(),
            TeamcityUpdateParameterIncrementCommand()
        )
    ).main(args)
}