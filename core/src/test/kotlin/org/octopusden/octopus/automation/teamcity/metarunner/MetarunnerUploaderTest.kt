package org.octopusden.octopus.automation.teamcity.metarunner

import feign.form.FormData
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.octopusden.octopus.automation.teamcity.RecordingTeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityServer
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MetarunnerUploaderTest {
    private fun zipOf(vararg entries: Pair<String, String?>) =
        ByteArrayOutputStream()
            .also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    entries.forEach { (name, content) ->
                        zip.putNextEntry(ZipEntry(name))
                        content?.let { zip.write(it.toByteArray()) }
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
            .inputStream()

    @Test
    fun uploadsEachXmlUnderItsFileName() {
        // The client's upload path depends on the server version; this is the pre-2025 form upload.
        val recorder = RecordingTeamcityClient { method, _ -> if (method == "getServer") TeamcityServer("2022.04.7") else null }

        MetarunnerUploader(recorder.client).upload(
            "Proj",
            zipOf("runners/" to null, "runners/A.xml" to "<a/>", "B.xml" to "<b/>", "README.md" to "skip"),
        )

        // uploadMetarunner(fileName, form, action, projectId)
        val uploads = recorder.callsOf("uploadMetarunner").map { call ->
            val form = call.args[1] as FormData
            Triple(call.args[3], form.fileName, String(form.data))
        }
        Assertions.assertEquals(listOf(Triple("Proj", "A.xml", "<a/>"), Triple("Proj", "B.xml", "<b/>")), uploads)
    }
}
