package com.maven.cleaner.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class MetadataRefresherTest {

    @TempDir
    lateinit var tempDir: Path

    private val refresher = MetadataRefresher()

    private fun createMetadata(artifactDir: Path, versions: List<String>, filename: String = "maven-metadata-local.xml"): Path {
        val versionEntries = versions.joinToString("\n") { "          <version>$it</version>" }
        val latest = versions.lastOrNull() ?: ""
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>com.example</groupId>
  <artifactId>mylib</artifactId>
  <versioning>
    <latest>$latest</latest>
    <release>$latest</release>
    <versions>
$versionEntries
    </versions>
    <lastUpdated>20230101000000</lastUpdated>
  </versioning>
</metadata>"""
        val file = artifactDir.resolve(filename)
        file.writeText(xml)
        return file
    }

    @Test
    fun testRefreshRemovesDeletedVersion() {
        val artifactDir = tempDir.resolve("com/example/mylib").createDirectories()
        artifactDir.resolve("1.0").createDirectories()
        artifactDir.resolve("2.0").createDirectories()
        // Version 1.1 is in metadata but NOT on disk
        createMetadata(artifactDir, listOf("1.0", "1.1", "2.0"))

        refresher.refreshMetadata(artifactDir)

        val content = artifactDir.resolve("maven-metadata-local.xml").readText()
        assertTrue(content.contains("<version>1.0</version>"))
        assertTrue(content.contains("<version>2.0</version>"))
        assertFalse(content.contains("<version>1.1</version>"))
    }

    @Test
    fun testRefreshUpdatesLatestToHighestRemaining() {
        val artifactDir = tempDir.resolve("com/example/mylib").createDirectories()
        artifactDir.resolve("1.0").createDirectories()
        artifactDir.resolve("1.1").createDirectories()
        // 2.0 was in metadata but deleted from disk
        createMetadata(artifactDir, listOf("1.0", "1.1", "2.0"))

        refresher.refreshMetadata(artifactDir)

        val content = artifactDir.resolve("maven-metadata-local.xml").readText()
        assertTrue(content.contains("<latest>1.1</latest>"))
    }

    @Test
    fun testRefreshUpdatesReleaseElement() {
        val artifactDir = tempDir.resolve("com/example/mylib").createDirectories()
        artifactDir.resolve("1.0").createDirectories()
        createMetadata(artifactDir, listOf("1.0", "2.0"))

        refresher.refreshMetadata(artifactDir)

        val content = artifactDir.resolve("maven-metadata-local.xml").readText()
        assertTrue(content.contains("<release>1.0</release>"))
    }

    @Test
    fun testRefreshDoesNotSetReleaseForSnapshot() {
        val artifactDir = tempDir.resolve("com/example/mylib").createDirectories()
        artifactDir.resolve("1.0-SNAPSHOT").createDirectories()
        val versionEntries = "<version>1.0-SNAPSHOT</version>\n          <version>2.0</version>"
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>com.example</groupId>
  <artifactId>mylib</artifactId>
  <versioning>
    <latest>2.0</latest>
    <release>2.0</release>
    <versions>
          $versionEntries
    </versions>
    <lastUpdated>20230101000000</lastUpdated>
  </versioning>
</metadata>"""
        artifactDir.resolve("maven-metadata-local.xml").writeText(xml)

        refresher.refreshMetadata(artifactDir)

        val content = artifactDir.resolve("maven-metadata-local.xml").readText()
        // Only 1.0-SNAPSHOT remains, which is a snapshot
        // The release element should not be set to a SNAPSHOT
        assertFalse(content.contains("<release>1.0-SNAPSHOT</release>"))
    }

    @Test
    fun testRefreshUpdatesLastUpdatedTimestamp() {
        val artifactDir = tempDir.resolve("com/example/mylib").createDirectories()
        artifactDir.resolve("1.0").createDirectories()
        createMetadata(artifactDir, listOf("1.0"))

        refresher.refreshMetadata(artifactDir)

        val content = artifactDir.resolve("maven-metadata-local.xml").readText()
        assertFalse(content.contains("<lastUpdated>20230101000000</lastUpdated>"),
            "Timestamp should have been updated from the old value")
    }

    @Test
    fun testRefreshBothMetadataFiles() {
        val artifactDir = tempDir.resolve("com/example/mylib").createDirectories()
        artifactDir.resolve("1.0").createDirectories()
        createMetadata(artifactDir, listOf("1.0", "2.0"), "maven-metadata-local.xml")
        createMetadata(artifactDir, listOf("1.0", "2.0"), "maven-metadata.xml")

        refresher.refreshMetadata(artifactDir)

        val local = artifactDir.resolve("maven-metadata-local.xml").readText()
        val remote = artifactDir.resolve("maven-metadata.xml").readText()
        assertFalse(local.contains("<version>2.0</version>"))
        assertFalse(remote.contains("<version>2.0</version>"))
    }

    @Test
    fun testRefreshNoMetadataFileIsNoop() {
        val artifactDir = tempDir.resolve("com/example/mylib").createDirectories()
        artifactDir.resolve("1.0").createDirectories()
        // No metadata file — should not throw
        refresher.refreshMetadata(artifactDir)
    }

    @Test
    fun testRefreshNonExistentDirectoryIsNoop() {
        refresher.refreshMetadata(tempDir.resolve("does-not-exist"))
    }

    @Test
    fun testCleanupWhenNoVersionsRemain() {
        val artifactDir = tempDir.resolve("com/example/mylib").createDirectories()
        val metadataFile = createMetadata(artifactDir, listOf("1.0"))
        metadataFile.resolveSibling("${metadataFile.fileName}.md5").writeText("abc")
        metadataFile.resolveSibling("${metadataFile.fileName}.sha1").writeText("def")
        // No version directories on disk

        refresher.refreshMetadata(artifactDir)

        assertFalse(metadataFile.exists(), "Metadata should be deleted when no versions remain")
    }

    @Test
    fun testMalformedXmlDoesNotThrow() {
        val artifactDir = tempDir.resolve("com/example/mylib").createDirectories()
        artifactDir.resolve("1.0").createDirectories()
        artifactDir.resolve("maven-metadata-local.xml").writeText("this is not xml at all <><>")

        // Should not throw
        refresher.refreshMetadata(artifactDir)
    }

    @Test
    fun testMetadataWithNoVersioningElement() {
        val artifactDir = tempDir.resolve("com/example/mylib").createDirectories()
        artifactDir.resolve("1.0").createDirectories()
        artifactDir.resolve("maven-metadata-local.xml").writeText("""<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>com.example</groupId>
  <artifactId>mylib</artifactId>
</metadata>""")

        // Should not throw
        refresher.refreshMetadata(artifactDir)
    }
}
