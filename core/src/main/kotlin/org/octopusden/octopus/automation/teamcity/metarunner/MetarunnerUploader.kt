package org.octopusden.octopus.automation.teamcity.metarunner

import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.uploadMetarunner
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.io.path.name

/** Uploads every `.xml` file in a zip, under its file name, as a metarunner of a project. */
class MetarunnerUploader(
    private val client: TeamcityClient,
) {
    private val log = LoggerFactory.getLogger(MetarunnerUploader::class.java)

    /** Reads [zip] to the end; the caller closes it. */
    fun upload(
        projectId: String,
        zip: InputStream,
    ) {
        require(projectId.isNotBlank()) { "projectId is blank" }
        val zipFile = ZipInputStream(zip.buffered())
        var entry: ZipEntry?
        while (zipFile.nextEntry.also { entry = it } != null) {
            entry?.let {
                if (!it.isDirectory && it.name.endsWith(".xml")) {
                    val metarunner = Paths.get(it.name).name
                    log.info("Upload metarunner '$metarunner' for project with id $projectId")
                    client.uploadMetarunner(
                        projectId,
                        metarunner,
                        ByteArrayOutputStream()
                            .apply {
                                zipFile.copyTo(this)
                            }.toByteArray(),
                    )
                }
            }
        }
    }
}
