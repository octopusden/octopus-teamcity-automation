package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.slf4j.Logger

class TeamcityCreateBuildChainCommand : CliktCommand(name = "create-build-chain") {
    private val context by requireObject<MutableMap<String, Any>>()

    override fun run() {
        val log = context[TeamcityCommand.LOG] as Logger
        val client = context[TeamcityCommand.CLIENT] as TeamcityClient
        log.info("Create build chain")
        TODO("Not yet implemented")
    }
}