package com.openminis.app.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class LocalZipBackupTest {
    @Test fun `local backup is a standard zip`() {
        assertEquals("zip", BackupFormat.FILE_EXTENSION)
        assertEquals("application/zip", BackupFormat.MIME_TYPE)
        val dir = kotlin.io.path.createTempDirectory("local-backup-").toFile()
        try {
            val input = File(dir, "input").apply { mkdirs() }
            File(input, "manifest.json").writeText("{}")
            val output = File(dir, "backup.zip")
            BackupZip.archive(input, output)
            ZipFile(output).use { zip ->
                assertNotNull(zip.getEntry("manifest.json"))
            }
        } finally { dir.deleteRecursively() }
    }
}
