package com.maven.cleaner.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class EndToEndTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createArtifact(repoRoot: Path, groupId: String, artifactId: String, versions: List<String>): Path {
        val groupPath = groupId.replace('.', '/')
        val artifactDir = repoRoot.resolve(groupPath).resolve(artifactId).createDirectories()

        for (version in versions) {
            val versionDir = artifactDir.resolve(version).createDirectories()
            versionDir.resolve("$artifactId-$version.pom").writeText("<project/>")
            versionDir.resolve("$artifactId-$version.jar").writeText("x".repeat(100))
        }

        // Create maven-metadata-local.xml
        val versionEntries = versions.joinToString("\n") { "      <version>$it</version>" }
        val sorted = versions.sortedWith(VersionStringComparator())
        val latest = sorted.last()
        val release = sorted.lastOrNull { !it.endsWith("-SNAPSHOT") } ?: latest
        artifactDir.resolve("maven-metadata-local.xml").writeText("""<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>$groupId</groupId>
  <artifactId>$artifactId</artifactId>
  <versioning>
    <latest>$latest</latest>
    <release>$release</release>
    <versions>
$versionEntries
    </versions>
    <lastUpdated>20230101000000</lastUpdated>
  </versioning>
</metadata>""")

        return artifactDir
    }

    @Test
    fun testFullCleanupFlow() = runBlocking {
        // Setup: 3 artifacts with multiple versions
        createArtifact(tempDir, "com.example", "lib-a", listOf("1.0", "1.1", "2.0"))
        createArtifact(tempDir, "com.example", "lib-b", listOf("1.0", "2.0", "3.0", "4.0"))
        createArtifact(tempDir, "org.other", "util", listOf("0.1", "0.2"))

        val scanner = RepositoryScanner(tempDir)
        val cleaner = ArtifactCleaner(allowedRoots = listOf(tempDir))

        // Scan
        val artifacts = scanner.scan()
        assertEquals(3, artifacts.size)

        // Find old versions
        val oldVersions = artifacts.flatMap { cleaner.findOldVersions(it) }
        assertTrue(oldVersions.isNotEmpty())

        // Verify latest is NOT in old versions
        for (art in artifacts) {
            val sorted = art.versions.sortedWith(VersionComparator())
            val latest = sorted.last()
            assertFalse(oldVersions.contains(latest),
                "Latest version ${latest.version} of ${art.groupId}:${art.artifactId} should not be in old versions")
        }

        // Delete old versions
        val freed = cleaner.deleteVersions(oldVersions)
        assertTrue(freed > 0)

        // Verify old version directories are gone
        for (v in oldVersions) {
            assertFalse(v.path.exists(), "Old version should be deleted: ${v.path}")
        }

        // Verify latest versions still exist
        for (art in artifacts) {
            val sorted = art.versions.sortedWith(VersionComparator())
            val latest = sorted.last()
            assertTrue(latest.path.exists(), "Latest version should still exist: ${latest.path}")
        }

        // Verify metadata is updated
        val libA = artifacts.first { it.artifactId == "lib-a" }
        val metadataPath = libA.versions[0].path.parent.parent.resolve("maven-metadata-local.xml")
        if (metadataPath.exists()) {
            val content = metadataPath.readText()
            assertTrue(content.contains("<version>2.0</version>"), "Metadata should contain latest version 2.0")
            assertFalse(content.contains("<version>1.0</version>"), "Metadata should not contain deleted version 1.0")
            assertFalse(content.contains("<version>1.1</version>"), "Metadata should not contain deleted version 1.1")
        }
    }

    @Test
    fun testSnapshotCleanupFlow() = runBlocking {
        createArtifact(tempDir, "com.example", "mylib", listOf("1.0", "1.1-SNAPSHOT", "2.0"))

        val scanner = RepositoryScanner(tempDir)
        val cleaner = ArtifactCleaner(allowedRoots = listOf(tempDir))
        val artifacts = scanner.scan()

        val oldSnapshots = artifacts.flatMap { cleaner.findOldSnapshots(it) }
        val snapshotVersions = oldSnapshots.map { it.version }

        assertTrue(snapshotVersions.contains("1.1-SNAPSHOT"),
            "1.1-SNAPSHOT should be marked for deletion")

        cleaner.deleteVersions(oldSnapshots)

        val v1Dir = tempDir.resolve("com/example/mylib/1.0")
        val snapDir = tempDir.resolve("com/example/mylib/1.1-SNAPSHOT")
        val v2Dir = tempDir.resolve("com/example/mylib/2.0")

        assertTrue(v1Dir.exists(), "1.0 should survive")
        assertFalse(snapDir.exists(), "1.1-SNAPSHOT should be deleted")
        assertTrue(v2Dir.exists(), "2.0 should survive")
    }

    @Test
    fun testCleanupPreservesUnrelatedArtifacts() = runBlocking {
        createArtifact(tempDir, "com.example", "lib-a", listOf("1.0", "2.0"))
        createArtifact(tempDir, "com.example", "lib-b", listOf("1.0", "2.0"))

        val scanner = RepositoryScanner(tempDir)
        val cleaner = ArtifactCleaner(allowedRoots = listOf(tempDir))
        val artifacts = scanner.scan()

        // Only delete old versions of lib-a
        val libA = artifacts.first { it.artifactId == "lib-a" }
        val oldA = cleaner.findOldVersions(libA)
        cleaner.deleteVersions(oldA)

        // lib-b should be completely untouched
        val libB = artifacts.first { it.artifactId == "lib-b" }
        for (v in libB.versions) {
            assertTrue(v.path.exists(), "lib-b version ${v.version} should be untouched")
        }
    }

    @Test
    fun testCleanupReportsCorrectFreedSpace() = runBlocking {
        val artifactDir = tempDir.resolve("com/example/mylib").createDirectories()
        val v1Dir = artifactDir.resolve("1.0").createDirectories()
        v1Dir.resolve("mylib-1.0.pom").writeText("<project/>")
        val knownContent = "x".repeat(5000)
        v1Dir.resolve("mylib-1.0.jar").writeText(knownContent)

        val v2Dir = artifactDir.resolve("2.0").createDirectories()
        v2Dir.resolve("mylib-2.0.pom").writeText("<project/>")

        val scanner = RepositoryScanner(tempDir)
        val cleaner = ArtifactCleaner(allowedRoots = listOf(tempDir))
        val artifacts = scanner.scan()

        val old = cleaner.findOldVersions(artifacts.first())
        assertEquals(1, old.size)
        assertEquals("1.0", old[0].version)

        val freed = cleaner.deleteVersions(old)
        assertTrue(freed >= 5000, "Should have freed at least the jar size, got: $freed")
    }

    @Test
    fun testIdempotentCleanup() = runBlocking {
        createArtifact(tempDir, "com.example", "mylib", listOf("1.0", "2.0", "3.0"))

        val scanner = RepositoryScanner(tempDir)
        val cleaner = ArtifactCleaner(allowedRoots = listOf(tempDir))

        // First pass
        val artifacts1 = scanner.scan()
        val old1 = artifacts1.flatMap { cleaner.findOldVersions(it) }
        assertTrue(old1.isNotEmpty())
        cleaner.deleteVersions(old1)

        // Second pass — should find nothing to delete
        val artifacts2 = scanner.scan()
        val old2 = artifacts2.flatMap { cleaner.findOldVersions(it) }
        assertTrue(old2.isEmpty(), "Second cleanup pass should find nothing to delete")
    }

    @Test
    fun testCleanupWithOnlyOneVersionPerArtifact() = runBlocking {
        createArtifact(tempDir, "com.example", "lib-a", listOf("1.0"))
        createArtifact(tempDir, "com.example", "lib-b", listOf("2.0"))
        createArtifact(tempDir, "org.other", "util", listOf("0.1"))

        val scanner = RepositoryScanner(tempDir)
        val cleaner = ArtifactCleaner(allowedRoots = listOf(tempDir))
        val artifacts = scanner.scan()

        val old = artifacts.flatMap { cleaner.findOldVersions(it) }
        assertTrue(old.isEmpty(), "No old versions should be found when each artifact has only one version")

        val snapshots = artifacts.flatMap { cleaner.findOldSnapshots(it) }
        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun testCleanupWithDeepGroupId() = runBlocking {
        createArtifact(tempDir, "org.apache.commons", "commons-lang3", listOf("3.11", "3.12.0", "3.13.0"))

        val scanner = RepositoryScanner(tempDir)
        val cleaner = ArtifactCleaner(allowedRoots = listOf(tempDir))
        val artifacts = scanner.scan()

        assertEquals(1, artifacts.size)
        assertEquals("org.apache.commons", artifacts[0].groupId)

        val old = cleaner.findOldVersions(artifacts[0])
        cleaner.deleteVersions(old)

        val latestDir = tempDir.resolve("org/apache/commons/commons-lang3/3.13.0")
        assertTrue(latestDir.exists(), "Latest version 3.13.0 should survive")

        val oldDir = tempDir.resolve("org/apache/commons/commons-lang3/3.11")
        assertFalse(oldDir.exists(), "Old version 3.11 should be deleted")
    }

    @Test
    fun testMetadataCorrectAfterPartialCleanup() = runBlocking {
        createArtifact(tempDir, "com.example", "mylib", listOf("1.0", "1.1", "2.0", "3.0"))

        val scanner = RepositoryScanner(tempDir)
        val cleaner = ArtifactCleaner(allowedRoots = listOf(tempDir))
        val artifacts = scanner.scan()

        val old = cleaner.findOldVersions(artifacts.first())
        // Delete only versions 1.0 and 1.1, keep 2.0 and 3.0
        val toDelete = old.filter { it.version in listOf("1.0", "1.1") }
        cleaner.deleteVersions(toDelete)

        val metadataPath = tempDir.resolve("com/example/mylib/maven-metadata-local.xml")
        assertTrue(metadataPath.exists())
        val content = metadataPath.readText()

        assertTrue(content.contains("<version>2.0</version>"))
        assertTrue(content.contains("<version>3.0</version>"))
        assertFalse(content.contains("<version>1.0</version>"))
        assertFalse(content.contains("<version>1.1</version>"))
        assertTrue(content.contains("<latest>3.0</latest>"))
    }
}
