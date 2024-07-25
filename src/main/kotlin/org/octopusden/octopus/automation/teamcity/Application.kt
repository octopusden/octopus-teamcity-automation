package org.octopusden.octopus.automation.teamcity

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.required
import org.apache.commons.text.StringSubstitutor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider

open class Application() {
    private val logger: Logger = LoggerFactory.getLogger(Application::class.java)
    val parser = ArgParser(Application::class.java.simpleName)

    // TODO: +Custom parameters
    private val teamcityUrl by parser.option(
        type = ArgType.String,
        fullName = "teamcity.url",
        shortName = "t",
        description = "Teamcity Url",
    ).required()

    private val teamcityUser by parser.option(
        type = ArgType.String,
        fullName = "teamcity.user",
        shortName = "u",
        description = "Teamcity user"
    ).required()

    private val teamcityPassword by parser.option(
        type = ArgType.String,
        fullName = "teamcity.password",
        shortName = "p",
        description = "Teamcity password"
    ).required()

    private val componentsRegistryUrl by parser.option(
        type = ArgType.String,
        fullName = "registry.url",
        description = "Components Registry service Url"
    ).required()
    // end of parameters

    val componentsRegistryClient by lazy {
        ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl(): String {
                    return componentsRegistryUrl
                }
            }
        )
    }

    fun getTeamCityClient() = TeamcityClassicClient(
        object : ClientParametersProvider {
            override fun getApiUrl() = teamcityUrl
            override fun getAuth() = StandardBasicCredCredentialProvider(teamcityUser, teamcityPassword)
        }
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
    val substitutor = StringSubstitutor(System.getenv())
    args.forEachIndexed{ i, arg ->
        args[i] = substitutor.replace(arg)
    }
    Application().run(args)
}