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
