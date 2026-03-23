package com.maven.cleaner.cli

import com.maven.cleaner.core.ArtifactCleaner
import com.maven.cleaner.core.ArtifactVersion
import com.maven.cleaner.core.RepositoryMigrator
import com.maven.cleaner.core.RepositoryScanner
import com.maven.cleaner.core.UpstreamChecker
import com.maven.cleaner.core.UpstreamStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.Paths
import com.maven.cleaner.core.formatSize
import kotlin.io.path.*

fun main(args: Array<String>) {
    // Ensure clean shutdown on Ctrl+C
    Runtime.getRuntime().addShutdownHook(Thread {
        System.err.println("\nInterrupted. Shutting down...")
    })

    if (args.contains("--migrate-split")) {
        runMigrateSplit(args)
        return
    }

    runCleanup(args)
}

private fun runMigrateSplit(args: Array<String>) {
    println("Maven Repository Split Migration")
    println("================================")
    println()
    println("This will reorganize your local Maven repository into the split layout:")
    println("  ~/.m2/repository/cached/    <- downloaded dependencies (safe to delete)")
    println("  ~/.m2/repository/installed/ <- your locally built artifacts (protected)")
    println()

    val repoPathArg = args.indexOf("--repo").let { idx ->
        if (idx >= 0 && idx + 1 < args.size) Paths.get(args[idx + 1]) else null
    }
    val repoPath = repoPathArg ?: RepositoryScanner.defaultRepositoryPath()
    val dryRun = args.contains("--dry-run")

    UpstreamChecker().use { checker ->
        val migrator = RepositoryMigrator(checker, repoPath)

        when (migrator.detectSplitStatus()) {
            RepositoryMigrator.SplitStatus.FULLY_SPLIT -> {
                println("Repository is already fully split. No migration needed.")
                return
            }
            RepositoryMigrator.SplitStatus.PARTIALLY_SPLIT -> {
                println("Repository is partially split. Some artifacts are still at the top level.")
                print("Continue migration for remaining artifacts? (y/N): ")
                if (readLine()?.lowercase() != "y") {
                    println("Aborted.")
                    return
                }
            }
            RepositoryMigrator.SplitStatus.NOT_SPLIT -> {
                // proceed normally
            }
        }

        println("Scanning repository at $repoPath...")
        val scanner = RepositoryScanner(repoPath)
        val artifactCount = runBlocking { scanner.scan() }.size
        println("Found $artifactCount artifacts. Each will be checked against Maven Central.")

        if (!dryRun) {
            println()
            print("Proceed with migration? (y/N): ")
            if (readLine()?.lowercase() != "y") {
                println("Aborted.")
                return
            }
        }

        println()
        val result = runBlocking {
            migrator.migrate(dryRun = dryRun) { current, total, artifact, target ->
                val prefix = if (dryRun) "[DRY RUN] " else ""
                println("${prefix}[$current/$total] $artifact -> $target")
            }
        }

        println()
        if (dryRun) {
            println("=== Dry Run Results ===")
        } else {
            println("=== Migration Complete ===")
        }
        println("  Moved to cached/:    ${result.movedToCached}")
        println("  Moved to installed/: ${result.movedToInstalled}")
        println("  Skipped (unknown):   ${result.skipped}")
        println("  Errors:              ${result.errors}")

        if (!dryRun && result.movedToCached > 0) {
            println()
            println("To use the split layout going forward, add to your .mvn/maven.config or settings.xml:")
            println("  -Daether.enhancedLocalRepository.split=true")
            println("  -Daether.enhancedLocalRepository.splitRemoteRepository=true")
        }
    }
}

private fun runCleanup(args: Array<String>) {
    println("Maven Repository Cleaner (Kotlin, JDK 21)")
    println("----------------------------------------")

    val repoPathArg = args.indexOf("--repo").let { idx ->
        if (idx >= 0 && idx + 1 < args.size) Paths.get(args[idx + 1]) else null
    }
    val scanner = if (repoPathArg != null) RepositoryScanner(repoPathArg) else RepositoryScanner()
    println("Scanning repository: ${scanner.getRepositoryPath()}...")
    val artifacts = runBlocking { scanner.scan() }
    println("Found ${artifacts.size} unique artifacts.")

    val allowedRoots = mutableListOf(RepositoryScanner.defaultRepositoryPath(), Paths.get(System.getProperty("user.home"), ".gradle"))
    if (repoPathArg != null) {
        allowedRoots.add(repoPathArg)
    }
    val cleaner = ArtifactCleaner(allowedRoots = allowedRoots)
    val dryRun = args.contains("--dry-run")
    val skipUpstream = args.contains("--skip-upstream")

    val candidates = artifacts.flatMap { art ->
        val old = cleaner.findOldVersions(art)
        val snapshots = cleaner.findOldSnapshots(art)
        (old + snapshots)
    }.distinctBy { it.path }

    val (removableVersions, protectedVersions) = if (skipUpstream) {
        candidates to emptyList<ArtifactVersion>()
    } else {
        println("Checking upstream status for potential removals (this may take a while)...")
        UpstreamChecker().use { upstreamChecker ->
            runBlocking {
                val semaphore = Semaphore(10)
                val results = candidates.map { v ->
                    async {
                        val art = artifacts.first { a -> a.versions.any { it.path == v.path } }
                        semaphore.withPermit {
                            v to upstreamChecker.checkMavenCentral(art.groupId, art.artifactId, v.version)
                        }
                    }
                }.awaitAll()

                val removable = results.filter { it.second == UpstreamStatus.AVAILABLE }.map { it.first }
                val protected_ = results.filter { it.second == UpstreamStatus.LOCAL_ONLY }.map { it.first }
                removable to protected_
            }
        }
    }

    if (removableVersions.isEmpty() && protectedVersions.isEmpty()) {
        println("No removable versions found.")
        return
    }

    if (protectedVersions.isNotEmpty()) {
        println("Protected ${protectedVersions.size} local-only versions from deletion.")
    }

    val totalSize = removableVersions.sumOf { it.size }
    val gradleLogs = scanner.scanGradleLogs()
    val gradleLogsSize = gradleLogs.sumOf { Files.size(it) }

    val totalSizeToFree = totalSize + gradleLogsSize
    println("Total space to free: ${formatSize(totalSizeToFree)}")

    if (dryRun) {
        println("Dry run mode: No files will be deleted.")
        removableVersions.forEach { println("Would delete: ${it.path} (${formatSize(it.size)})") }
        gradleLogs.forEach { println("Would delete log: $it (${formatSize(Files.size(it))})") }
        return
    }

    if (removableVersions.isEmpty() && gradleLogs.isEmpty()) {
        println("Nothing to delete.")
        return
    }

    print("Do you want to delete these files? (y/N): ")
    val response = readLine()
    if (response?.lowercase() == "y") {
        println("Deleting...")
        runBlocking {
            var freed = cleaner.deleteVersions(removableVersions)
            freed += cleaner.deletePaths(gradleLogs)
            println("Freed ${formatSize(freed)} of space.")
        }
    } else {
        println("Cleanup cancelled.")
    }
}
