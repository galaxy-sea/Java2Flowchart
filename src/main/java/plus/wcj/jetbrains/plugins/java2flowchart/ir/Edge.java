package plus.wcj.jetbrains.plugins.java2flowchart.ir;

import java.util.Objects;
import java.util.Optional;

public record Edge(
        String from,
        String to,
        EdgeType type,
        String label)
{
    public Edge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        type = Optional.ofNullable(type).orElse(EdgeType.NORMAL);
        label = Optional.ofNullable(label).orElse("");
    }
}
