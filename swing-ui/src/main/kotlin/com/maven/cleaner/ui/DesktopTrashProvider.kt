package com.maven.cleaner.ui

import com.maven.cleaner.core.TrashProvider
import com.sun.jna.platform.win32.W32FileUtils
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists

class DesktopTrashProvider : TrashProvider {

    private val logger = LoggerFactory.getLogger(DesktopTrashProvider::class.java)
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    override fun isSupported(): Boolean {
        return if (isWindows) {
            true
        } else {
            java.awt.Desktop.isDesktopSupported() &&
                java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)
        }
    }

    override fun moveToTrash(path: Path) {
        moveToTrash(listOf(path))
    }

    override fun moveToTrash(paths: List<Path>) {
        if (paths.isEmpty()) return

        if (isWindows) {
            val files = paths.map { it.toFile() }.toTypedArray()
            try {
                W32FileUtils.getInstance().moveToTrash(*files)
            } catch (e: IOException) {
                throw IOException("Failed to move files to Recycle Bin: ${e.message}", e)
            }

            // Verify files are actually gone
            val remaining = paths.filter { it.exists() }
            if (remaining.isNotEmpty()) {
                val names = remaining.joinToString(", ") { it.fileName.toString() }
                throw IOException(
                    "Recycle Bin operation reported success but ${remaining.size} items still exist: $names. " +
                        "This can happen with network drives or drives without a Recycle Bin."
                )
            }
        } else {
            val failed = mutableListOf<Path>()
            for (path in paths) {
                val success = java.awt.Desktop.getDesktop().moveToTrash(path.toFile())
                if (!success) {
                    failed.add(path)
                }
            }
            if (failed.isNotEmpty()) {
                throw IOException("Failed to move ${failed.size} items to trash")
            }
        }
    }
}
