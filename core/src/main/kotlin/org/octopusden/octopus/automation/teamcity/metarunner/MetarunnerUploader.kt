package org.octopusden.octopus.automation.teamcity.metarunner

import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.uploadMetarunner
import java.io.InputStream

/** Uploads every `.xml` file in a zip, under its file name, as a metarunner of a project. */
class MetarunnerUploader(
    private val client: TeamcityClient,
) {
    /**
     * Reads [zip] to the end; the caller closes it. [beforeUpload] receives each metarunner's name before
     * it is uploaded. Returns the uploaded names, in zip order.
     */
    fun upload(
        projectId: String,
        zip: InputStream,
        beforeUpload: (String) -> Unit = {},
    ): List<String> {
        require(projectId.isNotBlank()) { "projectId is blank" }
        return MetarunnerZip
            .entries(zip)
            .map { metarunner ->
                beforeUpload(metarunner.name)
                client.uploadMetarunner(projectId, metarunner.name, metarunner.content)
                metarunner.name
            }.toList()
    }
}
