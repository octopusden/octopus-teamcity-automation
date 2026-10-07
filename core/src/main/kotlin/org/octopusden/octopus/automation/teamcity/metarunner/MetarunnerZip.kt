package org.octopusden.octopus.automation.teamcity.metarunner

import java.io.InputStream
import java.nio.file.Paths
import java.util.zip.ZipInputStream
import kotlin.io.path.name

/** Which zip entries are metarunners, and under which names, with no TeamCity calls. */
internal object MetarunnerZip {
    class Metarunner(
        val name: String,
        val content: ByteArray,
    )

    /** Every `.xml` file entry, named by its file name without directories, read lazily in zip order. */
    fun entries(zip: InputStream): Sequence<Metarunner> {
        val zipFile = ZipInputStream(zip.buffered())
        return generateSequence { zipFile.nextEntry }
            .filter { !it.isDirectory && it.name.endsWith(".xml") }
            .map { Metarunner(Paths.get(it.name).name, zipFile.readBytes()) }
    }
}
