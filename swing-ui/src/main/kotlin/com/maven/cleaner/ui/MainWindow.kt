package com.maven.cleaner.ui

import org.slf4j.LoggerFactory
import com.formdev.flatlaf.FlatLightLaf
import com.maven.cleaner.core.ArtifactCleaner
import com.maven.cleaner.core.ArtifactVersion
import com.maven.cleaner.core.RepositoryScanner
import com.maven.cleaner.core.UpstreamChecker
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.*

class MainWindow : JFrame("Maven Repository Cleaner (Kotlin)") {

    private val logger = LoggerFactory.getLogger(MainWindow::class.java)
    private val tableModel = object : DefaultTableModel(arrayOf("GroupId", "ArtifactId", "Version", "Size", "Upstream", "Path", "IsLatest"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == 6) java.lang.Boolean::class.java else String::class.java
        }
    }
    private val scanner = RepositoryScanner()
    private val table = JTable(tableModel)
    private val scanButton = JButton("Scan Repository")
    private val scanSnapshotsButton = JButton("Scan Snapshots")
    private val scanGradleButton = JButton("Scan Gradle Logs")
    
    private val selectSnapshotsButton = JButton("Select Snapshots")
    private val selectOldVersionsButton = JButton("Select Old Versions")
    private val excludeSnapshotsButton = JButton("Exclude Snapshots")
    private val checkUpstreamButton = JButton("Check Upstream")
    private val protectLocalOnlyButton = JButton("Protect Local Only")
    private val dryRunButton = JButton("Dry Run")
    private val deleteButton = JButton("Delete Selected")
    private val useTrashCheckBox = JCheckBox("Use Trash")
    private val showSelectedOnlyCheckBox = JCheckBox("Show Selected Only")
    
    private val statusLabel = JLabel("Ready")
    private val repoPathLabel = JLabel("Repository: ${scanner.getRepositoryPath()}")
    private val totalRepoSizeLabel = JLabel("Total Repo Size: Calculating...")
    private val selectedSizeLabel = JLabel("Selected Size: 0 B")
    
    private val cleaner = ArtifactCleaner()
    private val upstreamChecker = UpstreamChecker()
    private var foundVersions = mutableListOf<ArtifactVersion>()
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        setupUI()
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1200, 750)
        setLocationRelativeTo(null)
        calculateTotalSize()
    }

    private fun calculateTotalSize() {
        coroutineScope.launch(Dispatchers.IO) {
            val totalSize = scanner.calculateTotalRepositorySize()
            withContext(Dispatchers.Main) {
                totalRepoSizeLabel.text = "Total Repo Size: ${formatSize(totalSize)}"
            }
        }
    }

    private fun setupUI() {
        layout = BorderLayout()

        // Toolbar with FlowLayout that can wrap if needed (though FlowLayout itself doesn't wrap well in a JScrollPane, we'll use a better approach)
        val toolbar = JToolBar()
        toolbar.isFloatable = false
        
        scanButton.addActionListener { scan(false) }
        scanSnapshotsButton.addActionListener { scan(true) }
        scanGradleButton.addActionListener { scanGradle() }
        
        selectSnapshotsButton.addActionListener { selectRows { it.endsWith("-SNAPSHOT") } }
        selectOldVersionsButton.addActionListener { selectOldVersions() }
        excludeSnapshotsButton.addActionListener { excludeSnapshots() }
        checkUpstreamButton.addActionListener { checkUpstream() }
        protectLocalOnlyButton.addActionListener { protectLocalOnly() }
        dryRunButton.addActionListener { performDryRun() }
        deleteButton.addActionListener { deleteSelected() }
        
        deleteButton.isEnabled = false
        dryRunButton.isEnabled = false
        selectSnapshotsButton.isEnabled = false
        selectOldVersionsButton.isEnabled = false
        excludeSnapshotsButton.isEnabled = false
        checkUpstreamButton.isEnabled = false
        protectLocalOnlyButton.isEnabled = false
        
        showSelectedOnlyCheckBox.addActionListener { toggleFilter() }
        
        toolbar.add(scanButton)
        toolbar.add(scanSnapshotsButton)
        toolbar.add(scanGradleButton)
        toolbar.addSeparator()
        toolbar.add(selectSnapshotsButton)
        toolbar.add(selectOldVersionsButton)
        toolbar.add(excludeSnapshotsButton)
        toolbar.add(checkUpstreamButton)
        toolbar.add(protectLocalOnlyButton)
        toolbar.addSeparator()
        toolbar.add(dryRunButton)
        toolbar.add(deleteButton)
        toolbar.addSeparator()
        toolbar.add(useTrashCheckBox)
        toolbar.add(showSelectedOnlyCheckBox)
        
        // Wrap JToolBar in a panel with BorderLayout to handle resizing better or just add directly
        add(toolbar, BorderLayout.NORTH)

        // Table
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.selectionModel.addListSelectionListener { updateSelectedSize() }
        table.autoCreateRowSorter = true
        
        // Custom renderer for highlighting latest versions
        val latestVersionColor = Color(230, 255, 230) // Light green
        table.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                val modelRow = table.convertRowIndexToModel(row)
                val isLatest = table.model.getValueAt(modelRow, 6) as? Boolean ?: false
                
                if (!isSelected) {
                    if (isLatest) {
                        c.background = latestVersionColor
                    } else {
                        c.background = table.background
                    }
                }
                return c
            }
        })
        
        // Hide the "IsLatest" column from view but keep it in model
        table.columnModel.removeColumn(table.columnModel.getColumn(6))
        
        add(JScrollPane(table), BorderLayout.CENTER)

        // Status bar
        val statusBar = JPanel(BorderLayout())
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        leftPanel.add(statusLabel)
        leftPanel.add(JSeparator(JSeparator.VERTICAL))
        leftPanel.add(selectedSizeLabel)
        
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        rightPanel.add(repoPathLabel)
        rightPanel.add(JSeparator(JSeparator.VERTICAL))
        rightPanel.add(totalRepoSizeLabel)
        
        statusBar.add(leftPanel, BorderLayout.WEST)
        statusBar.add(rightPanel, BorderLayout.EAST)
        add(statusBar, BorderLayout.SOUTH)
    }

    private fun updateSelectedSize() {
        val selectedRows = table.selectedRows
        var totalSize = 0L
        for (row in selectedRows) {
            val pathStr = table.getValueAt(row, 5) as String
            val path = Paths.get(pathStr)
            if (Files.exists(path)) {
                totalSize += if (Files.isDirectory(path)) calculateSize(path) else Files.size(path)
            }
        }
        selectedSizeLabel.text = "Selected Size: ${formatSize(totalSize)}"
    }

    private fun toggleFilter() {
        val showSelectedOnly = showSelectedOnlyCheckBox.isSelected
        if (showSelectedOnly) {
            val selectedIndices = table.selectedRows.map { table.convertRowIndexToModel(it) }.toSet()
            table.rowSorter = javax.swing.table.TableRowSorter<DefaultTableModel>(tableModel).apply {
                rowFilter = object : RowFilter<DefaultTableModel, Int>() {
                    override fun include(entry: Entry<out DefaultTableModel, out Int>): Boolean {
                        return selectedIndices.contains(entry.identifier)
                    }
                }
            }
        } else {
            table.rowSorter = null
        }
    }

    private fun scan(snapshotsOnly: Boolean) {
        statusLabel.text = "Scanning..."
        scanButton.isEnabled = false
        scanSnapshotsButton.isEnabled = false
        
        object : SwingWorker<List<Array<Any>>, Void>() {
            var allFound: List<ArtifactVersion> = emptyList()
            var totalToFree = 0L

            override fun doInBackground(): List<Array<Any>> {
                val artifacts = scanner.scan()
                foundVersions = artifacts.flatMap { it.versions }.toMutableList()
                
                val versionComparator = com.maven.cleaner.core.VersionComparator()
                val data = mutableListOf<Array<Any>>()
                
                artifacts.sortedWith(compareBy({ it.groupId }, { it.artifactId })).forEach { art ->
                    val sortedVersions = art.versions.sortedWith(versionComparator)
                    val latestVersion = sortedVersions.lastOrNull()
                    
                    sortedVersions.forEach { v ->
                        val isLatest = v == latestVersion
                        data.add(arrayOf(art.groupId, art.artifactId, v.version, formatSize(v.size), "Unknown", v.path.toString(), isLatest))
                    }
                }
                return data
            }

            override fun done() {
                tableModel.rowCount = 0
                showSelectedOnlyCheckBox.isSelected = false
                // Reset sorter to ensure alphabetical initial view if needed
                table.rowSorter = null 
                
                val data = get()
                for (row in data) {
                    tableModel.addRow(row)
                }
                
                // Re-enable sorter
                table.autoCreateRowSorter = true
                
                statusLabel.text = "Found ${data.size} versions in repository."
                scanButton.isEnabled = true
                scanSnapshotsButton.isEnabled = true
                deleteButton.isEnabled = data.isNotEmpty()
                dryRunButton.isEnabled = data.isNotEmpty()
                selectSnapshotsButton.isEnabled = data.isNotEmpty()
                selectOldVersionsButton.isEnabled = data.isNotEmpty()
                excludeSnapshotsButton.isEnabled = data.isNotEmpty()
                checkUpstreamButton.isEnabled = data.isNotEmpty()
                protectLocalOnlyButton.isEnabled = data.isNotEmpty()
                calculateTotalSize()
            }
        }.execute()
    }

    private fun scanGradle() {
        statusLabel.text = "Scanning Gradle logs..."
        object : SwingWorker<List<Array<Any>>, Void>() {
            var logs: List<Path> = emptyList()
            var totalSize = 0L

            override fun doInBackground(): List<Array<Any>> {
                logs = scanner.scanGradleLogs()
                totalSize = logs.sumOf { Files.size(it) }
                return logs.map { arrayOf("Gradle", "Daemon Log", it.fileName.toString(), formatSize(Files.size(it)), "N/A", it.toString(), false) }
            }

            override fun done() {
                tableModel.rowCount = 0
                showSelectedOnlyCheckBox.isSelected = false
                table.rowSorter = null
                get().forEach { tableModel.addRow(it) }
                table.autoCreateRowSorter = true
                statusLabel.text = "Found ${logs.size} logs. To be freed: ${formatSize(totalSize)}"
                deleteButton.isEnabled = logs.isNotEmpty()
                dryRunButton.isEnabled = logs.isNotEmpty()
                calculateTotalSize()
            }
        }.execute()
    }

    private fun performDryRun() {
        val selectedRows = table.selectedRows
        if (selectedRows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select versions to simulate.")
            return
        }

        val toDelete = mutableListOf<Path>()
        var protectedCount = 0
        for (row in selectedRows) {
            val upstreamStatus = table.getValueAt(row, 4) as String
            if (upstreamStatus == "Local Only") {
                protectedCount++
                continue
            }
            val pathStr = table.getValueAt(row, 5) as String
            toDelete.add(Paths.get(pathStr))
        }

        if (protectedCount > 0) {
            JOptionPane.showMessageDialog(this, "Protected $protectedCount local-only dependencies from simulation.")
        }

        if (toDelete.isEmpty()) return

        val totalSize = toDelete.sumOf { if (Files.exists(it)) (if (Files.isDirectory(it)) calculateSize(it) else Files.size(it)) else 0L }
        JOptionPane.showMessageDialog(this, "[DRY RUN] Would have deleted ${toDelete.size} items and freed ${formatSize(totalSize)}.")
    }

    private fun deleteSelected() {
        val selectedRows = table.selectedRows
        if (selectedRows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select versions to delete.")
            return
        }

        val confirm = JOptionPane.showConfirmDialog(this, "Are you sure you want to delete ${selectedRows.size} items?", "Confirm Delete", JOptionPane.YES_NO_OPTION)
            if (confirm == JOptionPane.YES_OPTION) {
                val toDeleteWithIndices = mutableListOf<Pair<Int, Path>>()
                var protectedCount = 0
                for (row in selectedRows) {
                    val modelIndex = table.convertRowIndexToModel(row)
                    val upstreamStatus = tableModel.getValueAt(modelIndex, 4) as String
                    if (upstreamStatus == "Local Only") {
                        protectedCount++
                        continue
                    }
                    val pathStr = tableModel.getValueAt(modelIndex, 5) as String
                    toDeleteWithIndices.add(modelIndex to Paths.get(pathStr))
                }
                
                if (protectedCount > 0) {
                    JOptionPane.showMessageDialog(this, "Protected $protectedCount local-only dependencies from deletion.")
                }
                
                if (toDeleteWithIndices.isEmpty()) return

                val toDeletePaths = toDeleteWithIndices.map { it.second }
                val useTrash = useTrashCheckBox.isSelected
                val progressDialog = ProgressDialog(this, "Deleting Artifacts")
                progressDialog.setMax(toDeletePaths.size)

                object : SwingWorker<Long, Pair<Int, Path>>() {
                    override fun doInBackground(): Long {
                        return runBlocking {
                            cleaner.deletePaths(toDeletePaths, useTrash) { current, total, path ->
                                publish(current to path)
                            }
                        }
                    }

                    override fun process(chunks: List<Pair<Int, Path>>) {
                        val (current, path) = chunks.last()
                        progressDialog.setProgress(current)
                        progressDialog.setMessage("Deleting item $current of ${toDeletePaths.size}")
                        progressDialog.setSubMessage(path.fileName.toString())
                    }

                    override fun done() {
                        progressDialog.dispose()
                        try {
                            val freed = get()
                            JOptionPane.showMessageDialog(this@MainWindow, "Successfully deleted and freed ${formatSize(freed)}.")
                            
                            // Remove deleted rows from model in reverse order to maintain correct indices
                            val sortedIndicesToRemove = toDeleteWithIndices.map { it.first }.sortedDescending()
                            for (index in sortedIndicesToRemove) {
                                if (index < tableModel.rowCount) {
                                    tableModel.removeRow(index)
                                }
                            }
                            
                            // Clear selection and update filter if needed
                            table.clearSelection()
                            if (showSelectedOnlyCheckBox.isSelected) {
                                toggleFilter()
                            }
                            
                            statusLabel.text = "Deleted ${toDeletePaths.size} items. Freed ${formatSize(freed)}."
                            updateSelectedSize()
                        } catch (e: Exception) {
                            logger.error("Deletion failed", e)
                            val message = e.cause?.message ?: e.message ?: "Unknown error"
                            JOptionPane.showMessageDialog(this@MainWindow, "Deletion failed: $message", "Error", JOptionPane.ERROR_MESSAGE)
                            statusLabel.text = "Deletion failed."
                        }
                        calculateTotalSize()
                    }
                }.execute()

                progressDialog.isVisible = true
            }
    }

    private fun calculateSize(path: Path): Long {
        return try {
            Files.walk(path).mapToLong { if (Files.isRegularFile(it)) Files.size(it) else 0L }.sum()
        } catch (e: Exception) { 0L }
    }

    private fun selectRows(predicate: (String) -> Boolean) {
        table.clearSelection()
        for (i in 0 until table.rowCount) {
            val version = table.getValueAt(i, 2) as String
            if (predicate(version)) {
                table.addRowSelectionInterval(i, i)
            }
        }
    }

    private fun selectOldVersions() {
        val artifactGroups = mutableMapOf<Pair<String, String>, MutableList<Int>>()
        for (i in 0 until table.rowCount) {
            val groupId = table.getValueAt(i, 0) as String
            val artifactId = table.getValueAt(i, 1) as String
            if (groupId == "Gradle") continue
            artifactGroups.getOrPut(groupId to artifactId) { mutableListOf() }.add(i)
        }

        table.clearSelection()
        val versionComparator = com.maven.cleaner.core.VersionComparator()

        for ((_, indices) in artifactGroups) {
            if (indices.size <= 1) continue
            
            val versionsWithIndices = indices.map { index ->
                val versionStr = table.getValueAt(index, 2) as String
                index to com.maven.cleaner.core.ArtifactVersion(versionStr, java.nio.file.Paths.get(""), 0)
            }
            
            val sorted = versionsWithIndices.sortedWith(compareBy(versionComparator) { it.second })
            // Drop the latest version
            val oldVersions = sorted.dropLast(1)
            for ((index, _) in oldVersions) {
                table.addRowSelectionInterval(index, index)
            }
        }
    }

    private fun excludeSnapshots() {
        val selectedRows = table.selectedRows.toMutableList()
        table.clearSelection()
        for (row in selectedRows) {
            val version = table.getValueAt(row, 2) as String
            if (!version.endsWith("-SNAPSHOT")) {
                table.addRowSelectionInterval(row, row)
            }
        }
    }

    private fun protectLocalOnly() {
        val selectedRows = table.selectedRows.toMutableList()
        var protectedCount = 0
        for (row in selectedRows) {
            val upstreamStatus = table.getValueAt(row, 4) as String
            if (upstreamStatus == "Local Only") {
                table.removeRowSelectionInterval(row, row)
                protectedCount++
            }
        }
        if (protectedCount > 0) {
            JOptionPane.showMessageDialog(this, "Deselected $protectedCount local-only dependencies.")
        } else {
            JOptionPane.showMessageDialog(this, "No local-only dependencies found in current selection.")
        }
    }

    private fun checkUpstream() {
        statusLabel.text = "Checking upstream status..."
        checkUpstreamButton.isEnabled = false
        
        coroutineScope.launch {
            for (i in 0 until table.rowCount) {
                val groupId = table.getValueAt(i, 0) as String
                val artifactId = table.getValueAt(i, 1) as String
                val version = table.getValueAt(i, 2) as String
                
                if (groupId == "Gradle") continue

                val exists = upstreamChecker.checkMavenCentral(groupId, artifactId, version)
                withContext(Dispatchers.Main) {
                    table.setValueAt(if (exists) "Available" else "Local Only", i, 4)
                }
            }
            statusLabel.text = "Upstream check complete."
            checkUpstreamButton.isEnabled = true
        }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "kB", "MB", "GB", "TB")
        val digitGroups = (ln(size.toDouble()) / ln(1024.0)).toInt()
        return String.format("%.2f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }
}

fun main() {
    SwingUtilities.invokeLater {
        try {
            FlatLightLaf.setup()
        } catch (e: Exception) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            } catch (ex: Exception) {}
        }
        MainWindow().isVisible = true
    }
}
