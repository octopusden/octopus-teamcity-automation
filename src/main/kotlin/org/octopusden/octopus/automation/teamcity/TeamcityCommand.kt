package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.slf4j.LoggerFactory

class TeamcityCommand : CliktCommand(name = "") {
    private val url by option("--url", help = "TeamCity URL").required()
    private val user by option("--user", help = "TeamCity user").required()
    private val password by option("--password", help = "TeamCity password").required()

    private val context by findOrSetObject { mutableMapOf<String, Any>() }

    override fun run() {
        context[LOG] = LoggerFactory.getLogger(TeamcityCommand::class.java.`package`.name)
        context[CLIENT] = TeamcityClassicClient(
            object : ClientParametersProvider {
                override fun getApiUrl() = url
                override fun getAuth() = StandardBasicCredCredentialProvider(user, password)
            }
        )
    }

    companion object {
        const val LOG = "log"
        const val CLIENT = "client"
    }
}