package com.maven.cleaner.core

import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

interface TrashProvider {
    fun isSupported(): Boolean
    fun moveToTrash(path: Path)
}

class DesktopTrashProvider : TrashProvider {
    override fun isSupported(): Boolean = 
        java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)

    override fun moveToTrash(path: Path) {
        java.awt.Desktop.getDesktop().moveToTrash(path.toFile())
    }
}

class ArtifactCleaner(private val trashProvider: TrashProvider = DesktopTrashProvider()) {

    private val logger = LoggerFactory.getLogger(ArtifactCleaner::class.java)
    private val metadataRefresher = MetadataRefresher()
    private val lock = ReentrantLock()

    fun findOldVersions(artifact: Artifact): List<ArtifactVersion> {
        if (artifact.versions.size <= 1) return emptyList()

        // Sort versions by comparison
        val sortedVersions = artifact.versions.sortedWith(VersionComparator())
        
        // Keep the latest one
        return sortedVersions.dropLast(1)
    }

    fun findOldSnapshots(artifact: Artifact): List<ArtifactVersion> {
        return artifact.versions.filter { it.isSnapshot }
    }

    suspend fun deletePaths(paths: List<Path>, useTrash: Boolean = false, onProgress: ((Int, Int, Path) -> Unit)? = null): Long = coroutineScope {
        val totalFreed = AtomicLong(0L)
        val affectedParents = mutableSetOf<Path>()
        val total = paths.size
        val counter = AtomicInteger(0)
        
        // Concurrency limit. Trash operations are often serialized by OS, 
        // but we can at least parallelize the prep work (size calculation).
        val semaphore = Semaphore(20) 

        val jobs = paths.map { path ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (path.exists()) {
                        // Pre-calculate size before any deletion/trash operation
                        val size = if (path.isDirectory()) calculateSize(path) else Files.size(path)
                        val parent = path.parent
                        
                        val currentCount = counter.incrementAndGet()
                        onProgress?.invoke(currentCount, total, path)
                        
                        if (useTrash && trashProvider.isSupported()) {
                            try {
                                // Desktop.moveToTrash is often slow/blocking on Windows UI thread or shell.
                                // Calling it from Dispatchers.IO is correct.
                                trashProvider.moveToTrash(path)
                            } catch (e: Exception) {
                                logger.error("Trash failed for {}: {}", path, e.message)
                                throw Exception("Failed to move to trash: $path. Error: ${e.message}. Deletion aborted to prevent permanent removal.")
                            }
                        } else {
                            if (path.isDirectory()) deleteDirectory(path) else Files.deleteIfExists(path)
                        }

                        totalFreed.addAndGet(size)
                        
                        if (parent != null) {
                            lock.withLock {
                                affectedParents.add(parent)
                            }
                        }
                    } else {
                        logger.warn("Path does not exist: {}", path)
                    }
                }
            }
        }

        // Wait for all deletions to complete
        jobs.awaitAll()

        // Refresh metadata for all affected parent directories
        for (parent in affectedParents) {
            try {
                metadataRefresher.refreshMetadata(parent)
            } catch (e: Exception) {
                logger.error("Failed to refresh metadata for {}: {}", parent, e.message)
            }
        }

        totalFreed.get()
    }

    private fun calculateSize(path: Path): Long {
        return Files.walk(path).mapToLong { if (Files.isRegularFile(it)) Files.size(it) else 0L }.sum()
    }

    suspend fun deleteVersions(versions: List<ArtifactVersion>, useTrash: Boolean = false): Long {
        return deletePaths(versions.map { it.path }, useTrash)
    }

    private fun deleteDirectory(path: Path) {
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}

/**
 * A simple version comparator. In a real app, we might want to use something like Maven's own version comparison logic.
 */
class VersionComparator : Comparator<ArtifactVersion> {
    override fun compare(v1: ArtifactVersion, v2: ArtifactVersion): Int {
        val parts1 = v1.version.split(".", "-", "_").filter { it.isNotEmpty() }
        val parts2 = v2.version.split(".", "-", "_").filter { it.isNotEmpty() }
        
        val minLen = minOf(parts1.size, parts2.size)
        for (i in 0 until minLen) {
            val p1 = parts1[i]
            val p2 = parts2[i]
            
            val n1 = p1.toIntOrNull()
            val n2 = p2.toIntOrNull()
            
            if (n1 != null && n2 != null) {
                if (n1 != n2) return n1.compareTo(n2)
            } else {
                if (p1 != p2) return p1.compareTo(p2)
            }
        }
        return parts1.size.compareTo(parts2.size)
    }
}
