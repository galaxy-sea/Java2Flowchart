package plus.wcj.jetbrains.plugins.java2flowchart.ir;

import java.util.List;
import java.util.Objects;

public record ControlFlowGraph(String entryId, String exitId, List<Node> nodes, List<Edge> edges) {
    public ControlFlowGraph {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(exitId, "exitId");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
