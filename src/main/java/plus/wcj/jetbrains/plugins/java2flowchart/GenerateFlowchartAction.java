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

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import plus.wcj.jetbrains.plugins.java2flowchart.extract.FlowExtractor;
import plus.wcj.jetbrains.plugins.java2flowchart.extract.JavaFlowExtractor;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.ControlFlowGraph;
import plus.wcj.jetbrains.plugins.java2flowchart.render.DiagramRenderer;
import plus.wcj.jetbrains.plugins.java2flowchart.render.MermaidFlowchartRenderer;
import plus.wcj.jetbrains.plugins.java2flowchart.render.RenderOptions;
import plus.wcj.jetbrains.plugins.java2flowchart.settings.Java2FlowchartSettings;
import plus.wcj.jetbrains.plugins.java2flowchart.settings.Java2FlowchartSettings.Language;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

public class GenerateFlowchartAction extends DumbAwareAction {
    private static final String OUTPUT_DIR = "Java2Flowchart";
    private final FlowExtractor extractor = new JavaFlowExtractor();
    private final DiagramRenderer renderer = new MermaidFlowchartRenderer();

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = currentPsiFile(project, editor);
        boolean enabled = project != null && editor != null && psiFile instanceof PsiJavaFile && findMethod(editor, psiFile) != null;
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = currentPsiFile(project, editor);
        Language language = Java2FlowchartSettings.getInstance().getState().getLanguage();
        if (project == null || editor == null || !(psiFile instanceof PsiJavaFile)) {
            notify(project, Java2FlowchartBundle.message("notify.not.in.method", language), NotificationType.WARNING);
            return;
        }

        PsiMethod method = findMethod(editor, psiFile);
        if (method == null) {
            notify(project, Java2FlowchartBundle.message("notify.method.not.found", language), NotificationType.WARNING);
            return;
        }

        ControlFlowGraph graph = ReadAction.compute(() -> extractor.extract(method, Java2FlowchartSettings.getInstance().getState()));
        String mermaid = renderer.render(graph, RenderOptions.topDown());
        String source = sourceLink(project, (PsiJavaFile) psiFile, method);
        String content = """
                # %s
                
                %s
                
                ```mermaid
                %s
                ```
                
                %s
                """.formatted(method.getName(), source, mermaid, formatSettings(Java2FlowchartSettings.getInstance().getState())).stripTrailing();

        String basePath = project.getBasePath();
        if (basePath == null) {
            notify(project, Java2FlowchartBundle.message("notify.no.basepath", language), NotificationType.ERROR);
            return;
        }

