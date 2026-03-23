package com.maven.cleaner.ui

import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Frame
import javax.swing.*

class ProgressDialog(owner: Frame, title: String) : JDialog(owner, title, true) {
    private val progressBar = JProgressBar(0, 100)
    private val messageLabel = JLabel("Starting...")
    private val subMessageLabel = JLabel(" ")
    private val cancelButton = JButton("Cancel")

    @Volatile
    var isCancelled: Boolean = false
        private set

    private var onCancel: (() -> Unit)? = null

    init {
        val panel = JPanel(BorderLayout(10, 10))
        panel.border = BorderFactory.createEmptyBorder(20, 20, 20, 20)

        val labelsPanel = JPanel()
        labelsPanel.layout = BoxLayout(labelsPanel, BoxLayout.Y_AXIS)
        labelsPanel.add(messageLabel)
        labelsPanel.add(Box.createVerticalStrut(5))
        labelsPanel.add(subMessageLabel)

        panel.add(labelsPanel, BorderLayout.NORTH)
        panel.add(progressBar, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        cancelButton.addActionListener {
            isCancelled = true
            cancelButton.isEnabled = false
            messageLabel.text = "Cancelling..."
            onCancel?.invoke()
        }
        buttonPanel.add(cancelButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        contentPane = panel
        defaultCloseOperation = DO_NOTHING_ON_CLOSE

        setSize(400, 170)
        setLocationRelativeTo(owner)
        isResizable = false
    }

    fun setOnCancel(action: () -> Unit) {
        onCancel = action
    }

    fun setProgress(value: Int) {
        SwingUtilities.invokeLater {
            progressBar.value = value
        }
    }

    fun setMax(max: Int) {
        SwingUtilities.invokeLater {
            progressBar.isIndeterminate = false
            progressBar.maximum = max
        }
    }

    fun setIndeterminate(indeterminate: Boolean) {
        SwingUtilities.invokeLater {
            progressBar.isIndeterminate = indeterminate
        }
    }

    fun setMessage(message: String) {
        SwingUtilities.invokeLater {
            messageLabel.text = message
        }
    }

    fun setSubMessage(message: String) {
        SwingUtilities.invokeLater {
            subMessageLabel.text = message
        }
    }
}
