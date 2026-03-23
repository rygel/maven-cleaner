package com.maven.cleaner.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText

class ArtifactCleanerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun cleanerForTesting(trashProvider: TrashProvider = NoOpTrashProvider()): ArtifactCleaner {
        return ArtifactCleaner(trashProvider, allowedRoots = listOf(tempDir))
    }

    private fun version(v: String, path: Path = Paths.get(""), size: Long = 0): ArtifactVersion {
        return ArtifactVersion(v, path, size)
    }

    private fun artifact(groupId: String, artifactId: String, versions: List<String>): Artifact {
        return Artifact(groupId, artifactId, versions.map { version(it) })
    }

    // --- findOldVersions tests ---

    @Test
    fun testFindOldVersionsSingleVersion() {
        val art = artifact("com.example", "lib", listOf("1.0"))
        val cleaner = cleanerForTesting()
        assertTrue(cleaner.findOldVersions(art).isEmpty())
    }

    @Test
    fun testFindOldVersionsEmptyVersions() {
        val art = Artifact("com.example", "lib", emptyList())
        val cleaner = cleanerForTesting()
        assertTrue(cleaner.findOldVersions(art).isEmpty())
    }

    @Test
    fun testFindOldVersionsTwoVersions() {
        val art = artifact("com.example", "lib", listOf("1.0", "2.0"))
        val cleaner = cleanerForTesting()
        val old = cleaner.findOldVersions(art)
        assertEquals(1, old.size)
        assertEquals("1.0", old[0].version)
    }

    @Test
    fun testFindOldVersionsMultipleVersions() {
        val art = artifact("com.example", "lib", listOf("1.0", "1.1", "2.0", "3.0"))
        val cleaner = cleanerForTesting()
        val old = cleaner.findOldVersions(art)
        assertEquals(3, old.size)
        val oldVersionStrings = old.map { it.version }.toSet()
        assertTrue(oldVersionStrings.containsAll(listOf("1.0", "1.1", "2.0")))
        assertFalse(oldVersionStrings.contains("3.0"))
    }

    @Test
    fun testFindOldVersionsUnorderedInput() {
        val art = artifact("com.example", "lib", listOf("3.0", "1.0", "2.0", "1.5"))
        val cleaner = cleanerForTesting()
        val old = cleaner.findOldVersions(art)
        assertEquals(3, old.size)
        assertFalse(old.any { it.version == "3.0" }, "3.0 should be kept as latest")
    }

    @Test
    fun testFindOldVersionsKeepsLatestSnapshot() {
        // If only version is a SNAPSHOT, it should be kept
        val art = artifact("com.example", "lib", listOf("1.0-SNAPSHOT", "2.0-SNAPSHOT"))
        val cleaner = cleanerForTesting()
        val old = cleaner.findOldVersions(art)
        assertEquals(1, old.size)
        assertEquals("1.0-SNAPSHOT", old[0].version)
    }

    // --- findOldSnapshots tests ---

    @Test
    fun testFindOldSnapshotsNoSnapshots() {
        val art = artifact("com.example", "lib", listOf("1.0", "2.0"))
        val cleaner = cleanerForTesting()
        assertTrue(cleaner.findOldSnapshots(art).isEmpty())
    }

    @Test
    fun testFindOldSnapshotsEmptyArtifact() {
        val art = Artifact("com.example", "lib", emptyList())
        val cleaner = cleanerForTesting()
        assertTrue(cleaner.findOldSnapshots(art).isEmpty())
    }

    @Test
    fun testFindOldSnapshotsSingleSnapshotIsLatest() {
        // Only one version and it's a SNAPSHOT — should NOT be returned (it's the latest)
        val art = artifact("com.example", "lib", listOf("1.0-SNAPSHOT"))
        val cleaner = cleanerForTesting()
        assertTrue(cleaner.findOldSnapshots(art).isEmpty())
    }

    @Test
    fun testFindOldSnapshotsDoesNotDeleteLatest() {
        // Latest is 2.0 (release), so 1.0-SNAPSHOT should be returned
        val art = artifact("com.example", "lib", listOf("1.0-SNAPSHOT", "2.0"))
        val cleaner = cleanerForTesting()
        val old = cleaner.findOldSnapshots(art)
        assertEquals(1, old.size)
        assertEquals("1.0-SNAPSHOT", old[0].version)
    }

    @Test
    fun testFindOldSnapshotsOnlySnapshots() {
        val art = artifact("com.example", "lib", listOf("1.0-SNAPSHOT", "2.0-SNAPSHOT", "3.0-SNAPSHOT"))
        val cleaner = cleanerForTesting()
        val old = cleaner.findOldSnapshots(art)
        // 3.0-SNAPSHOT is the latest, so only 1.0-SNAPSHOT and 2.0-SNAPSHOT should be returned
        assertEquals(2, old.size)
        val names = old.map { it.version }.toSet()
        assertTrue(names.contains("1.0-SNAPSHOT"))
        assertTrue(names.contains("2.0-SNAPSHOT"))
        assertFalse(names.contains("3.0-SNAPSHOT"))
    }

    @Test
    fun testFindOldSnapshotsMixed() {
        val art = artifact("com.example", "lib", listOf("1.0", "1.1-SNAPSHOT", "2.0-SNAPSHOT", "2.0"))
        val cleaner = cleanerForTesting()
        val old = cleaner.findOldSnapshots(art)
        val names = old.map { it.version }.toSet()
        // Latest version per comparator: "2.0-SNAPSHOT" sorts after "2.0" (more segments),
        // so 2.0-SNAPSHOT is the latest. 1.1-SNAPSHOT is a snapshot that's not latest.
        assertTrue(names.contains("1.1-SNAPSHOT"))
    }

    // --- Path validation tests ---

    @Test
    fun testPathValidationAcceptsAllowedRoot() = runBlocking {
        val cleaner = cleanerForTesting()
        val file = tempDir.resolve("safe-file.txt").createFile()
        // Should not throw
        cleaner.deletePaths(listOf(file), useTrash = false)
        assertFalse(file.exists())
    }

    @Test
    fun testPathValidationRejectsOutsidePath() {
        val cleaner = cleanerForTesting()
        val outsidePath = Paths.get(System.getProperty("user.home"), "Desktop", "important.txt")
        assertThrows(SecurityException::class.java) {
            runBlocking { cleaner.deletePaths(listOf(outsidePath), useTrash = false) }
        }
    }

    @Test
    fun testPathValidationRejectsTraversalAttack() {
        val cleaner = cleanerForTesting()
        val traversalPath = tempDir.resolve("../../etc/passwd")
        assertThrows(SecurityException::class.java) {
            runBlocking { cleaner.deletePaths(listOf(traversalPath), useTrash = false) }
        }
    }

    // --- deleteDirectory tests ---

    @Test
    fun testDeleteDirectoryRemovesAllContents() = runBlocking {
        val cleaner = cleanerForTesting()
        val dir = tempDir.resolve("artifact/1.0").createDirectories()
        dir.resolve("file1.txt").writeText("hello")
        dir.resolve("file2.txt").writeText("world")
        dir.resolve("sub").createDirectories().resolve("nested.txt").writeText("nested")

        cleaner.deletePaths(listOf(dir), useTrash = false)

        assertFalse(dir.exists())
    }

    @Test
    fun testDeleteNonExistentPathReturnsZero() = runBlocking {
        val cleaner = cleanerForTesting()
        val nonExistent = tempDir.resolve("does-not-exist")
        val freed = cleaner.deletePaths(listOf(nonExistent), useTrash = false)
        assertEquals(0L, freed)
    }

    // --- Progress callback test ---

    @Test
    fun testDeletePathsProgressCallback() = runBlocking {
        val cleaner = cleanerForTesting()
        val file1 = tempDir.resolve("f1.txt").apply { createFile(); writeText("a") }
        val file2 = tempDir.resolve("f2.txt").apply { createFile(); writeText("b") }
        val file3 = tempDir.resolve("f3.txt").apply { createFile(); writeText("c") }

        val progressUpdates = mutableListOf<Triple<Int, Int, Path>>()
        cleaner.deletePaths(listOf(file1, file2, file3), useTrash = false) { current, total, path ->
            synchronized(progressUpdates) {
                progressUpdates.add(Triple(current, total, path))
            }
        }

        assertEquals(3, progressUpdates.size)
        assertTrue(progressUpdates.all { it.second == 3 }, "Total should always be 3")
        val currentValues = progressUpdates.map { it.first }.toSet()
        assertEquals(setOf(1, 2, 3), currentValues)
    }

    // --- Trash provider test ---

    @Test
    fun testTrashFailureAbortsWithoutPermanentDeletion() = runBlocking {
        val failingTrash = object : TrashProvider {
            override fun isSupported() = true
            override fun moveToTrash(path: Path) {
                throw RuntimeException("Trash broken")
            }
        }
        val cleaner = cleanerForTesting(failingTrash)
        val file = tempDir.resolve("important.txt").createFile()

        assertThrows(Exception::class.java) {
            runBlocking { cleaner.deletePaths(listOf(file), useTrash = true) }
        }
        assertTrue(file.exists(), "File should survive when trash fails")
    }
}
