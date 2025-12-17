package plus.wcj.jetbrains.plugins.java2flowchart.extract;

import com.intellij.psi.PsiMethod;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.ControlFlowGraph;

public interface FlowExtractor {
    ControlFlowGraph extract(PsiMethod method, ExtractOptions options);
}
