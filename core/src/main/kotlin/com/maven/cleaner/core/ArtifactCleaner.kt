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
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

interface TrashProvider {
    fun isSupported(): Boolean
    fun moveToTrash(path: Path)
    fun moveToTrash(paths: List<Path>) {
        paths.forEach { moveToTrash(it) }
    }
}

class NoOpTrashProvider : TrashProvider {
    override fun isSupported(): Boolean = false
    override fun moveToTrash(path: Path) {
        throw UnsupportedOperationException("Trash is not supported in this environment")
    }
}

class ArtifactCleaner(
    private val trashProvider: TrashProvider = NoOpTrashProvider(),
    private val allowedRoots: List<Path> = listOf(
        RepositoryScanner.defaultRepositoryPath(),
        java.nio.file.Paths.get(System.getProperty("user.home"), ".gradle")
    )
) {

    private val logger = LoggerFactory.getLogger(ArtifactCleaner::class.java)
    private val metadataRefresher = MetadataRefresher()
    private val lock = ReentrantLock()
    private val normalizedRoots by lazy { allowedRoots.map { it.toAbsolutePath().normalize() } }

    private fun validatePath(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        val isAllowed = normalizedRoots.any { root -> normalized.startsWith(root) }
        if (!isAllowed) {
            throw SecurityException(
                "Refusing to operate on path outside allowed roots: $normalized. " +
                    "Allowed roots: $normalizedRoots"
            )
        }
    }

    private fun validatePaths(paths: List<Path>) {
        paths.forEach { validatePath(it) }
    }

    fun findOldVersions(artifact: Artifact): List<ArtifactVersion> {
        if (artifact.versions.size <= 1) return emptyList()
        val sortedVersions = artifact.versions.sortedWith(VersionComparator())
        return sortedVersions.dropLast(1)
    }

    fun findOldSnapshots(artifact: Artifact): List<ArtifactVersion> {
        if (artifact.versions.isEmpty()) return emptyList()
        val sortedVersions = artifact.versions.sortedWith(VersionComparator())
        val latest = sortedVersions.last()
        // Return all snapshots except the latest version (to avoid deleting the only version)
        return artifact.versions.filter { it.isSnapshot && it != latest }
    }

    suspend fun deletePaths(paths: List<Path>, useTrash: Boolean = false, onProgress: ((Int, Int, Path) -> Unit)? = null): Long = coroutineScope {
        validatePaths(paths)
        val totalFreed = AtomicLong(0L)
        val affectedParents = mutableSetOf<Path>()
        val total = paths.size
        val counter = AtomicInteger(0)
        val semaphore = Semaphore(20)

        // Phase 1: Calculate sizes in parallel (no symlink following)
        val existingPaths = paths.map { path ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (path.exists(LinkOption.NOFOLLOW_LINKS)) {
                        val size = if (path.isDirectory(LinkOption.NOFOLLOW_LINKS)) calculateSize(path) else Files.size(path)
                        path to size
                    } else {
                        logger.warn("Path does not exist: {}", path)
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()

        // Phase 2: Delete or trash
        if (useTrash && trashProvider.isSupported()) {
            val batchSize = 50
            for (batch in existingPaths.chunked(batchSize)) {
                ensureActive()
                val batchPaths = batch.map { it.first }
                val batchSizes = batch.map { it.second }

                // Re-validate before destructive operation (TOCTOU mitigation)
                validatePaths(batchPaths)
                batchPaths.forEach { rejectIfSymlink(it) }

                onProgress?.invoke(counter.addAndGet(batchPaths.size), total, batchPaths.last())

                try {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        trashProvider.moveToTrash(batchPaths)
                    }
                } catch (e: Exception) {
                    logger.error("Bulk trash failed: {}", e.message)
                    throw Exception("Failed to move batch to trash. Error: ${e.message}. Deletion aborted to prevent permanent removal.")
                }

                totalFreed.addAndGet(batchSizes.sum())
                for (p in batchPaths) {
                    p.parent?.let { parent ->
                        lock.withLock { affectedParents.add(parent) }
                    }
                }
            }
        } else {
            val jobs = existingPaths.map { (path, size) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        ensureActive()

                        // Re-validate before destructive operation (TOCTOU mitigation)
                        validatePath(path)
                        rejectIfSymlink(path)

                        val currentCount = counter.incrementAndGet()
                        onProgress?.invoke(currentCount, total, path)

                        if (path.isDirectory(LinkOption.NOFOLLOW_LINKS)) deleteDirectory(path) else Files.deleteIfExists(path)

                        totalFreed.addAndGet(size)
                        path.parent?.let { parent ->
                            lock.withLock { affectedParents.add(parent) }
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        // Phase 3: Refresh metadata for all affected parent directories
        for (parent in affectedParents) {
            try {
                metadataRefresher.refreshMetadata(parent)
            } catch (e: Exception) {
                logger.error("Failed to refresh metadata for {}: {}", parent, e.message)
            }
        }

        totalFreed.get()
    }

    private fun rejectIfSymlink(path: Path) {
        if (path.isSymbolicLink()) {
            throw SecurityException("Refusing to operate on symlink: $path")
        }
    }

    private fun calculateSize(path: Path): Long {
        // Do NOT follow symlinks — only count real files within the directory
        return Files.walk(path).use { stream ->
            stream
                .filter { !it.isSymbolicLink() }
                .mapToLong { if (Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)) Files.size(it) else 0L }
                .sum()
        }
    }

    suspend fun deleteVersions(versions: List<ArtifactVersion>, useTrash: Boolean = false): Long {
        return deletePaths(versions.map { it.path }, useTrash)
    }

    private fun deleteDirectory(path: Path) {
        // Walk without following symlinks
        Files.walk(path).use { stream ->
            val entries = stream
                .sorted(Comparator.reverseOrder())
                .toList()

            for (entry in entries) {
                // If a symlink is found inside the directory, delete the link itself, not its target
                if (entry.isSymbolicLink()) {
                    logger.warn("Found symlink inside artifact directory, removing link only: {}", entry)
                }
                // Validate every entry stays within allowed roots
                val normalized = entry.toAbsolutePath().normalize()
                val isAllowed = normalizedRoots.any { root -> normalized.startsWith(root) }
                if (!isAllowed) {
                    throw SecurityException("deleteDirectory walked outside allowed roots: $normalized")
                }
                Files.deleteIfExists(entry)
            }
        }
    }
}

class VersionComparator : Comparator<ArtifactVersion> {
    private val stringComparator = VersionStringComparator()

    override fun compare(v1: ArtifactVersion, v2: ArtifactVersion): Int {
        return stringComparator.compare(v1.version, v2.version)
    }
}
