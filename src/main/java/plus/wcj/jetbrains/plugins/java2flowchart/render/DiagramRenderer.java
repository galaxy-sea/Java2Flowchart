package plus.wcj.jetbrains.plugins.java2flowchart.render;

import plus.wcj.jetbrains.plugins.java2flowchart.ir.ControlFlowGraph;

public interface DiagramRenderer {
    String id();

    String displayName();

    String render(ControlFlowGraph graph, RenderOptions options);
}
