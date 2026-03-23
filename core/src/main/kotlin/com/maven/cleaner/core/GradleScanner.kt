package com.maven.cleaner.core

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

data class GradleCleanable(
    val category: String,
    val description: String,
    val path: Path,
    val size: Long
)

class GradleScanner {

    private val logger = LoggerFactory.getLogger(GradleScanner::class.java)
    private val gradleHome: Path = Paths.get(System.getProperty("user.home"), ".gradle")

    fun getGradleHome(): Path = gradleHome

    fun scanAll(): List<GradleCleanable> {
        if (!gradleHome.exists() || !gradleHome.isDirectory()) return emptyList()
        return scanDaemonLogs() + scanCaches() + scanDistributions() + scanBuildScans() + scanNativeFiles()
    }

    fun scanDaemonLogs(): List<GradleCleanable> {
        val daemonDir = gradleHome.resolve("daemon")
        if (!daemonDir.exists() || !daemonDir.isDirectory()) return emptyList()

        val results = mutableListOf<GradleCleanable>()
        daemonDir.listDirectoryEntries().filter { it.isDirectory() }.forEach { versionDir ->
            versionDir.listDirectoryEntries().filter { it.name.endsWith(".out.log") }.forEach { log ->
                val lastModified = try {
                    Files.getLastModifiedTime(log).toInstant()
                } catch (e: Exception) {
                    Instant.MIN
                }
                if (lastModified.isBefore(Instant.now().minus(7, ChronoUnit.DAYS))) {
                    val size = try { Files.size(log) } catch (e: Exception) { 0L }
                    results.add(GradleCleanable("Daemon Log", "Gradle ${versionDir.name}", log, size))
                }
            }
        }
        return results
    }

    fun scanCaches(): List<GradleCleanable> {
        val cachesDir = gradleHome.resolve("caches")
        if (!cachesDir.exists() || !cachesDir.isDirectory()) return emptyList()

        val results = mutableListOf<GradleCleanable>()

        cachesDir.listDirectoryEntries().filter { it.isDirectory() }.forEach { entry ->
            val name = entry.name
            val size = calculateDirectorySize(entry)
            if (size == 0L) return@forEach

            when {
                name.matches(Regex("\\d+\\.\\d+.*")) -> {
                    results.add(GradleCleanable("Version Cache", "Gradle $name cache", entry, size))
                }
                name == "transforms-4" || name.startsWith("transforms-") -> {
                    results.add(GradleCleanable("Transform Cache", "Artifact transforms ($name)", entry, size))
                }
                name == "journal-1" -> {
                    results.add(GradleCleanable("Journal", "Cache journal", entry, size))
                }
                name == "build-cache-1" -> {
                    results.add(GradleCleanable("Build Cache", "Local build cache", entry, size))
                }
                name.startsWith("modules-") -> {
                    results.add(GradleCleanable("Modules Cache", "Dependency modules ($name)", entry, size))
                }
                name == "jars-9" || name.startsWith("jars-") -> {
                    results.add(GradleCleanable("Jars Cache", "Cached jars ($name)", entry, size))
                }
                else -> {
                    results.add(GradleCleanable("Other Cache", name, entry, size))
                }
            }
        }
        return results
    }

    fun scanDistributions(): List<GradleCleanable> {
        val distsDir = gradleHome.resolve("wrapper").resolve("dists")
        if (!distsDir.exists() || !distsDir.isDirectory()) return emptyList()

        val results = mutableListOf<GradleCleanable>()
        distsDir.listDirectoryEntries().filter { it.isDirectory() }.forEach { distDir ->
            val size = calculateDirectorySize(distDir)
            if (size > 0) {
                results.add(GradleCleanable("Distribution", "Gradle wrapper: ${distDir.name}", distDir, size))
            }
        }
        return results
    }

    fun scanBuildScans(): List<GradleCleanable> {
        val buildScanDir = gradleHome.resolve(".build-scan-data")
        if (!buildScanDir.exists() || !buildScanDir.isDirectory()) return emptyList()

        val size = calculateDirectorySize(buildScanDir)
        if (size == 0L) return emptyList()
        return listOf(GradleCleanable("Build Scans", "Build scan data", buildScanDir, size))
    }

    fun scanNativeFiles(): List<GradleCleanable> {
        val nativeDir = gradleHome.resolve("native")
        if (!nativeDir.exists() || !nativeDir.isDirectory()) return emptyList()

        val size = calculateDirectorySize(nativeDir)
        if (size == 0L) return emptyList()
        return listOf(GradleCleanable("Native", "Native platform files", nativeDir, size))
    }

    fun calculateTotalGradleSize(): Long {
        if (!gradleHome.exists() || !gradleHome.isDirectory()) return 0L
        return calculateDirectorySize(gradleHome)
    }

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
