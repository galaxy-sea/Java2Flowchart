/*
 *  Copyright 2025-present The original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package plus.wcj.jetbrains.plugins.java2flowchart.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.ui.BooleanTableCellRenderer
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.FormBuilder
import javax.swing.DefaultCellEditor
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.Dimension
import plus.wcj.jetbrains.plugins.java2flowchart.Java2FlowchartBundle
import java.awt.BorderLayout

class Java2FlowchartSettingsConfigurable : SearchableConfigurable {
    private val settings = Java2FlowchartSettings.getInstance()
    private lateinit var foldFluentCheckBox: JBCheckBox
    private lateinit var foldNestedCheckBox: JBCheckBox
    private lateinit var foldSequentialCheckBox: JBCheckBox
    private lateinit var foldSetCheckBox: JBCheckBox
    private lateinit var foldGetCheckBox: JBCheckBox
    private lateinit var foldCtorCheckBox: JBCheckBox
    private lateinit var languageCombo: ComboBox<Java2FlowchartSettings.Language>
    private lateinit var languageLabel: JBLabel
    private lateinit var jdkDepthSpinner: JBIntSpinner
    private lateinit var jdkDepthLabel: JBLabel
    private lateinit var callDepthSpinner: JBIntSpinner
    private lateinit var callDepthLabel: JBLabel
    private lateinit var ternaryLevelSpinner: JBIntSpinner
    private lateinit var ternaryLabel: JBLabel
    private lateinit var labelMaxSpinner: JBIntSpinner
    private lateinit var labelMaxLabel: JBLabel
    private lateinit var useJavadocCheckBox: JBCheckBox
    private lateinit var exportSourceCheckBox: JBCheckBox
    private lateinit var skipRegexTable: JBTable
    private lateinit var skipRegexModel: ListTableModel<Java2FlowchartSettings.SkipRegexEntry>
    private var panel: JPanel? = null

    override fun getId(): String = "plus.wcj.jetbrains.plugins.java2flowchart.settings"

    override fun getDisplayName(): String = "Java2Flowchart"

    override fun createComponent(): JComponent {
        initControls()
        buildForm()
        return JPanel(BorderLayout()).apply {
            add(panel, BorderLayout.NORTH)
        }
    }

    override fun isModified(): Boolean {
        val langModified = selectedLanguage() != settings.state.language
        val depthModified = (jdkDepthSpinner.value as Int) != settings.state.jdkApiDepth
        val callDepthModified = (callDepthSpinner.value as Int) != settings.state.callDepth
        val ternaryModified = (ternaryLevelSpinner.value as Int) != settings.state.ternaryExpandLevel
        val labelModified = (labelMaxSpinner.value as Int) != settings.state.labelMaxLength
        val javadocModified = useJavadocCheckBox.isSelected != settings.state.useJavadocLabels
        val exportSourceModified = exportSourceCheckBox.isSelected != settings.state.exportSource
        val foldFluentModified = foldFluentCheckBox.isSelected != settings.state.foldFluentCalls
        val foldNestedModified = foldNestedCheckBox.isSelected != settings.state.foldNestedCalls
        val foldDetailModified =
            foldSequentialCheckBox.isSelected != settings.state.foldSequentialCalls ||
                    foldSetCheckBox.isSelected != settings.state.foldSequentialSetters ||
                    foldGetCheckBox.isSelected != settings.state.foldSequentialGetters ||
                    foldCtorCheckBox.isSelected != settings.state.foldSequentialCtors
        val skipRegexModified = currentSkipEntries() != settings.state.skipRegexEntries
        return foldFluentModified || foldNestedModified || foldDetailModified || langModified || depthModified || callDepthModified || ternaryModified || labelModified || javadocModified || exportSourceModified || skipRegexModified
    }

    override fun apply() {
        settings.state.foldFluentCalls = foldFluentCheckBox.isSelected
        settings.state.foldNestedCalls = foldNestedCheckBox.isSelected
        settings.state.foldSequentialCalls = foldSequentialCheckBox.isSelected
        settings.state.foldSequentialSetters = foldSetCheckBox.isSelected
        settings.state.foldSequentialGetters = foldGetCheckBox.isSelected
        settings.state.foldSequentialCtors = foldCtorCheckBox.isSelected
        settings.state.language = selectedLanguage()
        settings.state.jdkApiDepth = jdkDepthSpinner.number
        settings.state.callDepth = callDepthSpinner.number
        settings.state.ternaryExpandLevel = ternaryLevelSpinner.number
        settings.state.labelMaxLength = labelMaxSpinner.number
        settings.state.useJavadocLabels = useJavadocCheckBox.isSelected
        settings.state.exportSource = exportSourceCheckBox.isSelected
        val skips = currentSkipEntries().filter { it.pattern.isNotBlank() }
        settings.state.skipRegexEntries = skips.toMutableList()
    }

    override fun reset() {
        applyStateToUi()
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun selectedLanguage(): Java2FlowchartSettings.Language =
        languageCombo.selectedItem as? Java2FlowchartSettings.Language ?: Java2FlowchartSettings.Language.EN

    private fun applyLanguageTexts(language: Java2FlowchartSettings.Language) {
        languageLabel.text = Java2FlowchartBundle.message("settings.language.label", language)
        val jdkText = Java2FlowchartBundle.message("settings.jdk.depth", language)
        jdkDepthSpinner.toolTipText = jdkText
        jdkDepthLabel.text = jdkText
        val callText = Java2FlowchartBundle.message("settings.call.depth", language)
        callDepthSpinner.toolTipText = callText
        callDepthLabel.text = callText
        val ternaryText = Java2FlowchartBundle.message("settings.expand.ternary.level", language)
        ternaryLevelSpinner.toolTipText = ternaryText
        ternaryLabel.text = ternaryText
        val labelText = Java2FlowchartBundle.message("settings.label.max", language)
        labelMaxSpinner.toolTipText = labelText
        labelMaxLabel.text = labelText
        useJavadocCheckBox.text = Java2FlowchartBundle.message("settings.use.javadoc", language)
        exportSourceCheckBox.text = Java2FlowchartBundle.message("settings.export.source", language)
        foldFluentCheckBox.text = Java2FlowchartBundle.message("settings.fold.fluent", language)
        foldNestedCheckBox.text = Java2FlowchartBundle.message("settings.fold.nested", language)
        foldSequentialCheckBox.text = Java2FlowchartBundle.message("settings.fold.sequential", language)
        foldSetCheckBox.text = Java2FlowchartBundle.message("settings.fold.seq.set", language)
        foldGetCheckBox.text = Java2FlowchartBundle.message("settings.fold.seq.get", language)
        foldCtorCheckBox.text = Java2FlowchartBundle.message("settings.fold.seq.ctor", language)
        languageCombo.renderer = SimpleListCellRenderer.create("") { item ->
            when (item) {
                Java2FlowchartSettings.Language.EN -> Java2FlowchartBundle.message("settings.language.option.en", language)
                Java2FlowchartSettings.Language.ZH -> Java2FlowchartBundle.message("settings.language.option.zh", language)
                else -> item?.toString() ?: ""
            }
        }
        languageCombo.selectedItem = language
    }

    private fun initControls() {
        languageCombo = ComboBox(Java2FlowchartSettings.Language.entries.toTypedArray()).apply {
            val h = preferredSize.height
            maximumSize = Dimension(200, h)
            preferredSize = Dimension(200, h)
            minimumSize = Dimension(140, h)
            addActionListener {
                val lang = selectedLanguage()
                applyLanguageTexts(lang)
                rebuildSkipRegexModel(lang)
            }
        }
        languageLabel = JBLabel()
        jdkDepthLabel = JBLabel()
        callDepthLabel = JBLabel()
        ternaryLabel = JBLabel()
        labelMaxLabel = JBLabel()
        jdkDepthSpinner = JBIntSpinner(settings.state.jdkApiDepth, -1, 5, 1)
        callDepthSpinner = JBIntSpinner(settings.state.callDepth, -1, 10, 1)
        ternaryLevelSpinner = JBIntSpinner(settings.state.ternaryExpandLevel, -1, 10, 1)
        labelMaxSpinner = JBIntSpinner(settings.state.labelMaxLength, -1, 500, 5)
        useJavadocCheckBox = JBCheckBox()
        exportSourceCheckBox = JBCheckBox()
        foldFluentCheckBox = JBCheckBox()
        foldNestedCheckBox = JBCheckBox()
        foldSequentialCheckBox = JBCheckBox()
        foldSetCheckBox = JBCheckBox()
        foldGetCheckBox = JBCheckBox()
        foldCtorCheckBox = JBCheckBox()
        foldSequentialCheckBox.addActionListener { updateSequentialChildrenEnabled() }
        rebuildSkipRegexModel(settings.state.language)
        applyLanguageTexts(settings.state.language)
        applyStateToUi()
    }

    private fun applyStateToUi() {
        rebuildSkipRegexModel(settings.state.language)
        languageCombo.selectedItem = settings.state.language
        applyLanguageTexts(settings.state.language)
        jdkDepthSpinner.value = settings.state.jdkApiDepth
        callDepthSpinner.value = settings.state.callDepth
        ternaryLevelSpinner.value = settings.state.ternaryExpandLevel
        labelMaxSpinner.value = settings.state.labelMaxLength
        useJavadocCheckBox.isSelected = settings.state.useJavadocLabels
        exportSourceCheckBox.isSelected = settings.state.exportSource
        foldFluentCheckBox.isSelected = settings.state.foldFluentCalls
        foldNestedCheckBox.isSelected = settings.state.foldNestedCalls
        foldSequentialCheckBox.isSelected = settings.state.foldSequentialCalls
        foldSetCheckBox.isSelected = settings.state.foldSequentialSetters
        foldGetCheckBox.isSelected = settings.state.foldSequentialGetters
        foldCtorCheckBox.isSelected = settings.state.foldSequentialCtors
        updateSequentialChildrenEnabled()
        skipRegexModel.items = settings.state.skipRegexEntries.map { Java2FlowchartSettings.SkipRegexEntry(it.enabled, it.pattern) }
    }

    private fun buildForm() {
        val formBuilder = FormBuilder.createFormBuilder()
            .addLabeledComponent(languageLabel, languageCombo, 1, false)
            .addComponent(foldFluentCheckBox)
            .addComponent(foldNestedCheckBox)
            .addComponent(foldSequentialCheckBox)
            .addComponent(sequentialChildrenPanel())
            .addComponent(useJavadocCheckBox)
            .addComponent(exportSourceCheckBox)
            .addLabeledComponent(jdkDepthLabel, jdkDepthSpinner, 1, false)
            .addLabeledComponent(callDepthLabel, callDepthSpinner, 1, false)
            .addLabeledComponent(ternaryLabel, ternaryLevelSpinner, 1, false)
            .addLabeledComponent(labelMaxLabel, labelMaxSpinner, 1, false)
            .addSeparator()
            .addComponent(JBLabel(Java2FlowchartBundle.message("settings.skip.regex.title", selectedLanguage())))
            .addComponent(
                ToolbarDecorator.createDecorator(skipRegexTable)
                    .setAddAction {
                        skipRegexModel.addRow(Java2FlowchartSettings.SkipRegexEntry(true, ""))
                        adjustSkipRegexHeight()
                    }
                    .setRemoveAction {
                        val idx = skipRegexTable.selectedRow
                        if (idx >= 0 && idx < skipRegexModel.rowCount) {
                            skipRegexModel.removeRow(idx)
                            adjustSkipRegexHeight()
                        }
                    }
                    .disableUpDownActions()
                    .createPanel()
            )
        panel = formBuilder.panel
    }

    private fun currentSkipEntries(): List<Java2FlowchartSettings.SkipRegexEntry> =
        (0 until skipRegexModel.rowCount).map { idx ->
            val item = skipRegexModel.getRowValue(idx)
            Java2FlowchartSettings.SkipRegexEntry(item.enabled, item.pattern)
        }

    private fun rebuildSkipRegexModel(language: Java2FlowchartSettings.Language) {
        val existing: List<Java2FlowchartSettings.SkipRegexEntry> =
            if (::skipRegexModel.isInitialized) currentSkipEntries() else settings.state.skipRegexEntries

        if (!::skipRegexTable.isInitialized) {
            skipRegexTable = JBTable()
        }

        val enableColumn = object : ColumnInfo<Java2FlowchartSettings.SkipRegexEntry, Boolean>(
            Java2FlowchartBundle.message("settings.skip.regex.enable", language)
        ) {
            override fun valueOf(item: Java2FlowchartSettings.SkipRegexEntry): Boolean = item.enabled
            override fun isCellEditable(item: Java2FlowchartSettings.SkipRegexEntry?): Boolean = true
            override fun setValue(item: Java2FlowchartSettings.SkipRegexEntry, value: Boolean?) {
                item.enabled = value ?: false
            }
            override fun getColumnClass(): Class<Boolean> = Boolean::class.java
        }

        val regexColumn = object : ColumnInfo<Java2FlowchartSettings.SkipRegexEntry, String>(
            Java2FlowchartBundle.message("settings.skip.regex", language)
        ) {
            override fun valueOf(item: Java2FlowchartSettings.SkipRegexEntry): String = item.pattern
            override fun isCellEditable(item: Java2FlowchartSettings.SkipRegexEntry?): Boolean = true
            override fun setValue(item: Java2FlowchartSettings.SkipRegexEntry, value: String?) {
                item.pattern = value ?: ""
            }
            override fun getColumnClass(): Class<String> = String::class.java
        }

        skipRegexModel = ListTableModel(enableColumn, regexColumn)
        skipRegexModel.items = existing.map { Java2FlowchartSettings.SkipRegexEntry(it.enabled, it.pattern) }
        skipRegexTable.model = skipRegexModel
        skipRegexTable.setDefaultRenderer(Boolean::class.java, BooleanTableCellRenderer())
        skipRegexTable.columnModel.getColumn(0).apply {
            preferredWidth = 55
            minWidth = 55
            maxWidth = 55
            cellEditor = DefaultCellEditor(JCheckBox())
        }
        adjustSkipRegexHeight()
    }

    private fun adjustSkipRegexHeight() {
        val rows = (skipRegexModel.rowCount + 1).coerceAtLeast(3)
        val header = skipRegexTable.tableHeader?.preferredSize?.height ?: 0
        val height = skipRegexTable.rowHeight * rows + header
        val width = (skipRegexTable.preferredScrollableViewportSize?.width ?: 480).coerceAtLeast(320)
        skipRegexTable.preferredScrollableViewportSize = Dimension(width, height)
    }

    private fun updateSequentialChildrenEnabled() {
        val enabled = foldSequentialCheckBox.isSelected
        foldSetCheckBox.isEnabled = enabled
        foldGetCheckBox.isEnabled = enabled
        foldCtorCheckBox.isEnabled = enabled
    }

    private fun sequentialChildrenPanel(): JPanel {
        return JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0)).apply {
            add(foldSetCheckBox)
            add(foldGetCheckBox)
            add(foldCtorCheckBox)
        }
    }
}
