package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.io.path.name
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.uploadMetarunner
import org.slf4j.Logger

class TeamcityUploadMetarunnersCommand : CliktCommand(name = COMMAND) {
    private val projectId by option(PROJECT_ID_OPTION, help = "TeamCity project id").convert { it.trim() }.required()
        .check("PROJECT_ID_OPTION is empty") { it.isNotEmpty() }
    private val zip by option(ZIP_OPTION, help = "URL of a zip file with metarunners").convert { URI(it).toURL() }
        .required()

    private val context by requireObject<MutableMap<String, Any>>()

    override fun run() {
        val log = context[TeamcityCommand.LOG] as Logger
        val client = context[TeamcityCommand.CLIENT] as TeamcityClient
        ZipInputStream(zip.openStream().buffered()).use { zipFile ->
            var entry: ZipEntry?
            while (zipFile.nextEntry.also { entry = it } != null) {
                entry?.let {
                    if (!it.isDirectory && it.name.endsWith(".xml")) {
                        val metarunner = Paths.get(it.name).name
                        log.info("Upload metarunner '$metarunner' for project with id $projectId")
                        client.uploadMetarunner(
                            projectId, metarunner, ByteArrayOutputStream().apply {
                                zipFile.copyTo(this)
                            }.toByteArray()
                        )
                    }
                }

            }
        }
    }

    companion object {
        const val COMMAND = "upload-metarunners"
        const val PROJECT_ID_OPTION = "--project-id"
        const val ZIP_OPTION = "--zip"
    }
}