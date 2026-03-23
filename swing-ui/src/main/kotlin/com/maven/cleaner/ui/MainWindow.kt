package com.maven.cleaner.ui

import org.slf4j.LoggerFactory
import com.formdev.flatlaf.FlatLightLaf
import com.maven.cleaner.core.ArtifactCleaner
import com.maven.cleaner.core.ArtifactVersion
import com.maven.cleaner.core.GradleScanner
import com.maven.cleaner.core.RepositoryMigrator
import com.maven.cleaner.core.RepositoryScanner
import com.maven.cleaner.core.UpstreamChecker
import com.maven.cleaner.core.UpstreamStatus
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.*
import javax.swing.border.TitledBorder
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import com.maven.cleaner.core.formatSize
import kotlinx.coroutines.*

class MainWindow : JFrame("Maven Cleaner") {

    private val logger = LoggerFactory.getLogger(MainWindow::class.java)
    private val tableModel = object : DefaultTableModel(arrayOf("Source", "GroupId", "ArtifactId", "Version", "Size", "Upstream", "Path", "IsLatest"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                4 -> java.lang.Long::class.java
                7 -> java.lang.Boolean::class.java
                else -> String::class.java
            }
        }
    }
    private val scanner = RepositoryScanner()
    private val gradleScanner = GradleScanner()
    private val table = JTable(tableModel)

    private val statusLabel = JLabel("Ready")
    private val totalRepoSizeLabel = JLabel("Maven: Calculating...")
    private val totalGradleSizeLabel = JLabel("Gradle: Calculating...")
    private val selectedSizeLabel = JLabel("Selected: 0 B")

    private val cleaner = ArtifactCleaner(DesktopTrashProvider())
    private val upstreamChecker = UpstreamChecker()

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sizeCache = mutableMapOf<String, Long>()

    // Buttons
    private val scanMavenButton = JButton("All Versions")
    private val scanSnapshotsButton = JButton("Snapshots Only")
    private val scanGradleLogsButton = JButton("Daemon Logs")
    private val scanGradleCachesButton = JButton("Caches & Dists")
    private val scanGradleAllButton = JButton("Everything")

    private val selectOldVersionsButton = JButton("Select Old Versions")
    private val selectSnapshotsButton = JButton("Select Snapshots")
    private val excludeSnapshotsButton = JButton("Exclude Snapshots")
    private val checkUpstreamButton = JButton("Check Upstream")
    private val protectLocalOnlyButton = JButton("Protect Local Only")
    private val selectAllButton = JButton("Select All")
    private val selectNoneButton = JButton("Select None")

    private val dryRunButton = JButton("Dry Run")
    private val deleteButton = JButton("Delete Selected")
    private val useTrashCheckBox = JCheckBox("Move to Trash", true)
    private val showSelectedOnlyCheckBox = JCheckBox("Show Selected Only")
    private val layoutStatusLabel = JLabel("Detecting...")
    private val migrateSplitButton = JButton("Migrate to Split Layout")

    init {
        setupMenuBar()
        setupUI()
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                shutdown()
            }
        })
        setSize(1300, 800)
        setLocationRelativeTo(null)
        calculateSizes()
    }

    private fun shutdown() {
        coroutineScope.cancel()
        upstreamChecker.close()
        dispose()
        System.exit(0)
    }

    private fun setupMenuBar() {
        val menuBar = JMenuBar()

        val helpMenu = JMenu("Help")
        val aboutItem = JMenuItem("About Maven Cleaner")
        aboutItem.addActionListener { showAboutDialog() }
        helpMenu.add(aboutItem)

        menuBar.add(Box.createHorizontalGlue())
        menuBar.add(helpMenu)

        jMenuBar = menuBar
    }

    private fun showAboutDialog() {
        val message = """
            <html>
            <h2>Maven Cleaner</h2>
            <p>A tool to clean up your local Maven repository and Gradle caches.</p>
            <br/>
            <table>
            <tr><td><b>Features:</b></td></tr>
            <tr><td>- Scan and remove old Maven artifact versions</td></tr>
            <tr><td>- Clean Gradle daemon logs, caches, and distributions</td></tr>
            <tr><td>- Check Maven Central for upstream availability</td></tr>
            <tr><td>- Protect local-only artifacts from deletion</td></tr>
            <tr><td>- Move to Recycle Bin or permanently delete</td></tr>
            </table>
            <br/>
            <p>Built with Kotlin, JDK 21, and FlatLaf.</p>
            <p>Licensed under Apache License 2.0.</p>
            </html>
        """.trimIndent()

        JOptionPane.showMessageDialog(
            this, message, "About Maven Cleaner", JOptionPane.INFORMATION_MESSAGE
        )
    }

    private fun detectLayoutStatus() {
        coroutineScope.launch(Dispatchers.IO) {
            val migrator = RepositoryMigrator(upstreamChecker, scanner.getRepositoryPath())
            val status = migrator.detectSplitStatus()
            withContext(Dispatchers.Main) {
                when (status) {
                    RepositoryMigrator.SplitStatus.NOT_SPLIT -> {
                        layoutStatusLabel.text = "Layout: Classic (not split)"
                        layoutStatusLabel.foreground = Color.DARK_GRAY
                        migrateSplitButton.isEnabled = true
                    }
                    RepositoryMigrator.SplitStatus.PARTIALLY_SPLIT -> {
                        layoutStatusLabel.text = "Layout: Partially split"
                        layoutStatusLabel.foreground = Color(180, 120, 0)
                        migrateSplitButton.isEnabled = true
                    }
                    RepositoryMigrator.SplitStatus.FULLY_SPLIT -> {
                        layoutStatusLabel.text = "Layout: Split (cached + installed)"
                        layoutStatusLabel.foreground = Color(0, 130, 0)
                        migrateSplitButton.isEnabled = false
                    }
                }
            }
        }
    }

    private fun calculateSizes() {
        coroutineScope.launch(Dispatchers.IO) {
            val mavenSize = scanner.calculateTotalRepositorySize()
            val gradleSize = gradleScanner.calculateTotalGradleSize()
            withContext(Dispatchers.Main) {
                totalRepoSizeLabel.text = "Maven: ${formatSize(mavenSize)}"
                totalGradleSizeLabel.text = "Gradle: ${formatSize(gradleSize)}"
            }
        }
    }

    private fun setupUI() {
        layout = BorderLayout(0, 4)

        // --- Left side panel with grouped buttons ---
        val sidePanel = JPanel()
        sidePanel.layout = BoxLayout(sidePanel, BoxLayout.Y_AXIS)
        sidePanel.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        // Scan Maven panel
        val scanMavenPanel = createButtonGroup("Scan Maven", listOf(scanMavenButton, scanSnapshotsButton))
        scanMavenButton.addActionListener { scanMaven(false) }
        scanSnapshotsButton.addActionListener { scanMaven(true) }

        // Scan Gradle panel
        val scanGradlePanel = createButtonGroup("Scan Gradle", listOf(scanGradleLogsButton, scanGradleCachesButton, scanGradleAllButton))
        scanGradleLogsButton.addActionListener { scanGradle(logsOnly = true) }
        scanGradleCachesButton.addActionListener { scanGradle(logsOnly = false) }
        scanGradleAllButton.addActionListener { scanGradleAll() }

        // Selection panel
        val selectionPanel = createButtonGroup("Selection", listOf(
            selectOldVersionsButton, selectSnapshotsButton, excludeSnapshotsButton,
            selectAllButton, selectNoneButton
        ))
        selectOldVersionsButton.addActionListener { selectOldVersions() }
        selectSnapshotsButton.addActionListener { selectRows { it.endsWith("-SNAPSHOT") } }
        excludeSnapshotsButton.addActionListener { excludeSnapshots() }
        selectAllButton.addActionListener { table.selectAll() }
        selectNoneButton.addActionListener { table.clearSelection() }

        // Upstream panel
        val upstreamPanel = createButtonGroup("Upstream", listOf(checkUpstreamButton, protectLocalOnlyButton))
        checkUpstreamButton.addActionListener { checkUpstream() }
        protectLocalOnlyButton.addActionListener { protectLocalOnly() }

        // Actions panel
        val actionsPanel = createButtonGroup("Actions", listOf(dryRunButton, deleteButton))
        dryRunButton.addActionListener { performDryRun() }
        deleteButton.addActionListener { deleteSelected() }

        // Options panel
        val optionsPanel = createButtonGroup("Options", listOf(useTrashCheckBox, showSelectedOnlyCheckBox))
        showSelectedOnlyCheckBox.addActionListener { toggleFilter() }

        // Migration panel
        migrateSplitButton.toolTipText = "Reorganize repository into cached/ and installed/ subdirectories"
        migrateSplitButton.addActionListener { migrateSplit() }
        layoutStatusLabel.border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        val migratePanel = createButtonGroup("Repository Layout", listOf(layoutStatusLabel, migrateSplitButton))
        detectLayoutStatus()

        // Disable buttons until scan
        setPostScanButtonsEnabled(false)

        sidePanel.add(scanMavenPanel)
        sidePanel.add(Box.createVerticalStrut(4))
        sidePanel.add(scanGradlePanel)
        sidePanel.add(Box.createVerticalStrut(4))
        sidePanel.add(selectionPanel)
        sidePanel.add(Box.createVerticalStrut(4))
        sidePanel.add(upstreamPanel)
        sidePanel.add(Box.createVerticalStrut(4))
        sidePanel.add(actionsPanel)
        sidePanel.add(Box.createVerticalStrut(4))
        sidePanel.add(optionsPanel)
        sidePanel.add(Box.createVerticalStrut(4))
        sidePanel.add(migratePanel)
        sidePanel.add(Box.createVerticalGlue())

        val scrollSidePanel = JScrollPane(sidePanel)
        scrollSidePanel.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scrollSidePanel.border = BorderFactory.createEmptyBorder()

        add(scrollSidePanel, BorderLayout.WEST)

        // --- Table ---
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.selectionModel.addListSelectionListener { updateSelectedSize() }
        table.autoCreateRowSorter = true

        val latestVersionColor = Color(230, 255, 230)
        val latestRowRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                val displayValue = if (value is Long) formatSize(value) else value
                val c = super.getTableCellRendererComponent(table, displayValue, isSelected, hasFocus, row, column)
                val modelRow = table.convertRowIndexToModel(row)
                val isLatest = table.model.getValueAt(modelRow, 7) as? Boolean ?: false
                if (!isSelected) {
                    c.background = if (isLatest) latestVersionColor else table.background
                }
                return c
            }
        }
        table.setDefaultRenderer(Any::class.java, latestRowRenderer)
        table.setDefaultRenderer(java.lang.Long::class.java, latestRowRenderer)

        // Hide the IsLatest column from view
        table.columnModel.removeColumn(table.columnModel.getColumn(7))

        add(JScrollPane(table), BorderLayout.CENTER)

        // --- Status bar ---
        val statusBar = JPanel(BorderLayout())
        statusBar.border = BorderFactory.createEmptyBorder(2, 8, 2, 8)

        val leftStatus = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        leftStatus.add(statusLabel)
        leftStatus.add(JSeparator(JSeparator.VERTICAL))
        leftStatus.add(selectedSizeLabel)

        val rightStatus = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
        rightStatus.add(totalRepoSizeLabel)
        rightStatus.add(JSeparator(JSeparator.VERTICAL))
        rightStatus.add(totalGradleSizeLabel)

        statusBar.add(leftStatus, BorderLayout.WEST)
        statusBar.add(rightStatus, BorderLayout.EAST)
        add(statusBar, BorderLayout.SOUTH)
    }

    private fun createButtonGroup(title: String, components: List<JComponent>): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP
        )
        for (comp in components) {
            comp.alignmentX = Component.LEFT_ALIGNMENT
            if (comp is JButton) {
                comp.maximumSize = java.awt.Dimension(Int.MAX_VALUE, comp.preferredSize.height)
            }
            panel.add(comp)
            panel.add(Box.createVerticalStrut(2))
        }
        return panel
    }

    private fun setPostScanButtonsEnabled(enabled: Boolean) {
        selectOldVersionsButton.isEnabled = enabled
        selectSnapshotsButton.isEnabled = enabled
        excludeSnapshotsButton.isEnabled = enabled
        checkUpstreamButton.isEnabled = enabled
        protectLocalOnlyButton.isEnabled = enabled
        selectAllButton.isEnabled = enabled
        selectNoneButton.isEnabled = enabled
        dryRunButton.isEnabled = enabled
        deleteButton.isEnabled = enabled
    }

    private fun updateSelectedSize() {
        val selectedRows = table.selectedRows
        var totalSize = 0L
        for (row in selectedRows) {
            val pathStr = table.getValueAt(row, 6) as String
            val cached = sizeCache[pathStr]
            if (cached != null) {
                totalSize += cached
            }
        }
        selectedSizeLabel.text = "Selected: ${formatSize(totalSize)}"
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

    // --- Scan actions ---

    private fun scanMaven(snapshotsOnly: Boolean) {
        statusLabel.text = "Scanning Maven repository..."
        scanMavenButton.isEnabled = false
        scanSnapshotsButton.isEnabled = false

        val progress = ProgressDialog(this, "Scanning Maven")
        progress.setIndeterminate(true)
        progress.setMessage("Scanning repository...")
        progress.setSubMessage(scanner.getRepositoryPath().toString())

        object : SwingWorker<List<Array<Any>>, Void>() {
            override fun doInBackground(): List<Array<Any>> {
                val artifacts = scanner.scan()
                val versionComparator = com.maven.cleaner.core.VersionComparator()
                val data = mutableListOf<Array<Any>>()

                artifacts.sortedWith(compareBy({ it.groupId }, { it.artifactId })).forEach { art ->
                    val sortedVersions = art.versions.sortedWith(versionComparator)
                    val latestVersion = sortedVersions.lastOrNull()

                    sortedVersions.forEach { v ->
                        if (snapshotsOnly && !v.isSnapshot) return@forEach
                        val isLatest = v == latestVersion
                        sizeCache[v.path.toString()] = v.size
                        data.add(arrayOf("Maven", art.groupId, art.artifactId, v.version, v.size, "Unknown", v.path.toString(), isLatest))
                    }
                }
                return data
            }

            override fun done() {
                progress.dispose()
                loadTableData(get(), "Maven")
                scanMavenButton.isEnabled = true
                scanSnapshotsButton.isEnabled = true
            }
        }.execute()
        progress.isVisible = true
    }

    private fun scanGradle(logsOnly: Boolean) {
        statusLabel.text = "Scanning Gradle..."
        scanGradleLogsButton.isEnabled = false
        scanGradleCachesButton.isEnabled = false

        val progress = ProgressDialog(this, "Scanning Gradle")
        progress.setIndeterminate(true)
        progress.setMessage(if (logsOnly) "Scanning daemon logs..." else "Scanning caches and distributions...")

        object : SwingWorker<List<Array<Any>>, Void>() {
            override fun doInBackground(): List<Array<Any>> {
                val items = if (logsOnly) gradleScanner.scanDaemonLogs()
                else gradleScanner.scanCaches() + gradleScanner.scanDistributions()

                return items.map { item ->
                    sizeCache[item.path.toString()] = item.size
                    arrayOf("Gradle", item.category, item.description, "", item.size, "N/A", item.path.toString(), false)
                }
            }

            override fun done() {
                progress.dispose()
                loadTableData(get(), "Gradle")
                scanGradleLogsButton.isEnabled = true
                scanGradleCachesButton.isEnabled = true
            }
        }.execute()
        progress.isVisible = true
    }

    private fun scanGradleAll() {
        statusLabel.text = "Scanning all Gradle data..."
        scanGradleAllButton.isEnabled = false

        val progress = ProgressDialog(this, "Scanning Gradle")
        progress.setIndeterminate(true)
        progress.setMessage("Scanning all Gradle data...")

        object : SwingWorker<List<Array<Any>>, Void>() {
            override fun doInBackground(): List<Array<Any>> {
                val items = gradleScanner.scanAll()
                return items.map { item ->
                    sizeCache[item.path.toString()] = item.size
                    arrayOf("Gradle", item.category, item.description, "", item.size, "N/A", item.path.toString(), false)
                }
            }

            override fun done() {
                progress.dispose()
                loadTableData(get(), "Gradle")
                scanGradleAllButton.isEnabled = true
            }
        }.execute()
        progress.isVisible = true
    }

    private fun loadTableData(data: List<Array<Any>>, source: String) {
        tableModel.rowCount = 0
        showSelectedOnlyCheckBox.isSelected = false
        table.rowSorter = null

        for (row in data) {
            tableModel.addRow(row)
        }

        table.autoCreateRowSorter = true
        statusLabel.text = "Found ${data.size} items ($source)."
        setPostScanButtonsEnabled(data.isNotEmpty())
        calculateSizes()
    }

    // --- Selection actions ---

    private fun selectRows(predicate: (String) -> Boolean) {
        table.clearSelection()
        for (i in 0 until table.rowCount) {
            val version = table.getValueAt(i, 3) as String
            if (predicate(version)) {
                table.addRowSelectionInterval(i, i)
            }
        }
    }

    private fun selectOldVersions() {
        val artifactGroups = mutableMapOf<Pair<String, String>, MutableList<Int>>()
        for (i in 0 until table.rowCount) {
            val source = table.getValueAt(i, 0) as String
            if (source != "Maven") continue
            val groupId = table.getValueAt(i, 1) as String
            val artifactId = table.getValueAt(i, 2) as String
            artifactGroups.getOrPut(groupId to artifactId) { mutableListOf() }.add(i)
        }

        table.clearSelection()
        val versionComparator = com.maven.cleaner.core.VersionComparator()

        for ((_, indices) in artifactGroups) {
            if (indices.size <= 1) continue
            val versionsWithIndices = indices.map { index ->
                val versionStr = table.getValueAt(index, 3) as String
                index to ArtifactVersion(versionStr, Paths.get(""), 0)
            }
            val sorted = versionsWithIndices.sortedWith(compareBy(versionComparator) { it.second })
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
            val version = table.getValueAt(row, 3) as String
            if (!version.endsWith("-SNAPSHOT")) {
                table.addRowSelectionInterval(row, row)
            }
        }
    }

    private fun protectLocalOnly() {
        val selectedRows = table.selectedRows.toMutableList()
        var protectedCount = 0
        for (row in selectedRows) {
            val upstreamStatus = table.getValueAt(row, 5) as String
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

    private data class RowInfo(val source: String, val groupId: String, val artifactId: String, val version: String)

    private fun checkUpstream() {
        statusLabel.text = "Checking upstream status..."
        checkUpstreamButton.isEnabled = false

        val rowData: List<RowInfo> = (0 until table.rowCount).map { i ->
            RowInfo(
                table.getValueAt(i, 0) as String,
                table.getValueAt(i, 1) as String,
                table.getValueAt(i, 2) as String,
                table.getValueAt(i, 3) as String
            )
        }

        val mavenCount = rowData.count { it.source == "Maven" }
        val progress = ProgressDialog(this, "Checking Upstream")
        progress.setMax(mavenCount)
        progress.setMessage("Checking Maven Central...")

        val worker = object : SwingWorker<List<Pair<Int, String>>, Pair<Int, String>>() {
            override fun doInBackground(): List<Pair<Int, String>> {
                return runBlocking {
                    var checked = 0
                    rowData.mapIndexed { i, info ->
                        if (info.source != "Maven") {
                            i to "N/A"
                        } else {
                            if (progress.isCancelled) {
                                cancel(true)
                            }
                            val status = upstreamChecker.checkMavenCentral(info.groupId, info.artifactId, info.version)
                            val label = when (status) {
                                UpstreamStatus.AVAILABLE -> "Available"
                                UpstreamStatus.LOCAL_ONLY -> "Local Only"
                                UpstreamStatus.UNKNOWN -> "Unknown"
                            }
                            checked++
                            publish(checked to "${info.groupId}:${info.artifactId}:${info.version}")
                            i to label
                        }
                    }
                }
            }

            override fun process(chunks: List<Pair<Int, String>>) {
                val (current, artifact) = chunks.last()
                progress.setProgress(current)
                progress.setMessage("Checking $current of $mavenCount")
                progress.setSubMessage(artifact)
            }

            override fun done() {
                progress.dispose()
                if (isCancelled || progress.isCancelled) {
                    statusLabel.text = "Upstream check cancelled."
                    checkUpstreamButton.isEnabled = true
                    return
                }
                try {
                    val results = get()
                    for ((i, label) in results) {
                        if (i < table.rowCount) {
                            table.setValueAt(label, i, 5)
                        }
                    }
                    statusLabel.text = "Upstream check complete."
                } catch (e: Exception) {
                    logger.error("Upstream check failed", e)
                    statusLabel.text = "Upstream check failed."
                }
                checkUpstreamButton.isEnabled = true
            }
        }

        progress.setOnCancel { worker.cancel(true) }
        worker.execute()
        progress.isVisible = true
    }

    // --- Migration ---

    private fun migrateSplit() {
        val migrator = RepositoryMigrator(upstreamChecker, scanner.getRepositoryPath())

        when (migrator.detectSplitStatus()) {
            RepositoryMigrator.SplitStatus.FULLY_SPLIT -> {
                JOptionPane.showMessageDialog(
                    this,
                    "Repository is already fully split into cached/ and installed/.\nNo migration needed.",
                    "Already Migrated",
                    JOptionPane.INFORMATION_MESSAGE
                )
                return
            }
            RepositoryMigrator.SplitStatus.PARTIALLY_SPLIT -> {
                val proceed = JOptionPane.showConfirmDialog(
                    this,
                    "Repository is partially split. Some artifacts are still at the top level.\nContinue migration for the remaining artifacts?",
                    "Partial Migration",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                )
                if (proceed != JOptionPane.YES_OPTION) return
            }
            RepositoryMigrator.SplitStatus.NOT_SPLIT -> {
                // proceed normally
            }
        }

        val info = JOptionPane.showConfirmDialog(
            this,
            """<html>
            <h3>Migrate to Split Repository Layout</h3>
            <p>This will reorganize your Maven repository into:</p>
            <ul>
              <li><b>cached/</b> - downloaded dependencies (safe to delete anytime)</li>
              <li><b>installed/</b> - your locally built artifacts (protected)</li>
            </ul>
            <p>Each artifact will be checked against Maven Central to determine<br/>
            whether it's a downloaded dependency or a local project.</p>
            <br/>
            <p><b>This operation moves files. Make sure no Maven builds are running.</b></p>
            <br/>
            <p>Proceed?</p>
            </html>""".trimIndent(),
            "Migrate Repository",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.INFORMATION_MESSAGE
        )
        if (info != JOptionPane.OK_OPTION) return

        val progressDialog = ProgressDialog(this, "Migrating Repository")
        progressDialog.setMessage("Scanning artifacts...")

        val worker = object : SwingWorker<com.maven.cleaner.core.MigrationResult, String>() {
            override fun doInBackground(): com.maven.cleaner.core.MigrationResult {
                return runBlocking {
                    migrator.migrate(dryRun = false) { current, total, artifact, target ->
                        if (progressDialog.isCancelled) {
                            cancel(true)
                        }
                        publish("[$current/$total] $artifact -> $target")
                    }
                }
            }

            override fun process(chunks: List<String>) {
                val last = chunks.last()
                progressDialog.setMessage(last)
            }

            override fun done() {
                progressDialog.dispose()
                if (isCancelled || progressDialog.isCancelled) {
                    JOptionPane.showMessageDialog(
                        this@MainWindow,
                        "Migration was cancelled. Some artifacts may have already been moved.\nRun a scan to see the current state.",
                        "Cancelled",
                        JOptionPane.WARNING_MESSAGE
                    )
                    return
                }
                try {
                    val result = get()
                    JOptionPane.showMessageDialog(
                        this@MainWindow,
                        """<html>
                        <h3>Migration Complete</h3>
                        <table>
                        <tr><td>Moved to cached/:</td><td><b>${result.movedToCached}</b></td></tr>
                        <tr><td>Moved to installed/:</td><td><b>${result.movedToInstalled}</b></td></tr>
                        <tr><td>Skipped (unknown):</td><td>${result.skipped}</td></tr>
                        <tr><td>Errors:</td><td>${result.errors}</td></tr>
                        </table>
                        <br/>
                        <p>To use the split layout, ensure your .mvn/maven.config contains:</p>
                        <pre>-Daether.enhancedLocalRepository.split=true
-Daether.enhancedLocalRepository.splitRemoteRepository=true</pre>
                        </html>""".trimIndent(),
                        "Migration Complete",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                    calculateSizes()
                    detectLayoutStatus()
                } catch (e: Exception) {
                    logger.error("Migration failed", e)
                    val message = e.cause?.message ?: e.message ?: "Unknown error"
                    JOptionPane.showMessageDialog(this@MainWindow, "Migration failed: $message", "Error", JOptionPane.ERROR_MESSAGE)
                    detectLayoutStatus()
                }
            }
        }

        progressDialog.setOnCancel { worker.cancel(true) }
        worker.execute()
        progressDialog.isVisible = true
    }

    // --- Delete actions ---

    private fun performDryRun() {
        val selectedRows = table.selectedRows
        if (selectedRows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select items to simulate.")
            return
        }

        val toDelete = mutableListOf<Path>()
        var protectedCount = 0
        for (row in selectedRows) {
            val upstreamStatus = table.getValueAt(row, 5) as String
            if (upstreamStatus == "Local Only") {
                protectedCount++
                continue
            }
            val pathStr = table.getValueAt(row, 6) as String
            toDelete.add(Paths.get(pathStr))
        }

        if (protectedCount > 0) {
            JOptionPane.showMessageDialog(this, "Protected $protectedCount local-only items from simulation.")
        }

        if (toDelete.isEmpty()) return

        val totalSize = toDelete.sumOf { sizeCache[it.toString()] ?: 0L }
        JOptionPane.showMessageDialog(this, "[DRY RUN] Would have deleted ${toDelete.size} items and freed ${formatSize(totalSize)}.")
    }

    private fun deleteSelected() {
        val selectedRows = table.selectedRows
        if (selectedRows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select items to delete.")
            return
        }

        // Check if user is about to delete all versions of a Maven artifact
        val selectedModelIndices = selectedRows.map { table.convertRowIndexToModel(it) }.toSet()
        val latestVersionsSelected = mutableListOf<String>()
        for (modelIdx in selectedModelIndices) {
            val source = tableModel.getValueAt(modelIdx, 0) as String
            if (source != "Maven") continue
            val isLatest = tableModel.getValueAt(modelIdx, 7) as? Boolean ?: false
            if (!isLatest) continue
            val groupId = tableModel.getValueAt(modelIdx, 1) as String
            val artifactId = tableModel.getValueAt(modelIdx, 2) as String
            val totalVersions = (0 until tableModel.rowCount).count { i ->
                tableModel.getValueAt(i, 0) == "Maven" &&
                    tableModel.getValueAt(i, 1) == groupId && tableModel.getValueAt(i, 2) == artifactId
            }
            val selectedVersions = (0 until tableModel.rowCount).count { i ->
                selectedModelIndices.contains(i) &&
                    tableModel.getValueAt(i, 0) == "Maven" &&
                    tableModel.getValueAt(i, 1) == groupId && tableModel.getValueAt(i, 2) == artifactId
            }
            if (selectedVersions >= totalVersions) {
                latestVersionsSelected.add("$groupId:$artifactId")
            }
        }

        if (latestVersionsSelected.isNotEmpty()) {
            val names = if (latestVersionsSelected.size <= 5) latestVersionsSelected.joinToString("\n")
            else latestVersionsSelected.take(5).joinToString("\n") + "\n... and ${latestVersionsSelected.size - 5} more"
            val proceed = JOptionPane.showConfirmDialog(
                this,
                "WARNING: You are about to delete ALL versions of ${latestVersionsSelected.size} artifact(s):\n\n$names\n\nThis will remove them completely. Continue?",
                "Delete All Versions?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (proceed != JOptionPane.YES_OPTION) return
        }

        val confirm = JOptionPane.showConfirmDialog(this, "Are you sure you want to delete ${selectedRows.size} items?", "Confirm Delete", JOptionPane.YES_NO_OPTION)
        if (confirm != JOptionPane.YES_OPTION) return

        val toDeleteWithIndices = mutableListOf<Pair<Int, Path>>()
        var protectedCount = 0
        for (row in selectedRows) {
            val modelIndex = table.convertRowIndexToModel(row)
            val upstreamStatus = tableModel.getValueAt(modelIndex, 5) as String
            if (upstreamStatus == "Local Only") {
                protectedCount++
                continue
            }
            val pathStr = tableModel.getValueAt(modelIndex, 6) as String
            toDeleteWithIndices.add(modelIndex to Paths.get(pathStr))
        }

        if (protectedCount > 0) {
            JOptionPane.showMessageDialog(this, "Protected $protectedCount local-only items from deletion.")
        }

        if (toDeleteWithIndices.isEmpty()) return

        val toDeletePaths = toDeleteWithIndices.map { it.second }
        val useTrash = useTrashCheckBox.isSelected
        val progressDialog = ProgressDialog(this, "Deleting")
        progressDialog.setMax(toDeletePaths.size)

        val worker = object : SwingWorker<Long, Pair<Int, Path>>() {
            override fun doInBackground(): Long {
                return runBlocking {
                    cleaner.deletePaths(toDeletePaths, useTrash) { current, total, path ->
                        if (progressDialog.isCancelled) {
                            cancel(true)
                        }
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
                if (isCancelled || progressDialog.isCancelled) {
                    statusLabel.text = "Deletion cancelled."
                    JOptionPane.showMessageDialog(this@MainWindow, "Deletion was cancelled. Some items may have already been deleted.", "Cancelled", JOptionPane.WARNING_MESSAGE)
                    return
                }
                try {
                    val freed = get()
                    JOptionPane.showMessageDialog(this@MainWindow, "Successfully deleted and freed ${formatSize(freed)}.")

                    val sortedIndicesToRemove = toDeleteWithIndices.map { it.first }.sortedDescending()
                    for (index in sortedIndicesToRemove) {
                        if (index < tableModel.rowCount) {
                            tableModel.removeRow(index)
                        }
                    }

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
                calculateSizes()
            }
        }

        progressDialog.setOnCancel { worker.cancel(true) }
        worker.execute()
        progressDialog.isVisible = true
    }
}

fun main() {
    SwingUtilities.invokeLater {
        try {
            FlatLightLaf.setup()
        } catch (e: Exception) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            } catch (ex: Exception) {
                // fallback to default
            }
        }
        MainWindow().isVisible = true
    }
}
