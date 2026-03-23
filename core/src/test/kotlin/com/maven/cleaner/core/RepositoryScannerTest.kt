package com.maven.cleaner.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class RepositoryScannerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createPom(repoRoot: Path, groupPath: String, artifactId: String, version: String, content: String = ""): Path {
        val versionDir = repoRoot.resolve(groupPath).resolve(artifactId).resolve(version).createDirectories()
        val pomFile = versionDir.resolve("$artifactId-$version.pom").createFile()
        if (content.isNotEmpty()) pomFile.writeText(content)
        return versionDir
    }

    @Test
    fun testScanEmptyRepository() {
        val scanner = RepositoryScanner(tempDir)
        val artifacts = scanner.scan()
        assertTrue(artifacts.isEmpty())
    }

    @Test
    fun testScanNonExistentPath() {
        val scanner = RepositoryScanner(tempDir.resolve("does-not-exist"))
        val artifacts = scanner.scan()
        assertTrue(artifacts.isEmpty())
    }

    @Test
    fun testScanSingleArtifactSingleVersion() {
        createPom(tempDir, "com/example", "mylib", "1.0")
        val scanner = RepositoryScanner(tempDir)
        val artifacts = scanner.scan()

        assertEquals(1, artifacts.size)
        val art = artifacts[0]
        assertEquals("com.example", art.groupId)
        assertEquals("mylib", art.artifactId)
        assertEquals(1, art.versions.size)
        assertEquals("1.0", art.versions[0].version)
    }

    @Test
    fun testScanSingleArtifactMultipleVersions() {
        createPom(tempDir, "com/example", "mylib", "1.0")
        createPom(tempDir, "com/example", "mylib", "1.1")
        createPom(tempDir, "com/example", "mylib", "2.0")

        val scanner = RepositoryScanner(tempDir)
        val artifacts = scanner.scan()

        assertEquals(1, artifacts.size)
        val versions = artifacts[0].versions.map { it.version }.sorted()
        assertEquals(listOf("1.0", "1.1", "2.0"), versions)
    }

    @Test
    fun testScanMultipleArtifacts() {
        createPom(tempDir, "com/example", "lib-a", "1.0")
        createPom(tempDir, "org/other", "lib-b", "2.0")

        val scanner = RepositoryScanner(tempDir)
        val artifacts = scanner.scan()

        assertEquals(2, artifacts.size)
        val groupIds = artifacts.map { it.groupId }.toSet()
        assertTrue(groupIds.contains("com.example"))
        assertTrue(groupIds.contains("org.other"))
    }

    @Test
    fun testGroupIdExtractionDeepNesting() {
        createPom(tempDir, "org/apache/commons", "commons-lang3", "3.12.0")

        val scanner = RepositoryScanner(tempDir)
        val artifacts = scanner.scan()

        assertEquals(1, artifacts.size)
        assertEquals("org.apache.commons", artifacts[0].groupId)
        assertEquals("commons-lang3", artifacts[0].artifactId)
    }

    @Test
    fun testSnapshotVersionDetection() {
        createPom(tempDir, "com/example", "mylib", "1.0-SNAPSHOT")

        val scanner = RepositoryScanner(tempDir)
        val artifacts = scanner.scan()

        assertEquals(1, artifacts.size)
        assertTrue(artifacts[0].versions[0].isSnapshot)
    }

    @Test
    fun testNonSnapshotVersionDetection() {
        createPom(tempDir, "com/example", "mylib", "1.0")

        val scanner = RepositoryScanner(tempDir)
        val artifacts = scanner.scan()

        assertFalse(artifacts[0].versions[0].isSnapshot)
    }

    @Test
    fun testDirectoryWithoutPomIsNotArtifact() {
        val dir = tempDir.resolve("com/example/mylib/1.0").createDirectories()
        dir.resolve("mylib-1.0.jar").createFile()

        val scanner = RepositoryScanner(tempDir)
        val artifacts = scanner.scan()

        assertTrue(artifacts.isEmpty())
    }

    @Test
    fun testSizeCalculation() {
        val versionDir = createPom(tempDir, "com/example", "mylib", "1.0", "pom-content")
        val jarFile = versionDir.resolve("mylib-1.0.jar")
        jarFile.writeText("x".repeat(1000))

        val scanner = RepositoryScanner(tempDir)
        val artifacts = scanner.scan()

        // Size includes both the .pom file and the .jar file
        val expectedMinSize = 1000L + "pom-content".length
        assertTrue(artifacts[0].versions[0].size >= expectedMinSize,
            "Size should be at least $expectedMinSize, was: ${artifacts[0].versions[0].size}")
    }

    @Test
    fun testCalculateTotalRepositorySize() {
        createPom(tempDir, "com/example", "mylib", "1.0", "x".repeat(500))

        val scanner = RepositoryScanner(tempDir)
        val totalSize = scanner.calculateTotalRepositorySize()

        assertTrue(totalSize >= 500, "Total size should be at least 500, was: $totalSize")
    }

    @Test
    fun testScanIgnoresFilesAtRootLevel() {
        tempDir.resolve("some-random-file.txt").writeText("hello")
        createPom(tempDir, "com/example", "mylib", "1.0")

        val scanner = RepositoryScanner(tempDir)
        val artifacts = scanner.scan()

        assertEquals(1, artifacts.size)
    }

    @Test
    fun testGetRepositoryPath() {
        val scanner = RepositoryScanner(tempDir)
        assertEquals(tempDir, scanner.getRepositoryPath())
    }
}
