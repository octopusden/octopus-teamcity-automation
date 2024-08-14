package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.findOrSetObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.infrastructure.client.commons.ClientParametersProvider
import org.octopusden.octopus.infrastructure.client.commons.StandardBasicCredCredentialProvider
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClassicClient
import org.slf4j.LoggerFactory

class TeamcityCommand : CliktCommand(name = "") {
    private val url by option(URL_OPTION, help = "TeamCity URL").convert { it.trim() }.required()
        .check("$URL_OPTION is empty") { it.isNotEmpty() }
    private val user by option(USER_OPTION, help = "TeamCity user").convert { it.trim() }.required()
        .check("$USER_OPTION is empty") { it.isNotEmpty() }
    private val password by option(PASSWORD_OPTION, help = "TeamCity password").convert { it.trim() }.required()
        .check("$PASSWORD_OPTION is empty") { it.isNotEmpty() }

    private val context by findOrSetObject { mutableMapOf<String, Any>() }

    override fun run() {
        val log = LoggerFactory.getLogger(TeamcityCommand::class.java.`package`.name)
        val client = TeamcityClassicClient(object : ClientParametersProvider {
            override fun getApiUrl() = url
            override fun getAuth() = StandardBasicCredCredentialProvider(user, password)
        })
        log.info("TeamCity server version - ${client.getServer().version}")
        context[LOG] = log
        context[CLIENT] = client
    }

    companion object {
        const val URL_OPTION = "--url"
        const val USER_OPTION = "--user"
        const val PASSWORD_OPTION = "--password"
        const val LOG = "log"
        const val CLIENT = "client"
    }
}