package org.octopusden.octopus.automation.teamcity.metarunner

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MetarunnerZipTest {
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

    private fun namesAndContents(vararg entries: Pair<String, String?>) =
        MetarunnerZip.entries(zipOf(*entries)).map { it.name to String(it.content) }.toList()

    @Test
    fun takesXmlFilesUnderTheirFileNameInZipOrder() {
        Assertions.assertEquals(
            listOf("A.xml" to "<a/>", "B.xml" to "<b/>"),
            namesAndContents("runners/" to null, "runners/A.xml" to "<a/>", "README.md" to "skip", "B.xml" to "<b/>"),
        )
    }

    @Test
    fun skipsDirectoriesNamedLikeXml() {
        Assertions.assertEquals(emptyList<Pair<String, String>>(), namesAndContents("odd.xml/" to null))
    }
}
