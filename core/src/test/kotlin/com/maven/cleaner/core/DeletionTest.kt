package com.maven.cleaner.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText

class DeletionTest {

    @TempDir
    lateinit var tempDir: Path

    private fun cleanerForTesting(trashProvider: TrashProvider = NoOpTrashProvider()): ArtifactCleaner {
        return ArtifactCleaner(trashProvider, allowedRoots = listOf(tempDir))
    }

    @Test
    fun testDeletePaths() = runBlocking {
        val cleaner = cleanerForTesting()
        val file1 = tempDir.resolve("file1.txt").createFile()
        val dir1 = tempDir.resolve("dir1").createDirectories()
        val file2 = dir1.resolve("file2.txt").createFile()

        assertTrue(file1.exists())
        assertTrue(dir1.exists())
        assertTrue(file2.exists())

        val freed = cleaner.deletePaths(listOf(file1, dir1), useTrash = false)

        assertFalse(file1.exists())
        assertFalse(dir1.exists())
        assertFalse(file2.exists())
        assertTrue(freed >= 0)
    }

    @Test
    fun testMetadataRefreshAfterDeletion() = runBlocking {
        val cleaner = cleanerForTesting()
        val artifactDir = tempDir.resolve("com/example/my-artifact").createDirectories()
        val version1 = artifactDir.resolve("1.0.0").createDirectories()
        version1.resolve("my-artifact-1.0.0.pom").createFile()
        val version2 = artifactDir.resolve("1.1.0").createDirectories()
        version2.resolve("my-artifact-1.1.0.pom").createFile()

        val metadataFile = artifactDir.resolve("maven-metadata-local.xml")
        metadataFile.writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>my-artifact</artifactId>
              <versioning>
                <latest>1.1.0</latest>
                <release>1.1.0</release>
                <versions>
                  <version>1.0.0</version>
                  <version>1.1.0</version>
                </versions>
                <lastUpdated>20230101000000</lastUpdated>
              </versioning>
            </metadata>
        """.trimIndent())

        cleaner.deletePaths(listOf(version2), useTrash = false)

        assertFalse(version2.exists())
        assertTrue(metadataFile.exists())

        val content = metadataFile.toFile().readText()
        assertTrue(content.contains("<latest>1.0.0</latest>"), "Metadata should be updated to show 1.0.0 as latest")
        assertFalse(content.contains("<version>1.1.0</version>"), "Metadata should not contain 1.1.0")
    }

    @Test
    fun testTrashLogic() = runBlocking {
        val trashResults = mutableListOf<Path>()
        val mockTrashProvider = object : TrashProvider {
            override fun isSupported() = true
            override fun moveToTrash(path: Path) {
                trashResults.add(path)
            }
        }
        val cleaner = cleanerForTesting(mockTrashProvider)
        val file1 = tempDir.resolve("trash_me.txt").createFile()

        cleaner.deletePaths(listOf(file1), useTrash = true)

        assertEquals(1, trashResults.size)
        assertEquals(file1, trashResults[0])
    }

    @Test
    fun testTrashFailureAbortsDeletion() = runBlocking {
        val mockTrashProvider = object : TrashProvider {
            override fun isSupported() = true
            override fun moveToTrash(path: Path) {
                throw RuntimeException("Trash is full or something")
            }
        }
        val cleaner = cleanerForTesting(mockTrashProvider)
        val file1 = tempDir.resolve("protect_me.txt").createFile()

        assertThrows(Exception::class.java) {
            runBlocking {
                cleaner.deletePaths(listOf(file1), useTrash = true)
            }
        }

        assertTrue(file1.exists())
    }

    @Test
    fun testPathValidationRejectsOutsidePaths() {
        val cleaner = cleanerForTesting()
        val outsidePath = Path.of(System.getProperty("user.home"), "Desktop", "important_file.txt")

        assertThrows(SecurityException::class.java) {
            runBlocking {
                cleaner.deletePaths(listOf(outsidePath), useTrash = false)
            }
        }
    }
}
