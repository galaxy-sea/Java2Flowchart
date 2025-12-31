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

package plus.wcj.jetbrains.plugins.java2flowchart.render;

import plus.wcj.jetbrains.plugins.java2flowchart.ir.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class MermaidFlowchartRenderer implements DiagramRenderer {
    @Override
    public String id() {
        return "mermaid-flowchart";
    }

    @Override
    public String displayName() {
        return "Mermaid Flowchart";
    }

    @Override
    public String render(ControlFlowGraph graph, RenderOptions options) {
        RenderOptions renderOptions = options == null ? RenderOptions.topDown() : options;
        GraphView view = remapStartEnd(simplify(graph));
        StringBuilder builder = new StringBuilder();
        builder.append("%%{init: {\"flowchart\": {\"defaultRenderer\": \"elk\",\"wrappingWidth\": 9999}} }%%").append("\n");
        builder.append("flowchart ").append(renderOptions.direction()).append("\n");
        for (Node node : view.nodes) {
            builder.append("  ").append(node.id()).append(nodeShape(node)).append("\n");
        }
        builder.append("\n");
        renderEdgesCompact(view, builder);
        builder.append("\n");
        for (String line : callChainExtras(view)) {
            builder.append("  ").append(line).append("\n");
        }
        for (String line : recursiveHints(view)) {
            builder.append("  ").append(line).append("\n");
        }
        builder.append("\n");
        builder.append("  classDef startEnd fill:#f9f;\n");
        builder.append("  class n_start,n_end startEnd;\n");
        return builder.toString();
    }

    private GraphView remapStartEnd(GraphView view) {
        String startId = view.entryId;
        String endId = null;
        for (Node n : view.nodes) {
            if (n.type() == NodeType.END) {
                endId = n.id();
                break;
            }
        }
        if (startId == null && endId == null) {
            return view;
        }
        String newStart = startId != null ? "n_start" : null;
        String newEnd = endId != null ? "n_end" : null;
        java.util.Map<String, String> remap = new java.util.HashMap<>();
        if (startId != null) remap.put(startId, newStart);
        if (endId != null) remap.put(endId, newEnd);

        java.util.List<Node> remappedNodes = new java.util.ArrayList<>();
        for (Node n : view.nodes) {
            String newId = remap.getOrDefault(n.id(), n.id());
            remappedNodes.add(new Node(newId, n.type(), n.label(), n.meta()));
        }

        java.util.List<Edge> remappedEdges = new java.util.ArrayList<>();
        for (Edge e : view.edges) {
            String from = remap.getOrDefault(e.from(), e.from());
            String to = remap.getOrDefault(e.to(), e.to());
            remappedEdges.add(new Edge(from, to, e.type(), e.label()));
        }
        String newEntry = remap.getOrDefault(view.entryId, view.entryId);
        return new GraphView(remappedNodes, remappedEdges, newEntry);
    }

    private void renderEdgesCompact(GraphView view, StringBuilder builder) {
        java.util.Map<String, java.util.List<Edge>> out = new java.util.HashMap<>();
        java.util.Map<String, java.util.List<Edge>> in = new java.util.HashMap<>();
        for (Edge e : view.edges) {
            if (e.type() != EdgeType.NORMAL) {
                continue;
            }
            out.computeIfAbsent(e.from(), k -> new java.util.ArrayList<>()).add(e);
            in.computeIfAbsent(e.to(), k -> new java.util.ArrayList<>()).add(e);
        }
        java.util.Map<String, Node> nodeMap = new java.util.HashMap<>();
        for (Node n : view.nodes) {
            nodeMap.put(n.id(), n);
        }
        java.util.Set<Edge> used = new java.util.HashSet<>();
        record Chain(java.util.List<Edge> edges) {}
        java.util.List<Chain> chains = new java.util.ArrayList<>();
        for (Edge edge : view.edges) {
            if (edge.type() != EdgeType.NORMAL || used.contains(edge)) {
                continue;
            }
            boolean start = in.getOrDefault(edge.from(), java.util.List.of()).size() != 1
                    || out.getOrDefault(edge.from(), java.util.List.of()).size() != 1;
            if (!start) {
                continue;
            }
            java.util.List<Edge> seq = new java.util.ArrayList<>();
            Edge current = edge;
            while (true) {
                seq.add(current);
                used.add(current);
                var nextOut = out.getOrDefault(current.to(), java.util.List.of());
                var nextIn = in.getOrDefault(current.to(), java.util.List.of());
                if (nextOut.size() == 1 && nextIn.size() == 1) {
                    Edge next = nextOut.get(0);
                    if (used.contains(next)) {
                        break;
                    }
                    current = next;
                } else {
                    break;
                }
            }
            chains.add(new Chain(seq));
        }
        java.util.Map<String, Integer> maxLenByPair = new java.util.HashMap<>();
        for (Chain c : chains) {
            Edge first = c.edges().get(0);
            Edge last = c.edges().get(c.edges().size() - 1);
            String key = first.from() + "->" + last.to();
            maxLenByPair.merge(key, c.edges().size(), Integer::max);
        }
        for (Chain c : chains) {
            Edge first = c.edges().get(0);
            Edge last = c.edges().get(c.edges().size() - 1);
            String key = first.from() + "->" + last.to();
            int maxLen = maxLenByPair.getOrDefault(key, c.edges().size());
            int missing = Math.max(0, maxLen - c.edges().size());
            StringBuilder line = new StringBuilder();
            line.append("  ").append(first.from());
            for (int j = 0; j < c.edges().size(); j++) {
                Edge e = c.edges().get(j);
                boolean isLast = j == c.edges().size() - 1;
                String edgeText = formatEdge(e, isChainEdge(nodeMap.get(e.from()), nodeMap.get(e.to())));
                if (isLast && missing > 0) {
                    edgeText = "--" + "-".repeat(missing) + ">";
                }
                line.append(edgeText).append(e.to());
            }
            builder.append(line).append("\n");
        }
        for (Edge edge : view.edges) {
            if (edge.type() == EdgeType.NORMAL && used.contains(edge)) {
                continue;
            }
            boolean chainEdge = edge.type() == EdgeType.NORMAL && isChainEdge(nodeMap.get(edge.from()), nodeMap.get(edge.to()));
            builder.append("  ").append(edge.from()).append(formatEdge(edge, chainEdge)).append(edge.to()).append("\n");
        }
    }

    private String nodeShape(Node node) {
        String label = escape(node.label());
        return switch (node.type()) {
            case START, END -> "([\"%s\"])".formatted(label);
            case DECISION, LOOP_HEAD -> "{\"%s\"}".formatted(label);
            default -> "[\"%s\"]".formatted(label);
        };
    }

    private String formatEdge(Edge edge, boolean chainEdge) {
        String label = edgeLabelText(edge);
        if (edge.type() == EdgeType.RETURN) {
            String text = (label.isBlank() || "throw".equalsIgnoreCase(label)) ? "exception" : label;
            return "-. \"" + text + "\" .->";
        }
        if (chainEdge) {
            if (label.isBlank()) {
                return "-->";
            }
            return "--" + label + "-->";
        }
        if (label.isBlank()) {
            return "-->";
        }
        return "-- \"" + label + "\" -->";
    }

    private String edgeLabelText(Edge edge) {
        String label = edge.label();
        if (label == null || label.isBlank()) {
            label = switch (edge.type()) {
                case TRUE -> "true";
                case FALSE -> "false";
                case EXCEPTION -> //noinspection DuplicateBranchesInSwitch
                        "";
                case BREAK -> "break";
                case CONTINUE -> "continue";
                default -> "";
            };
        }
        return label.isBlank() ? "" : escape(label);
    }

    private GraphView simplify(ControlFlowGraph graph) {
        var nodes = graph.nodes();
        var edges = graph.edges();
        var skip = nodes.stream()
                .filter(n -> n.type() == NodeType.MERGE && n.label().isBlank())
                .map(Node::id)
                .toList();
        if (skip.isEmpty()) {
            return new GraphView(nodes, edges, graph.entryId());
        }

        var incoming = new java.util.HashMap<String, java.util.List<Edge>>();
        var outgoing = new java.util.HashMap<String, java.util.List<Edge>>();
        for (Edge e : edges) {
            incoming.computeIfAbsent(e.to(), k -> new java.util.ArrayList<>()).add(e);
            outgoing.computeIfAbsent(e.from(), k -> new java.util.ArrayList<>()).add(e);
        }

        java.util.List<Edge> newEdges = new java.util.ArrayList<>(edges);
        java.util.Set<String> skipSet = new java.util.HashSet<>(skip);
        for (String id : skipSet) {
            var ins = incoming.getOrDefault(id, java.util.List.of());
            var outs = outgoing.getOrDefault(id, java.util.List.of());
            newEdges.removeAll(ins);
            newEdges.removeAll(outs);
            for (Edge in : ins) {
                for (Edge out : outs) {
                    EdgeType type = in.type() != EdgeType.NORMAL ? in.type() : out.type();
                    String label = in.label() != null && !in.label().isBlank() ? in.label() : out.label();
                    Edge combined = new Edge(in.from(), out.to(), type, label);
                    if (!newEdges.contains(combined)) {
                        newEdges.add(combined);
                    }
                }
            }
        }

        java.util.List<Node> newNodes = nodes.stream()
                .filter(n -> !skipSet.contains(n.id()))
                .toList();
        return new GraphView(newNodes, newEdges, graph.entryId());
    }

    private java.util.List<String> recursiveHints(GraphView view) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Node node : view.nodes) {
            if (node.type() == NodeType.CALL && node.label().toLowerCase().contains("recursive call")) {
                String label = escape("recursive call");
                lines.add(node.id() + " -. \"" + label + "\" .-> " + view.entryId);
            }
        }
        return lines;
    }

    private java.util.List<String> callChainExtras(GraphView view) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.Map<String, String> mergedTargets = new java.util.HashMap<>();
        java.util.Set<String> renderedGraphs = new java.util.HashSet<>();
        java.util.Set<String> callEdgesSeen = new java.util.HashSet<>();
        java.util.Map<String, Integer> callCounters = new java.util.HashMap<>();
        java.util.List<Node> ordered = new java.util.ArrayList<>(view.nodes);
        sort(ordered);
        for (Node node : ordered) {
            if (node.type() == NodeType.CALL && !node.label().toLowerCase().contains("recursive call")) {
                renderCall(node.id(), node.meta().copy(), lines, mergedTargets, renderedGraphs, "", callCounters, callEdgesSeen);
            } else {
                java.util.List<NodeMeta> inlineCalls = node.meta().getInlineCalls();
                if (inlineCalls != null && !inlineCalls.isEmpty()) {
                    boolean first = true;
                    for (NodeMeta meta : inlineCalls) {
                        if (!lines.isEmpty() && first) {
                            lines.add("");
                        }
                        renderCall(node.id(), meta.copy(), lines, mergedTargets, renderedGraphs, "", callCounters, callEdgesSeen);
                        first = false;
                    }
                }
            }
        }
        return lines;
    }

    private RenderedGraph renderSubGraph(ControlFlowGraph graph, String prefix, java.util.List<String> lines,
                                         java.util.Set<String> renderedGraphs, String callPrefix,
                                         java.util.Map<String, Integer> callCounters,
                                         java.util.Map<String, String> mergedTargets) {
        if (renderedGraphs != null && !renderedGraphs.add(prefix + graph.entryId())) {
            return new RenderedGraph(prefix + graph.entryId(), null);
        }
        java.util.Set<String> filtered = new java.util.HashSet<>();
        java.util.Set<String> edgeTouched = new java.util.HashSet<>();
        for (Edge e : graph.edges()) {
            edgeTouched.add(e.from());
            edgeTouched.add(e.to());
        }
        lines.add("");
        java.util.List<Node> orderedNodes = new java.util.ArrayList<>(graph.nodes());
        sort(orderedNodes);
        for (Node node : orderedNodes) {
            if (node.type() == NodeType.CALL && !edgeTouched.contains(node.id())) {
                continue; // likely inline-only; skip node rendering
            }
            String newId = prefix + node.id();
            filtered.add(node.id());
            lines.add(newId + nodeShape(node));
        }
        String entryTarget = prefix + graph.entryId();
        String exitTarget = prefix + graph.exitId();
        lines.add("");
        java.util.Map<String, Node> nodeMap = new java.util.HashMap<>();
        for (Node n : graph.nodes()) {
            nodeMap.put(n.id(), n);
        }
        for (Edge edge : graph.edges()) {
            if (!filtered.contains(edge.from()) || !filtered.contains(edge.to())) {
                continue;
            }
            String from = prefix + edge.from();
            String to = prefix + edge.to();
            Node fromNode = nodeMap.get(edge.from());
            Node toNode = nodeMap.get(edge.to());
            boolean chainEdge = edge.type() == EdgeType.NORMAL && isChainEdge(fromNode, toNode);
            lines.add(from + formatEdge(edge, chainEdge) + to);
        }
        java.util.Set<String> callEdgesSeen = new java.util.HashSet<>();
        for (Node node : orderedNodes) {
            if (node.type() != NodeType.CALL) {
                continue;
            }
            renderCall(prefix + node.id(), node.meta().copy(), lines, mergedTargets,
                    renderedGraphs, callPrefix, callCounters, callEdgesSeen);
        }
        return new RenderedGraph(entryTarget, exitTarget);
    }

    private void sort(List<Node> orderedNodes) {
        orderedNodes.sort((a, b) -> {
            int la = lineNumberOf(a.meta());
            int lb = lineNumberOf(b.meta());
            if (la != lb) return Integer.compare(la, lb);
            return a.id().compareTo(b.id());
        });
    }

    private void renderCall(String sourceId, NodeMeta meta,
                            List<String> lines,
                            Map<String, String> mergedTargets, Set<String> renderedGraphs,
                            String callPrefix, Map<String, Integer> callCounters,
                            Set<String> callEdgesSeen) {
        String callee = meta.getCallee();
        String calleeKey = meta.getCalleeKey() != null ? meta.getCalleeKey() : callee;
        String calleeBody = meta.getCalleeBody();
        ControlFlowGraph calleeGraphObj = meta.getCalleeGraph();
        boolean inline = Boolean.TRUE.equals(meta.getInline());
        if (callee == null || callee.isBlank()) {
            return;
        }
        // Render inline calls first using the same prefix so numbering follows evaluation order.
        java.util.List<NodeMeta> inlineCalls = meta.getInlineCalls();
        if (inlineCalls != null) {
            for (NodeMeta inlineMeta : inlineCalls) {
                renderCall(sourceId, inlineMeta.copy(), lines, mergedTargets, renderedGraphs, callPrefix, callCounters, callEdgesSeen);
            }
        }
        // Allocate index for this call
        int baseIdx = callCounters.merge(callPrefix, 1, Integer::sum);
        String baseLabel = callPrefix.isEmpty() ? String.valueOf(baseIdx) : callPrefix + baseIdx;

        String calleeDisplay = meta.getCalleeDisplay() != null ? meta.getCalleeDisplay() : callee;
        Integer lineObj = meta.getLineNumber();
        String baseId;
        if (lineObj != null) {
            baseId = "cL" + lineObj;
        } else {
            String sanitized = sanitizeId(calleeKey);
            baseId = "c" + Math.abs(sanitized.hashCode());
        }
        String targetId = null;
        if (mergedTargets.containsKey(calleeKey)) {
            targetId = mergedTargets.get(calleeKey);
        }
        boolean skipEdge = Boolean.TRUE.equals(meta.getSkipCallRender());

        String childPrefix = baseLabel + ".";
        callCounters.remove(childPrefix); // reset child counter for this branch

        if (calleeGraphObj != null && targetId == null) {
            if (inline) {
                String entryNodeId = calleeGraphObj.entryId();
                Node entryNode = calleeGraphObj.nodes().stream()
                        .filter(n -> n.id().equals(entryNodeId))
                        .findFirst()
                        .orElse(null);
                targetId = baseId + "_" + entryNodeId;
                if (entryNode != null) {
                    lines.add(targetId + nodeShape(entryNode));
                } else {
                    lines.add(targetId + "[\"start\"]");
                }
                mergedTargets.putIfAbsent(calleeKey, targetId);
            } else {
                String prefix = baseId + "_";
                RenderedGraph rendered = renderSubGraph(calleeGraphObj, prefix, lines, renderedGraphs, baseLabel + ".", callCounters, mergedTargets);
                targetId = rendered.entryId();
                mergedTargets.putIfAbsent(calleeKey, targetId);
            }
        }
        if (!skipEdge && targetId == null) {
            targetId = baseId + "_stub";
            String label = !calleeDisplay.isBlank()
                    ? calleeDisplay
                    : calleeBody != null && !calleeBody.isBlank() ? calleeBody : callee;
            lines.add(targetId + "[\"" + escape(label) + "\"]");
            mergedTargets.putIfAbsent(calleeKey, targetId);
        }
        if (!skipEdge) {
            String edgeKey = sourceId + "|" + targetId + "|" + calleeKey;
            if (callEdgesSeen == null || callEdgesSeen.add(edgeKey)) {
                lines.add(sourceId + " -. \"calls:" + baseLabel + "\" .-> " + targetId);
            }
        }

    }

    private record RenderedGraph(String entryId, String exitId) {
    }

    private String sanitizeId(String raw) {
        if (raw == null) {
            return "unknown";
        }
        return raw.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private int lineNumberOf(NodeMeta meta) {
        Integer lineObj = meta != null ? meta.getLineNumber() : null;
        if (lineObj != null) {
            return lineObj;
        }
        return Integer.MAX_VALUE;
    }

    private record GraphView(java.util.List<Node> nodes, java.util.List<Edge> edges, String entryId) {
    }

    private String escape(String label) {
        if (label == null) {
            return "";
        }
        return label
                .replace("\\", "\\\\")
                // Mermaid renders \" as a quote terminator; use entity instead
                .replace("\"", "&quot;")
                .replace("\n", "<br/>");
    }

    private boolean isChainEdge(Node from, Node to) {
        if (from == null || to == null) return false;
        // explicit chain id from splitter; only treat as chain if both nodes were produced by chain split
        String chainA = from.meta().getFluentChainId();
        String chainB = to.meta().getFluentChainId();
        boolean splitA = from.meta().hasChainSplit();
        boolean splitB = to.meta().hasChainSplit();
        return chainA != null && chainA.equals(chainB) && splitA && splitB;
    }

}
