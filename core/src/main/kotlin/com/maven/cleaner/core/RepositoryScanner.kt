package com.maven.cleaner.core

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.*
import kotlin.concurrent.withLock
import kotlin.io.path.*

class RepositoryScanner(private val repositoryPath: Path = defaultRepositoryPath()) {

    private val logger = LoggerFactory.getLogger(RepositoryScanner::class.java)
    private val artifactMapLock = ReentrantLock()

    companion object {
        fun defaultRepositoryPath(): Path {
            val userHome = System.getProperty("user.home")
            return Paths.get(userHome, ".m2", "repository")
        }
    }

    suspend fun scan(): List<Artifact> {
        if (!repositoryPath.exists() || !repositoryPath.isDirectory()) {
            logger.warn("Repository path does not exist or is not a directory: {}", repositoryPath)
            return emptyList()
        }

        val artifactMap = mutableMapOf<Pair<String, String>, MutableList<ArtifactVersion>>()

        // Use a coroutine scope with multiple threads for fast I/O
        withContext(Dispatchers.IO) {
            scanDirectoryRecursive(repositoryPath, artifactMap)
        }

        return artifactMap.map { (key, versions) ->
            Artifact(key.first, key.second, versions)
        }
    }

    private suspend fun scanDirectoryRecursive(currentPath: Path, artifactMap: MutableMap<Pair<String, String>, MutableList<ArtifactVersion>>): Unit = coroutineScope {
        // Reject symlinks that could escape the repository
        if (currentPath.isSymbolicLink()) {
            val target = currentPath.toRealPath()
            if (!target.startsWith(repositoryPath.toAbsolutePath().normalize())) {
                logger.warn("Skipping symlink that points outside repository: {} -> {}", currentPath, target)
                return@coroutineScope
            }
        }

        val entries = try { currentPath.listDirectoryEntries() } catch (e: Exception) {
            logger.error("Failed to list entries in {}: {}", currentPath, e.message)
            emptyList()
        }
        val hasPom = entries.any { it.name.endsWith(".pom") }

        if (hasPom) {
            val version = currentPath.name
            val artifactPath = currentPath.parent
            val artifactId = artifactPath.name
            val relativePath = repositoryPath.relativize(artifactPath.parent).toString()
            val groupId = relativePath.replace(java.io.File.separatorChar, '.')

            // Calculate size from already-listed entries to avoid a separate Files.walk()
            val size = entries.sumOf { entry ->
                if (entry.isRegularFile()) {
                    try { Files.size(entry) } catch (_: Exception) { 0L }
                } else if (entry.isDirectory()) {
                    calculateDirectorySize(entry)
                } else {
                    0L
                }
            }
            val artifactVersion = ArtifactVersion(version, currentPath, size)

            artifactMapLock.withLock {
                artifactMap.getOrPut(groupId to artifactId) { mutableListOf() }.add(artifactVersion)
            }
        } else {
            entries.filter { it.isDirectory() }.forEach { entry ->
                launch {
                    scanDirectoryRecursive(entry, artifactMap)
                }
            }
        }
    }

    fun scanGradleLogs(): List<Path> {
        val userHome = System.getProperty("user.home")
        val gradlePath = Paths.get(userHome, ".gradle", "daemon")
        if (!gradlePath.exists() || !gradlePath.isDirectory()) return emptyList()

        val logs = mutableListOf<Path>()
        // Gradle daemons are in subdirectories by version
        gradlePath.listDirectoryEntries().filter { it.isDirectory() }.forEach { versionDir ->
            versionDir.listDirectoryEntries().filter { it.name.endsWith(".out.log") }.forEach { log ->
                // Optionally filter by age, e.g., older than 7 days
                val lastModified = try { Files.getLastModifiedTime(log).toInstant() } catch (e: Exception) { Instant.MIN }
                if (lastModified.isBefore(Instant.now().minus(7, ChronoUnit.DAYS))) {
                    logs.add(log)
                }
            }
        }
        return logs
    }

    fun calculateTotalRepositorySize(): Long {
        if (!repositoryPath.exists() || !repositoryPath.isDirectory()) return 0L
        return calculateDirectorySize(repositoryPath)
    }

    fun getRepositoryPath(): Path = repositoryPath

    private fun calculateDirectorySize(path: Path): Long {
        return try {
            Files.walk(path).use { stream ->
                stream.mapToLong {
                    if (Files.isRegularFile(it)) {
                        try { Files.size(it) } catch (e: Exception) { 0L }
                    } else {
                        0L
                    }
                }.sum()
            }
        } catch (e: Exception) {
            0L
        }
    }
}