        String pkgPath = packagePath((PsiJavaFile) psiFile);
        String classDir = pkgPath + "/" + className((PsiJavaFile) psiFile);
        String fileName = buildFileName((PsiJavaFile) psiFile, method);
        try {
            WriteAction.run(() -> saveToFile(basePath, classDir, fileName, content));
            notify(project, Java2FlowchartBundle.message("notify.generated", language, OUTPUT_DIR + "/" + classDir + "/" + fileName), NotificationType.INFORMATION);
        } catch (Exception ex) {
            notify(project, Java2FlowchartBundle.message("notify.failed", language, ex.getMessage()), NotificationType.ERROR);
        }
    }

    private PsiMethod findMethod(Editor editor, PsiFile psiFile) {
        return ReadAction.compute(() -> {
            int offset = editor.getCaretModel().getOffset();
            PsiElement element = psiFile.findElementAt(offset);
            return PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
        });
    }

    private String packagePath(PsiJavaFile psiFile) {
        String pkg = psiFile.getPackageName();
        return pkg == null || pkg.isBlank() ? "default" : pkg.replace('.', '/');
    }

    private String buildFileName(PsiJavaFile psiFile, PsiMethod method) {
        String params = Arrays.stream(method.getParameterList().getParameters())
                .map(p -> p.getType().getPresentableText())
                .collect(Collectors.joining(","));
        String methodPart = params.isEmpty() ? method.getName() : method.getName() + "(" + params + ")";
        String raw = methodPart + ".md";
        return raw.replaceAll("[^a-zA-Z0-9._(),-]", "_");
    }

    private String className(PsiJavaFile psiFile) {
        if (psiFile.getVirtualFile() != null) {
            return psiFile.getVirtualFile().getNameWithoutExtension();
        }
        String name = psiFile.getName();
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    private String sourceLink(Project project, PsiJavaFile psiFile, PsiMethod method) {
        VirtualFile vf = psiFile.getVirtualFile();
        if (project == null || vf == null) {
            return "";
        }
        Document doc = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        int line = -1;
        if (doc != null) {
            line = doc.getLineNumber(method.getTextOffset()) + 1;
        }
        String basePath = project.getBasePath();
        VirtualFile baseVf = basePath != null ? LocalFileSystem.getInstance().findFileByPath(basePath) : null;
        String rel = baseVf != null ? VfsUtilCore.getRelativePath(vf, baseVf, '/') : null;
        if (rel == null) {
            VirtualFile contentRoot = ProjectFileIndex.SERVICE.getInstance(project).getContentRootForFile(vf);
            if (contentRoot != null) {
                rel = VfsUtilCore.getRelativePath(vf, contentRoot, '/');
            }
        }
        String display = rel != null ? rel : vf.getPath();
        String target = vf.getPath();
        String fqMethod = psiFile.getPackageName() + "." + className(psiFile) + "." + method.getName() +
                "(" + Arrays.stream(method.getParameterList().getParameters())
                .map(p -> p.getType().getPresentableText())
                .collect(Collectors.joining(", ")) + ")";
        String linkTarget = rel != null ? rel : target;
        return "[" + fqMethod + "](" + linkTarget + ")";
    }

    private void saveToFile(String basePath, String packagePath, String fileName, String content) throws IOException {
        VirtualFile baseDir = LocalFileSystem.getInstance().findFileByPath(basePath);
        Objects.requireNonNull(baseDir, "baseDir");
        VirtualFile outputDir = VfsUtil.createDirectoryIfMissing(baseDir, OUTPUT_DIR);
        Objects.requireNonNull(outputDir, "outputDir");
        VirtualFile pkgDir = VfsUtil.createDirectoryIfMissing(outputDir, packagePath);
        Objects.requireNonNull(pkgDir, "pkgDir");
        VirtualFile target = pkgDir.findChild(fileName);
        if (target == null) {
            target = pkgDir.createChildData(this, fileName);
        }
        VfsUtil.saveText(target, content);
    }

    private String formatSettings(plus.wcj.jetbrains.plugins.java2flowchart.settings.Java2FlowchartSettings.State state) {
        boolean zh = state.getLanguage() == plus.wcj.jetbrains.plugins.java2flowchart.settings.Java2FlowchartSettings.Language.ZH;
        String title = zh ? "设置" : "Settings";
        String merge = zh ? "合并相同调用" : "mergeCalls";
        String depth = zh ? "JDK 调用深度" : "jdkApiDepth";
        String callDepth = zh ? "方法调用深度" : "callDepth";
        String ternary = zh ? "三元展开层级" : "ternaryExpandLevel";
        String label = zh ? "标签最大长度" : "labelMaxLength";
        String lang = zh ? "语言" : "language";
        String foldFluent = zh ? "合并链式调用" : "foldFluentCalls";
        String foldNested = zh ? "合并嵌套调用" : "foldNestedCalls";
        String useJavadoc = zh ? "使用Javadoc" : "useJavadoc";
        String foldSeq = zh ? "折叠顺序调用" : "foldSequentialCalls";
        String foldSet = zh ? "合并连续的 set" : "foldSeqSetters";
        String foldGet = zh ? "合并连续的 get/is" : "foldSeqGetters";
        String foldCtor = zh ? "合并连续的构造方法" : "foldSeqCtors";
        String regexTitle = zh ? "正则表达式" : "regex patterns";
        return """
                - %s
                - %s: %s
                - %s: %d
                - %s: %d
                - %s: %d
                - %s: %d
                - %s: %s
                - %s: %s
                - %s: %s
                - %s: %s
                - %s: %s
                - %s: %s
                - %s: %s
                - %s: %s
                %s
                """.formatted(
                title,
                merge, state.getMergeCalls(),
                depth, state.getJdkApiDepth(),
                callDepth, state.getCallDepth(),
                ternary, state.getTernaryExpandLevel(),
                label, state.getLabelMaxLength(),
                useJavadoc, state.getUseJavadocLabels(),
                lang, state.getLanguage(),
                foldFluent, state.getFoldFluentCalls(),
                foldNested, state.getFoldNestedCalls(),
                foldSeq, state.getFoldSequentialCalls(),
                foldSet, state.getFoldSequentialSetters(),
                foldGet, state.getFoldSequentialGetters(),
                foldCtor, state.getFoldSequentialCtors(),
                formatSkipRegex(state, regexTitle)
        );
    }

    private String formatSkipRegex(plus.wcj.jetbrains.plugins.java2flowchart.settings.Java2FlowchartSettings.State state, String title) {
        var entries = state.getSkipRegexEntries();
        if (entries.isEmpty()) {
            return "- " + title + ": (none)";
        }
        String lines = entries.stream()
                .map(e -> " - [" + (e.getEnabled() ? "x" : " ") + "] " + e.getPattern())
                .collect(java.util.stream.Collectors.joining("\n"));
        return "- " + title + ":\n" + lines;
    }

    private void notify(Project project, String message, NotificationType type) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Java2Flowchart")
                .createNotification(message, type);
        notification.notify(project);
    }

    private PsiFile currentPsiFile(Project project, Editor editor) {
        if (project == null || editor == null) {
            return null;
        }
        return ReadAction.compute(() -> PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()));
    }
}
