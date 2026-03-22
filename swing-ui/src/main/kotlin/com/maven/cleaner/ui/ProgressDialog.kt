package com.maven.cleaner.ui

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import javax.swing.*

class ProgressDialog(owner: Frame, title: String) : JDialog(owner, title, true) {
    private val progressBar = JProgressBar(0, 100)
    private val messageLabel = JLabel("Starting...")
    private val subMessageLabel = JLabel(" ")

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
        
        contentPane = panel
        
        setSize(400, 150)
        setLocationRelativeTo(owner)
        isResizable = false
    }

    fun setProgress(value: Int) {
        SwingUtilities.invokeLater {
            progressBar.value = value
        }
    }

    fun setMax(max: Int) {
        SwingUtilities.invokeLater {
            progressBar.maximum = max
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
