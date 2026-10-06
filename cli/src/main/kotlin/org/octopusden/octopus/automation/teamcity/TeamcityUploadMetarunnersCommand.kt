package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.automation.teamcity.metarunner.MetarunnerUploader
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import java.net.URI

class TeamcityUploadMetarunnersCommand : CliktCommand(name = COMMAND) {
    private val projectId by option(PROJECT_ID_OPTION, help = "TeamCity project id")
        .convert { it.trim() }
        .required()
        .check("PROJECT_ID_OPTION is empty") { it.isNotEmpty() }
    private val zip by option(ZIP_OPTION, help = "URL of a zip file with metarunners")
        .convert { URI(it).toURL() }
        .required()

    private val context by requireObject<MutableMap<String, Any>>()

    override fun run() {
        val client = context[TeamcityCommand.CLIENT] as TeamcityClient
        zip.openStream().use { MetarunnerUploader(client).upload(projectId, it) }
    }

    companion object {
        const val COMMAND = "upload-metarunners"
        const val PROJECT_ID_OPTION = "--project-id"
        const val ZIP_OPTION = "--zip"
    }
}
