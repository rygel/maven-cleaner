package com.maven.cleaner.cli

import com.maven.cleaner.core.ArtifactCleaner
import com.maven.cleaner.core.RepositoryScanner
import com.maven.cleaner.core.UpstreamChecker
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.math.ln
import kotlin.math.pow

fun main(args: Array<String>) {
    println("Maven Repository Cleaner (Kotlin, JDK 21)")
    println("----------------------------------------")

    val scanner = RepositoryScanner()
    println("Scanning repository: ${RepositoryScanner.defaultRepositoryPath()}...")
    val artifacts = scanner.scan()
    println("Found ${artifacts.size} unique artifacts.")

    val cleaner = ArtifactCleaner()
    val upstreamChecker = UpstreamChecker()
    val dryRun = args.contains("--dry-run")
    val skipUpstream = args.contains("--skip-upstream")

    val allOldVersions = artifacts.flatMap { cleaner.findOldVersions(it) }
    val oldSnapshots = artifacts.flatMap { cleaner.findOldSnapshots(it) }
    
    // Filter out local-only if not skipping upstream check
    val (removableVersions, protectedVersions) = if (skipUpstream) {
        (allOldVersions + oldSnapshots) to emptyList<com.maven.cleaner.core.ArtifactVersion>()
    } else {
        println("Checking upstream status for potential removals (this may take a while)...")
        runBlocking {
            val finalRemovable = mutableListOf<com.maven.cleaner.core.ArtifactVersion>()
            val finalProtected = mutableListOf<com.maven.cleaner.core.ArtifactVersion>()
            
            artifacts.forEach { art ->
                val versionsToCheck = cleaner.findOldVersions(art) + cleaner.findOldSnapshots(art)
                versionsToCheck.forEach { v ->
                    val exists = upstreamChecker.checkMavenCentral(art.groupId, art.artifactId, v.version)
                    if (exists) {
                        finalRemovable.add(v)
                    } else {
                        finalProtected.add(v)
                    }
                }
            }
            finalRemovable.toList() to finalProtected.toList()
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

fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "kB", "MB", "GB", "TB")
    val digitGroups = (ln(size.toDouble()) / ln(1024.0)).toInt()
    return String.format("%.2f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}
