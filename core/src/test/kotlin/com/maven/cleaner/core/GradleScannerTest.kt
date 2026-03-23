package com.maven.cleaner.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class GradleScannerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun scanner(): GradleScanner = GradleScanner(tempDir)

    // --- scanAll ---

    @Test
    fun testScanAllWithNoGradleDir() {
        val scanner = GradleScanner(tempDir.resolve("nonexistent"))
        assertTrue(scanner.scanAll().isEmpty())
    }

    @Test
    fun testScanAllCombinesResults() {
        // Create a daemon log and a cache entry
        val daemonDir = tempDir.resolve("daemon/8.5").createDirectories()
        val log = daemonDir.resolve("daemon-123.out.log").createFile()
        log.writeText("log content")
        Files.setLastModifiedTime(log, FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)))

        val cachesDir = tempDir.resolve("caches/8.5").createDirectories()
        cachesDir.resolve("some-file.bin").createFile().writeText("data")

        val results = scanner().scanAll()
        assertTrue(results.isNotEmpty())
        val categories = results.map { it.category }.toSet()
        assertTrue(categories.contains("Daemon Log"))
        assertTrue(categories.contains("Version Cache"))
    }

    // --- scanDaemonLogs ---

    @Test
    fun testScanDaemonLogsNoDaemonDir() {
        assertTrue(scanner().scanDaemonLogs().isEmpty())
    }

    @Test
    fun testScanDaemonLogsFindsOldLogs() {
        val daemonDir = tempDir.resolve("daemon/8.5").createDirectories()
        val oldLog = daemonDir.resolve("daemon-1.out.log").createFile()
        oldLog.writeText("old log content")
        Files.setLastModifiedTime(oldLog, FileTime.from(Instant.now().minus(14, ChronoUnit.DAYS)))

        val results = scanner().scanDaemonLogs()
        assertEquals(1, results.size)
        assertEquals("Daemon Log", results[0].category)
        assertTrue(results[0].description.contains("8.5"))
        assertTrue(results[0].size > 0)
    }

    @Test
    fun testScanDaemonLogsSkipsRecentLogs() {
        val daemonDir = tempDir.resolve("daemon/8.5").createDirectories()
        val recentLog = daemonDir.resolve("daemon-1.out.log").createFile()
        recentLog.writeText("recent log")
        // Default modification time is "now", which is within 7 days

        val results = scanner().scanDaemonLogs()
        assertTrue(results.isEmpty())
    }

    @Test
    fun testScanDaemonLogsSkipsNonLogFiles() {
        val daemonDir = tempDir.resolve("daemon/8.5").createDirectories()
        val notALog = daemonDir.resolve("daemon-1.txt").createFile()
        notALog.writeText("not a log")
        Files.setLastModifiedTime(notALog, FileTime.from(Instant.now().minus(14, ChronoUnit.DAYS)))

        assertTrue(scanner().scanDaemonLogs().isEmpty())
    }

    // --- scanCaches ---

    @Test
    fun testScanCachesNoCachesDir() {
        assertTrue(scanner().scanCaches().isEmpty())
    }

    @Test
    fun testScanCachesDetectsVersionCache() {
        val cacheDir = tempDir.resolve("caches/8.5").createDirectories()
        cacheDir.resolve("data.bin").createFile().writeText("cache data")

        val results = scanner().scanCaches()
        assertEquals(1, results.size)
        assertEquals("Version Cache", results[0].category)
        assertTrue(results[0].description.contains("8.5"))
    }

    @Test
    fun testScanCachesDetectsTransformCache() {
        val transformDir = tempDir.resolve("caches/transforms-4").createDirectories()
        transformDir.resolve("data.bin").createFile().writeText("transform data")

        val results = scanner().scanCaches()
        assertEquals(1, results.size)
        assertEquals("Transform Cache", results[0].category)
    }

    @Test
    fun testScanCachesDetectsJournal() {
        val journalDir = tempDir.resolve("caches/journal-1").createDirectories()
        journalDir.resolve("data.bin").createFile().writeText("journal")

        val results = scanner().scanCaches()
        assertEquals(1, results.size)
        assertEquals("Journal", results[0].category)
    }

    @Test
    fun testScanCachesDetectsBuildCache() {
        val buildCacheDir = tempDir.resolve("caches/build-cache-1").createDirectories()
        buildCacheDir.resolve("data.bin").createFile().writeText("build cache")

        val results = scanner().scanCaches()
        assertEquals(1, results.size)
        assertEquals("Build Cache", results[0].category)
    }

    @Test
    fun testScanCachesDetectsModulesCache() {
        val modulesDir = tempDir.resolve("caches/modules-2").createDirectories()
        modulesDir.resolve("data.bin").createFile().writeText("modules")

        val results = scanner().scanCaches()
        assertEquals(1, results.size)
        assertEquals("Modules Cache", results[0].category)
    }

    @Test
    fun testScanCachesDetectsJarsCache() {
        val jarsDir = tempDir.resolve("caches/jars-9").createDirectories()
        jarsDir.resolve("data.bin").createFile().writeText("jars")

        val results = scanner().scanCaches()
        assertEquals(1, results.size)
        assertEquals("Jars Cache", results[0].category)
    }

    @Test
    fun testScanCachesDetectsOtherCache() {
        val otherDir = tempDir.resolve("caches/something-unknown").createDirectories()
        otherDir.resolve("data.bin").createFile().writeText("other")

        val results = scanner().scanCaches()
        assertEquals(1, results.size)
        assertEquals("Other Cache", results[0].category)
    }

    @Test
    fun testScanCachesSkipsEmptyDirectories() {
        tempDir.resolve("caches/8.5").createDirectories()
        assertTrue(scanner().scanCaches().isEmpty())
    }

    // --- scanDistributions ---

    @Test
    fun testScanDistributionsNoDistsDir() {
        assertTrue(scanner().scanDistributions().isEmpty())
    }

    @Test
    fun testScanDistributionsFindsDistributions() {
        val distDir = tempDir.resolve("wrapper/dists/gradle-8.5-bin").createDirectories()
        distDir.resolve("gradle-8.5-bin.zip").createFile().writeText("x".repeat(1000))

        val results = scanner().scanDistributions()
        assertEquals(1, results.size)
        assertEquals("Distribution", results[0].category)
        assertTrue(results[0].size >= 1000)
    }

    // --- scanBuildScans ---

    @Test
    fun testScanBuildScansNoBuildScanDir() {
        assertTrue(scanner().scanBuildScans().isEmpty())
    }

    @Test
    fun testScanBuildScansFindsData() {
        val scanDir = tempDir.resolve(".build-scan-data").createDirectories()
        scanDir.resolve("scan.dat").createFile().writeText("scan data")

        val results = scanner().scanBuildScans()
        assertEquals(1, results.size)
        assertEquals("Build Scans", results[0].category)
    }

    // --- scanNativeFiles ---

    @Test
    fun testScanNativeFilesNoNativeDir() {
        assertTrue(scanner().scanNativeFiles().isEmpty())
    }

    @Test
    fun testScanNativeFilesFindsData() {
        val nativeDir = tempDir.resolve("native").createDirectories()
        nativeDir.resolve("native-platform.so").createFile().writeText("native binary")

        val results = scanner().scanNativeFiles()
        assertEquals(1, results.size)
        assertEquals("Native", results[0].category)
    }

    // --- calculateTotalGradleSize ---

    @Test
    fun testCalculateTotalGradleSize() {
        tempDir.resolve("caches").createDirectories().resolve("data.bin").createFile().writeText("x".repeat(500))
        val size = scanner().calculateTotalGradleSize()
        assertTrue(size >= 500)
    }

    @Test
    fun testCalculateTotalGradleSizeNonExistent() {
        val scanner = GradleScanner(tempDir.resolve("nonexistent"))
        assertEquals(0L, scanner.calculateTotalGradleSize())
    }
}
