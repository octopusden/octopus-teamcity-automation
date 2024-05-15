package org.octopusden.octopus.automation.teamcity

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import org.jetbrains.teamcity.rest.ProjectId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.jetbrains.teamcity.rest.TeamCityInstance

open class Application() {
    private val logger: Logger = LoggerFactory.getLogger(Application::class.java)
    private val parser = ArgParser(Application::class.java.simpleName)

  // TODO: +Custom parameters
    private val teamcityUrl by parser.option(
        type = ArgType.String,
        shortName = "t",
        description = "Teamcity Url"
    ).required()

    private val teamcityUser by parser.option(
        type = ArgType.String,
        shortName = "u",
        description = "Teamcity user"
    ).required()

    private val teamcityPassword by parser.option(
        type = ArgType.String,
        shortName = "p",
        description = "Teamcity password"
    ).required()

    private val parentProjectId by parser.option(
        type = ArgType.String,
        shortName = "pp",
        description = "Teamcity parent project Id"
    ).required()

    private val componentName by parser.option(
        type = ArgType.String,
        shortName = "c",
        description = "Component registry name"
    ).required()

    private val minorVersion by parser.option(
        type = ArgType.String,
        shortName = "m",
        description = "Minor version"
    ).required()
    // end of parameters

    fun run(args: Array<String>) {
        logger.debug("args = {}", args)
        parser.parse(args)
        // TODO: Custom code
        val tc = TeamCityInstance.httpAuth(
            serverUrl = teamcityUrl,
            username = teamcityUser,
            password = teamcityPassword
        )
        val parentProject = tc.project(ProjectId(parentProjectId))
        logger.info("Project    : {} {}", parentProject.name, parentProject.id.stringId)
    }
}

fun main(args: Array<String>) {
    Application().run(args)
}