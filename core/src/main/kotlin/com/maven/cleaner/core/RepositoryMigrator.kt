package com.maven.cleaner.core

import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

data class MigrationResult(
    val movedToCached: Int,
    val movedToInstalled: Int,
    val skipped: Int,
    val errors: Int
)

class RepositoryMigrator(
    private val upstreamChecker: UpstreamChecker,
    private val repositoryPath: Path = RepositoryScanner.defaultRepositoryPath()
) {

    private val logger = LoggerFactory.getLogger(RepositoryMigrator::class.java)
    private val cachedDir = repositoryPath.resolve("cached")
    private val installedDir = repositoryPath.resolve("installed")

    enum class SplitStatus {
        NOT_SPLIT,
        PARTIALLY_SPLIT,
        FULLY_SPLIT
    }

    fun detectSplitStatus(): SplitStatus {
        val hasCached = cachedDir.exists() && cachedDir.isDirectory()
        val hasInstalled = installedDir.exists() && installedDir.isDirectory()

        if (!hasCached && !hasInstalled) return SplitStatus.NOT_SPLIT

        // Check if there are still artifacts at the top level (not inside cached/ or installed/)
        val hasTopLevelArtifacts = repositoryPath.listDirectoryEntries()
            .filter { it.isDirectory() }
            .any { dir ->
                val name = dir.fileName.toString()
                name != "cached" && name != "installed" && containsArtifacts(dir)
            }

        return if (hasTopLevelArtifacts) SplitStatus.PARTIALLY_SPLIT else SplitStatus.FULLY_SPLIT
    }

    private fun containsArtifacts(dir: Path): Boolean {
        // Quick check: recursively look for .pom files (max 3 levels deep to stay fast)
        return try {
            Files.walk(dir, 6).use { stream ->
                stream.anyMatch { it.fileName.toString().endsWith(".pom") }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isAlreadySplit(): Boolean {
        return detectSplitStatus() != SplitStatus.NOT_SPLIT
    }

    suspend fun migrate(
        dryRun: Boolean = false,
        onProgress: ((current: Int, total: Int, artifact: String, target: String) -> Unit)? = null
    ): MigrationResult = coroutineScope {
        val scanner = RepositoryScanner(repositoryPath)
        val artifacts = scanner.scan()

        if (artifacts.isEmpty()) {
            return@coroutineScope MigrationResult(0, 0, 0, 0)
        }

        // Phase 1: Check all unique groupId:artifactId:version coordinates against Maven Central
        // Use one version per artifact to determine if it's upstream (optimization: if any version exists upstream,
        // the artifact is a dependency, not a local project)
        val semaphore = Semaphore(10)
        val checkResults = artifacts.map { artifact ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    ensureActive()
                    // Check the latest version — if it exists upstream, it's a dependency
                    val sorted = artifact.versions.sortedWith(VersionComparator())
                    val latestVersion = sorted.last()
                    val status = upstreamChecker.checkMavenCentral(
                        artifact.groupId, artifact.artifactId, latestVersion.version
                    )
                    artifact to status
                }
            }
        }.awaitAll()

        // Phase 2: Move artifacts to the correct subdirectory
        val total = checkResults.size
        var movedToCached = 0
        var movedToInstalled = 0
        var skipped = 0
        var errors = 0

        for ((index, pair) in checkResults.withIndex()) {
            ensureActive()
            val (artifact, status) = pair

            val targetSubdir = when (status) {
                UpstreamStatus.AVAILABLE -> "cached"
                UpstreamStatus.LOCAL_ONLY -> "installed"
                UpstreamStatus.UNKNOWN -> {
                    // If we can't determine, skip to be safe
                    logger.warn("Skipping {}: upstream status unknown", "${artifact.groupId}:${artifact.artifactId}")
                    skipped++
                    onProgress?.invoke(index + 1, total, "${artifact.groupId}:${artifact.artifactId}", "skipped")
                    continue
                }
            }

            val targetRoot = if (targetSubdir == "cached") cachedDir else installedDir
            val coordLabel = "${artifact.groupId}:${artifact.artifactId}"

            onProgress?.invoke(index + 1, total, coordLabel, targetSubdir)

            if (dryRun) {
                if (targetSubdir == "cached") movedToCached++ else movedToInstalled++
                continue
            }

            try {
                moveArtifact(artifact, targetRoot)
                if (targetSubdir == "cached") movedToCached++ else movedToInstalled++
            } catch (e: Exception) {
                logger.error("Failed to move {}: {}", coordLabel, e.message)
                errors++
            }
        }

        MigrationResult(movedToCached, movedToInstalled, skipped, errors)
    }

    private fun moveArtifact(artifact: Artifact, targetRoot: Path) {
        val groupPath = artifact.groupId.replace('.', '/')
        val targetArtifactDir = targetRoot.resolve(groupPath).resolve(artifact.artifactId)

        for (version in artifact.versions) {
            val sourceVersionDir = version.path
            if (!sourceVersionDir.exists() || !sourceVersionDir.isDirectory()) continue

            val targetVersionDir = targetArtifactDir.resolve(version.version)
            if (targetVersionDir.exists()) {
                logger.info("Target already exists, skipping: {}", targetVersionDir)
                continue
            }

            targetVersionDir.parent.createDirectories()
            Files.move(sourceVersionDir, targetVersionDir, StandardCopyOption.ATOMIC_MOVE)
            logger.debug("Moved {} -> {}", sourceVersionDir, targetVersionDir)
        }

        // Move metadata files from the source artifact directory
        val sourceArtifactDir = artifact.versions.firstOrNull()?.path?.parent ?: return
        if (!sourceArtifactDir.exists()) return

        for (metadataName in listOf("maven-metadata-local.xml", "maven-metadata.xml")) {
            val sourceMetadata = sourceArtifactDir.resolve(metadataName)
            if (sourceMetadata.exists()) {
                val targetMetadata = targetArtifactDir.resolve(metadataName)
                targetArtifactDir.createDirectories()
                Files.move(sourceMetadata, targetMetadata, StandardCopyOption.REPLACE_EXISTING)
            }
            // Also move checksum files
            for (ext in listOf(".md5", ".sha1", ".sha256", ".sha512")) {
                val checksumFile = sourceArtifactDir.resolve("$metadataName$ext")
                if (checksumFile.exists()) {
                    val targetChecksum = targetArtifactDir.resolve("$metadataName$ext")
                    Files.move(checksumFile, targetChecksum, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        // Clean up empty source directories
        cleanEmptyParents(sourceArtifactDir, repositoryPath)
    }

    private fun cleanEmptyParents(dir: Path, stopAt: Path) {
        var current = dir
        while (current != stopAt && current.startsWith(stopAt)) {
            if (!current.exists()) {
                current = current.parent ?: break
                continue
            }
            val entries = current.listDirectoryEntries()
            if (entries.isEmpty()) {
                try {
                    Files.delete(current)
                } catch (e: Exception) {
                    break
                }
                current = current.parent ?: break
            } else {
                break
            }
        }
    }
}
