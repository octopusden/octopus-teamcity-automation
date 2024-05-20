package org.octopusden.octopus.automation.teamcity

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.required
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.jetbrains.teamcity.rest.TeamCityInstance

open class Application() {
    private val logger: Logger = LoggerFactory.getLogger(Application::class.java)
    val parser = ArgParser(Application::class.java.simpleName)

    // TODO: +Custom parameters
    val teamcityUrl by parser.option(
        type = ArgType.String,
        fullName = "teamcity.url",
        shortName = "t",
        description = "Teamcity Url",
    ).required()

    val teamcityUser by parser.option(
        type = ArgType.String,
        fullName = "teamcity.user",
        shortName = "u",
        description = "Teamcity user"
    ).required()

    val teamcityPassword by parser.option(
        type = ArgType.String,
        fullName = "teamcity.password",
        shortName = "p",
        description = "Teamcity password"
    ).required()
    // end of parameters

    fun getTeamCityInstance(): TeamCityInstance = TeamCityInstance.httpAuth(
        serverUrl = teamcityUrl,
        username = teamcityUser,
        password = teamcityPassword
    )

    @OptIn(ExperimentalCli::class)
    fun run(args: Array<String>) {
        logger.debug("args = {}", args)
        parser.subcommands(
            CmdCreateCDBuildChain(this),
            CmdReplaceTeamcityVcsRoot(this)
        )
        parser.parse(args)
    }
}

fun main(args: Array<String>) {
    Application().run(args)
}