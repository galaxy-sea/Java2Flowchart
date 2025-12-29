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

package plus.wcj.jetbrains.plugins.java2flowchart;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import plus.wcj.jetbrains.plugins.java2flowchart.extract.FlowExtractor;
import plus.wcj.jetbrains.plugins.java2flowchart.extract.JavaFlowExtractor;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.ControlFlowGraph;
import plus.wcj.jetbrains.plugins.java2flowchart.render.DiagramRenderer;
import plus.wcj.jetbrains.plugins.java2flowchart.render.MermaidFlowchartRenderer;
import plus.wcj.jetbrains.plugins.java2flowchart.render.RenderOptions;
import plus.wcj.jetbrains.plugins.java2flowchart.settings.Java2FlowchartSettings;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Font;

public class MyToolWindowFactory implements ToolWindowFactory {
    private final FlowExtractor extractor = new JavaFlowExtractor();
    private final DiagramRenderer renderer = new MermaidFlowchartRenderer();

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        JTextArea output = new JTextArea();
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JButton generateButton = new JButton("Generate Mermaid");
        generateButton.addActionListener(e -> output.setText(generateDiagram(project)));

        JPanel contentPanel = new JPanel(new BorderLayout(0, 4));
        contentPanel.add(generateButton, BorderLayout.NORTH);
        contentPanel.add(new JBScrollPane(output), BorderLayout.CENTER);

        SimpleToolWindowPanel panel = new SimpleToolWindowPanel(true);
        panel.setContent(contentPanel);

        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    private @NlsContexts.Label String generateDiagram(Project project) {
        return ReadAction.compute(() -> {
            var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) {
                return "No active editor found.";
            }
            PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            if (psiFile == null) {
                return "Cannot locate PSI for current file.";
            }
            int offset = editor.getCaretModel().getOffset();
            PsiElement element = psiFile.findElementAt(offset);
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
            if (method == null) {
                return "Place the caret inside a Java method.";
            }
            ControlFlowGraph graph = extractor.extract(method, Java2FlowchartSettings.getInstance().getState());
            return renderer.render(graph, RenderOptions.topDown());
        });
    }
}
