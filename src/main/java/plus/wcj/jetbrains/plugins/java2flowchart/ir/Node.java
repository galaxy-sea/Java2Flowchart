package plus.wcj.jetbrains.plugins.java2flowchart.ir;

import com.intellij.openapi.util.TextRange;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record Node(String id, NodeType type, String label, Map<String, Object> meta) {
    public Node {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        label = Optional.ofNullable(label).orElse("");
        meta = Optional.ofNullable(meta).orElseGet(Map::of);
    }

    public TextRange textRange() {
        Object range = meta.get("textRange");
        return range instanceof TextRange textRange ? textRange : null;
    }
}
