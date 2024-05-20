package org.octopusden.octopus.automation.teamcity

import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.required
import org.jetbrains.teamcity.rest.ProjectId
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@kotlinx.cli.ExperimentalCli
class CmdCreateCDBuildChain(val application: Application) : Subcommand(
    name = "createCDBuildChain",
    actionDescription = "Create TeamCity CD build chain for component"
) {
    private val logger: Logger = LoggerFactory.getLogger(CmdCreateCDBuildChain::class.java)

    private val parentProjectId by option(
        type = ArgType.String,
        fullName = "project.parent",
        description = "Teamcity parent project Id"
    ).required()

    private val componentName by option(
        type = ArgType.String,
        fullName = "component",
        description = "Component registry name"
    ).required()

    private val minorVersion by option(
        type = ArgType.String,
        fullName = "version.minor",
        description = "Minor version"
    ).required()

    override fun execute() {
        val tc = application.getTeamCityInstance()
        // TODO: Custom code
        val parentProject = tc.project(ProjectId(parentProjectId))
        logger.info("Project    : {} {}", parentProject.name, parentProject.id.stringId)
    }
}