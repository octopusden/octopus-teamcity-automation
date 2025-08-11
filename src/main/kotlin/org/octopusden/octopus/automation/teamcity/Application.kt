package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.subcommands

const val SPLIT_SYMBOLS = "[,;]"

fun main(args: Array<String>) {
    TeamcityCommand().subcommands(
        TeamcityCreateBuildChainCommand(),
        TeamcityReplaceVcsRootCommand(),
        TeamcityUpdateParameterCommand().subcommands(
            TeamcityUpdateParameterSetCommand(),
            TeamcityUpdateParameterIncrementCommand()
        ),
        TeamcityUploadMetarunnersCommand(),
        TeamcityGetBuildTypesAgentRequirementsCommand(),
        TeamcityBuildQueueCommand(),
        TeamcityRenameComponentCommand(),
        TeamcityCreateEscrowConfigCommand()
    ).main(args)
}