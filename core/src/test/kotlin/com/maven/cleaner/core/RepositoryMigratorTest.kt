package com.maven.cleaner.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RepositoryMigratorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createArtifact(repoRoot: Path, groupId: String, artifactId: String, versions: List<String>) {
        val groupPath = groupId.replace('.', '/')
        val artifactDir = repoRoot.resolve(groupPath).resolve(artifactId).createDirectories()

        for (version in versions) {
            val versionDir = artifactDir.resolve(version).createDirectories()
            versionDir.resolve("$artifactId-$version.pom").writeText("<project/>")
            versionDir.resolve("$artifactId-$version.jar").writeText("content-$version")
        }

        val versionEntries = versions.joinToString("\n") { "      <version>$it</version>" }
        artifactDir.resolve("maven-metadata-local.xml").writeText("""<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>$groupId</groupId>
  <artifactId>$artifactId</artifactId>
  <versioning>
    <latest>${versions.last()}</latest>
    <versions>
$versionEntries
    </versions>
  </versioning>
</metadata>""")
    }

    private fun mockUpstreamChecker(available: Set<String>): UpstreamChecker {
        return object : UpstreamChecker() {
            override suspend fun checkMavenCentral(groupId: String, artifactId: String, version: String): UpstreamStatus {
                val key = "$groupId:$artifactId"
                return if (available.contains(key)) UpstreamStatus.AVAILABLE else UpstreamStatus.LOCAL_ONLY
            }
        }
    }

    @Test
    fun testDryRunDoesNotMoveFiles() = runBlocking {
        createArtifact(tempDir, "com.example", "lib", listOf("1.0"))

        val checker = mockUpstreamChecker(setOf("com.example:lib"))
        val migrator = RepositoryMigrator(checker, tempDir)

        val result = migrator.migrate(dryRun = true)

        assertEquals(1, result.movedToCached)
        assertEquals(0, result.movedToInstalled)

        // Files should still be in original location
        assertTrue(tempDir.resolve("com/example/lib/1.0").exists())
        assertFalse(tempDir.resolve("cached").exists())
    }

    @Test
    fun testMigrateUpstreamToCached() = runBlocking {
        createArtifact(tempDir, "com.example", "lib", listOf("1.0", "2.0"))

        val checker = mockUpstreamChecker(setOf("com.example:lib"))
        val migrator = RepositoryMigrator(checker, tempDir)

        val result = migrator.migrate()

        assertEquals(1, result.movedToCached)
        assertEquals(0, result.movedToInstalled)
        assertEquals(0, result.errors)

        assertTrue(tempDir.resolve("cached/com/example/lib/1.0/lib-1.0.pom").exists())
        assertTrue(tempDir.resolve("cached/com/example/lib/2.0/lib-2.0.jar").exists())
        assertFalse(tempDir.resolve("com/example/lib/1.0").exists())
    }

    @Test
    fun testMigrateLocalOnlyToInstalled() = runBlocking {
        createArtifact(tempDir, "com.mycompany", "internal-lib", listOf("1.0-SNAPSHOT"))

        val checker = mockUpstreamChecker(emptySet())
        val migrator = RepositoryMigrator(checker, tempDir)

        val result = migrator.migrate()

        assertEquals(0, result.movedToCached)
        assertEquals(1, result.movedToInstalled)

        assertTrue(tempDir.resolve("installed/com/mycompany/internal-lib/1.0-SNAPSHOT/internal-lib-1.0-SNAPSHOT.pom").exists())
        assertFalse(tempDir.resolve("com/mycompany/internal-lib/1.0-SNAPSHOT").exists())
    }

    @Test
    fun testMixedArtifacts() = runBlocking {
        createArtifact(tempDir, "org.apache", "commons-lang3", listOf("3.12", "3.13"))
        createArtifact(tempDir, "com.mycompany", "my-app", listOf("1.0-SNAPSHOT"))
        createArtifact(tempDir, "com.google", "guava", listOf("31.0"))

        val checker = mockUpstreamChecker(setOf("org.apache:commons-lang3", "com.google:guava"))
        val migrator = RepositoryMigrator(checker, tempDir)

        val result = migrator.migrate()

        assertEquals(2, result.movedToCached)
        assertEquals(1, result.movedToInstalled)

        assertTrue(tempDir.resolve("cached/org/apache/commons-lang3/3.12").exists())
        assertTrue(tempDir.resolve("cached/com/google/guava/31.0").exists())
        assertTrue(tempDir.resolve("installed/com/mycompany/my-app/1.0-SNAPSHOT").exists())
    }

    @Test
    fun testMetadataIsMoved() = runBlocking {
        createArtifact(tempDir, "com.example", "lib", listOf("1.0"))

        val checker = mockUpstreamChecker(setOf("com.example:lib"))
        val migrator = RepositoryMigrator(checker, tempDir)
        migrator.migrate()

        val metadata = tempDir.resolve("cached/com/example/lib/maven-metadata-local.xml")
        assertTrue(metadata.exists())
        assertTrue(metadata.readText().contains("com.example"))
    }

    @Test
    fun testEmptySourceDirectoriesCleanedUp() = runBlocking {
        createArtifact(tempDir, "com.example", "lib", listOf("1.0"))

        val checker = mockUpstreamChecker(setOf("com.example:lib"))
        val migrator = RepositoryMigrator(checker, tempDir)
        migrator.migrate()

        // The original com/example/lib directory should be gone
        assertFalse(tempDir.resolve("com/example/lib").exists())
        // Parent directories should be cleaned up too
        assertFalse(tempDir.resolve("com/example").exists())
        assertFalse(tempDir.resolve("com").exists())
    }

    @Test
    fun testProgressCallback() = runBlocking {
        createArtifact(tempDir, "com.example", "lib-a", listOf("1.0"))
        createArtifact(tempDir, "com.example", "lib-b", listOf("1.0"))

        val checker = mockUpstreamChecker(setOf("com.example:lib-a"))
        val migrator = RepositoryMigrator(checker, tempDir)

        val updates = mutableListOf<String>()
        migrator.migrate { current, total, artifact, target ->
            updates.add("$current/$total $artifact -> $target")
        }

        assertEquals(2, updates.size)
    }

    @Test
    fun testIsAlreadySplit() {
        val migrator = RepositoryMigrator(UpstreamChecker(), tempDir)
        assertFalse(migrator.isAlreadySplit())

        tempDir.resolve("cached").createDirectories()
        assertTrue(migrator.isAlreadySplit())
    }
}
