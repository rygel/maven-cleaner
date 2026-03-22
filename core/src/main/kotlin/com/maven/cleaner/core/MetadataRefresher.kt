package com.maven.cleaner.core

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.StringWriter
import kotlin.concurrent.withLock
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class MetadataRefresher {

    private val logger = LoggerFactory.getLogger(MetadataRefresher::class.java)
    private val lock = ReentrantLock()
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        .withZone(ZoneId.of("UTC"))

    /**
     * Refreshes metadata files in the parent directory of a deleted version.
     * @param artifactPath The path to the artifact directory (containing version subdirectories).
     */
    fun refreshMetadata(artifactPath: Path) {
        if (!artifactPath.exists() || !artifactPath.isDirectory()) return

        lock.withLock {
            val metadataFiles = listOf("maven-metadata-local.xml", "maven-metadata.xml")
                .map { artifactPath.resolve(it) }
                .filter { it.exists() }

            if (metadataFiles.isEmpty()) return

            // Find remaining versions on disk
            val remainingVersions = artifactPath.listDirectoryEntries()
                .filter { it.isDirectory() }
                .map { it.name }
                .toSet()

            if (remainingVersions.isEmpty()) {
                // No versions left, delete all metadata and checksums
                cleanupArtifactDirectory(artifactPath)
                return
            }

            for (metadataFile in metadataFiles) {
                updateMetadataFile(metadataFile, remainingVersions)
            }
        }
    }

    private fun updateMetadataFile(file: Path, actualVersions: Set<String>) {
        try {
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = true // Set namespace aware
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(file.toFile())

            // Try with and without namespace for better robustness
            val versioning = getVersioningElement(doc) ?: return
            val versionsList = getVersionsElement(versioning) ?: return
            val versionNodes = versionsList.getElementsByTagName("version")

            val toRemove = mutableListOf<Element>()
            val versionsFound = mutableListOf<String>()

            for (i in 0 until versionNodes.length) {
                val versionNode = versionNodes.item(i) as Element
                val versionText = versionNode.textContent
                if (!actualVersions.contains(versionText)) {
                    toRemove.add(versionNode)
                } else {
                    versionsFound.add(versionText)
                }
            }

            if (versionsFound.isEmpty()) {
                Files.deleteIfExists(file)
                deleteChecksums(file)
                return
            }

            // Remove non-existent versions
            for (node in toRemove) {
                versionsList.removeChild(node)
            }

            // Update latest and release
            // Assuming the actualVersions/versionsFound are already somewhat ordered or we just take the "highest"
            val sortedVersions = versionsFound.sortedWith(VersionComparatorForMetadata())
            val latestVersion = sortedVersions.last()

            updateOrAddElement(versioning, "latest", latestVersion)
            // Only update "release" if it's not a snapshot
            if (!latestVersion.endsWith("-SNAPSHOT")) {
                updateOrAddElement(versioning, "release", latestVersion)
            }

            // Update lastUpdated
            val now = timestampFormatter.format(java.time.Instant.now())
            updateOrAddElement(versioning, "lastUpdated", now)

            saveDocument(doc, file)
            updateChecksums(file)

        } catch (e: Exception) {
            logger.error("Failed to update metadata file {}: {}", file, e.message)
        }
    }

    private fun getVersioningElement(doc: Document): Element? {
        val list = doc.getElementsByTagName("versioning")
        return if (list.length > 0) list.item(0) as Element else null
    }

    private fun getVersionsElement(versioning: Element): Element? {
        val list = versioning.getElementsByTagName("versions")
        return if (list.length > 0) list.item(0) as Element else null
    }

    private fun updateOrAddElement(parent: Element, tagName: String, value: String) {
        val nodes = parent.getElementsByTagName(tagName)
        if (nodes.length > 0) {
            nodes.item(0).textContent = value
        } else {
            val newElement = parent.ownerDocument.createElement(tagName)
            newElement.textContent = value
            parent.appendChild(newElement)
        }
    }

    private fun saveDocument(doc: Document, file: Path) {
        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        
        val source = DOMSource(doc)
        val result = StreamResult(file.toFile())
        transformer.transform(source, result)
    }

    private fun cleanupArtifactDirectory(path: Path) {
        // Delete metadata and checksums
        path.listDirectoryEntries().forEach { entry ->
            if (entry.name.contains("maven-metadata")) {
                Files.deleteIfExists(entry)
            }
        }
        // If directory is empty (except maybe for some hidden files), we could delete it, 
        // but let's be conservative and only delete if it's truly empty of relevant files.
        if (path.listDirectoryEntries().isEmpty()) {
            Files.deleteIfExists(path)
        }
    }

    private fun deleteChecksums(file: Path) {
        Files.deleteIfExists(file.resolveSibling("${file.name}.md5"))
        Files.deleteIfExists(file.resolveSibling("${file.name}.sha1"))
        Files.deleteIfExists(file.resolveSibling("${file.name}.sha256"))
        Files.deleteIfExists(file.resolveSibling("${file.name}.sha512"))
    }

    private fun updateChecksums(file: Path) {
        // In a local repo, we might just delete old checksums or re-generate them.
        // For simplicity and speed, deleting them is often enough as Maven will re-generate or ignore if missing locally.
        // But better to delete them so they don't stay stale.
        deleteChecksums(file)
    }
}

class VersionComparatorForMetadata : Comparator<String> {
    override fun compare(v1: String, v2: String): Int {
        val parts1 = v1.split(".", "-", "_").filter { it.isNotEmpty() }
        val parts2 = v2.split(".", "-", "_").filter { it.isNotEmpty() }
        
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
