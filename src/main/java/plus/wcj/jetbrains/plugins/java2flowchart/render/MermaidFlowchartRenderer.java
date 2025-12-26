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

import plus.wcj.jetbrains.plugins.java2flowchart.ir.ControlFlowGraph;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.Edge;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.EdgeType;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.Node;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.NodeType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Objects;

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
        GraphView view = simplify(graph);
        StringBuilder builder = new StringBuilder();
        builder.append("%%{init: {\"flowchart\": {\"defaultRenderer\": \"elk\",\"wrappingWidth\": 9999}} }%%").append("\n");
        builder.append("flowchart ").append(renderOptions.direction()).append("\n");
        java.util.Set<String> connected = new java.util.HashSet<>();
        for (Edge e : view.edges) {
            connected.add(e.from());
            connected.add(e.to());
        }
        connected.add(view.entryId);
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
        builder.append("\n\n");
        return builder.toString();
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
            case START -> "([\"%s\"])".formatted(label);
            case END -> "([\"%s\"])".formatted(label);
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
                return "==>";
            }
            return "== \"" + label + "\" ==>";
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
                case EXCEPTION -> "";
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

        var nodeById = nodes.stream().collect(java.util.stream.Collectors.toMap(Node::id, n -> n));
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
        boolean mergeCallees = false;
        try {
            mergeCallees = plus.wcj.jetbrains.plugins.java2flowchart.settings.Java2FlowchartSettings.getInstance().getState().getMergeCalls();
        } catch (Throwable ignored) {
        }
        java.util.Map<String, String> mergedTargets = mergeCallees ? new java.util.HashMap<>() : null;
        java.util.Set<String> renderedGraphs = mergeCallees ? new java.util.HashSet<>() : null;
        java.util.Set<String> callEdgesSeen = new java.util.HashSet<>();
        java.util.Map<String, Integer> callCounters = new java.util.HashMap<>();
        java.util.List<Node> ordered = new java.util.ArrayList<>(view.nodes);
        ordered.sort((a, b) -> {
            int la = lineNumberOf(a.meta());
            int lb = lineNumberOf(b.meta());
            if (la != lb) return Integer.compare(la, lb);
            return a.id().compareTo(b.id());
        });
        for (Node node : ordered) {
            if (node.type() == NodeType.CALL && !node.label().toLowerCase().contains("recursive call")) {
                renderCall(node.id(), node.meta(), lines, mergeCallees, mergedTargets, renderedGraphs, "", callCounters, null, callEdgesSeen);
            } else {
                Object rawInline = node.meta().get("inlineCalls");
                if (rawInline instanceof java.util.List<?> list) {
                    boolean first = true;
                    for (Object o : list) {
                        if (o instanceof java.util.Map<?, ?> m) {
                            if (!lines.isEmpty() && first) {
                                lines.add("");
                            }
                            renderCall(node.id(), (java.util.Map<String, Object>) m, lines, mergeCallees, mergedTargets, renderedGraphs, "", callCounters, null, callEdgesSeen);
                            first = false;
                        }
                    }
                }
            }
        }
        return lines;
    }

    private RenderedGraph renderSubGraph(ControlFlowGraph graph, String prefix, java.util.List<String> lines, AtomicInteger idx,
                                         java.util.Set<String> renderedGraphs, String callPrefix,
                                         java.util.Map<String, Integer> callCounters, boolean mergeCallees,
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
        orderedNodes.sort((a, b) -> {
            int la = lineNumberOf(a.meta());
            int lb = lineNumberOf(b.meta());
            if (la != lb) return Integer.compare(la, lb);
            return a.id().compareTo(b.id());
        });
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
            renderCall(prefix + node.id(), node.meta(), lines, mergeCallees, mergedTargets,
                    renderedGraphs, callPrefix, callCounters, null, callEdgesSeen);
        }
        return new RenderedGraph(entryTarget, exitTarget);
    }

    private String renderCall(String sourceId, Map<String, Object> meta,
                            java.util.List<String> lines, boolean mergeCallees,
                            java.util.Map<String, String> mergedTargets, java.util.Set<String> renderedGraphs,
                            String callPrefix, java.util.Map<String, Integer> callCounters,
                            String forcedLabel, java.util.Set<String> callEdgesSeen) {
        Object calleeObj = meta.get("callee");
        Object calleeKeyObj = meta.get("calleeKey");
        Object calleeBodyObj = meta.get("calleeBody");
        Object calleeGraphObj = meta.get("calleeGraph");
        boolean inline = Boolean.TRUE.equals(meta.get("inline"));
        if (!(calleeObj instanceof String callee) || callee.isBlank()) {
            return "";
        }
        // Render inline calls first using the same prefix so numbering follows evaluation order.
        Object rawInline = meta.get("inlineCalls");
        String selfKey = inlineKey(meta);
        if (rawInline instanceof List<?> list) {
            java.util.Map<String, Map<String, Object>> inlineUnique = new java.util.LinkedHashMap<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> mm = (Map<String, Object>) m;
                    String key = inlineKey(mm);
                    if (selfKey != null && selfKey.equals(key)) {
                        continue; // skip duplicate of primary call
                    }
                    inlineUnique.putIfAbsent(key, mm);
                }
            }
            for (Map<String, Object> m : inlineUnique.values()) {
                renderCall(sourceId, m, lines, mergeCallees, mergedTargets, renderedGraphs, callPrefix, callCounters, null, callEdgesSeen);
            }
        }
        // Allocate index for this call
        int baseIdx = callCounters.merge(callPrefix, 1, Integer::sum);
        String baseLabel = callPrefix.isEmpty() ? String.valueOf(baseIdx) : callPrefix + baseIdx;

        String calleeBody = calleeBodyObj instanceof String s ? s : null;
        String calleeDisplay = meta.get("calleeDisplay") instanceof String s ? s : callee;
        String calleeKey = calleeKeyObj instanceof String s ? s : callee;
        Object lineObj = meta.get("lineNumber");
        String baseId;
        if (lineObj instanceof Number n) {
            baseId = "cL" + n.intValue();
        } else {
            String sanitized = sanitizeId(calleeKey);
            baseId = "c" + Math.abs(sanitized.hashCode());
        }
        String targetId = null;
        if (mergeCallees && mergedTargets.containsKey(calleeKey)) {
            targetId = mergedTargets.get(calleeKey);
        }
        boolean skipEdge = Boolean.TRUE.equals(meta.get("skipCallRender"));
        String labelText = forcedLabel != null ? forcedLabel : baseLabel;

        String childPrefix = baseLabel + ".";
        callCounters.remove(childPrefix); // reset child counter for this branch

        if (calleeGraphObj instanceof ControlFlowGraph calleeGraph && targetId == null) {
            if (inline) {
                String entryNodeId = calleeGraph.entryId();
                Node entryNode = calleeGraph.nodes().stream()
                        .filter(n -> n.id().equals(entryNodeId))
                        .findFirst()
                        .orElse(null);
                targetId = baseId + "_" + entryNodeId;
                if (entryNode != null) {
                    lines.add(targetId + nodeShape(entryNode));
                } else {
                    lines.add(targetId + "[\"start\"]");
                }
                if (mergeCallees) {
                    mergedTargets.putIfAbsent(calleeKey, targetId);
                }
            } else {
                String prefix = baseId + "_";
                RenderedGraph rendered = renderSubGraph(calleeGraph, prefix, lines, new AtomicInteger(0),
                        mergeCallees ? renderedGraphs : null, labelText + ".", callCounters, mergeCallees, mergedTargets);
                targetId = rendered.entryId();
                if (mergeCallees) {
                    mergedTargets.putIfAbsent(calleeKey, targetId);
                }
            }
        }
        if (!skipEdge && targetId == null) {
            targetId = baseId + "_stub";
            String label = calleeDisplay != null && !calleeDisplay.isBlank()
                    ? calleeDisplay
                    : (calleeBody != null && !calleeBody.isBlank() ? calleeBody : callee);
            lines.add(targetId + "[\"" + escape(label) + "\"]");
            if (mergeCallees) {
                mergedTargets.putIfAbsent(calleeKey, targetId);
            }
        }
        if (!skipEdge && targetId != null) {
            String edgeKey = sourceId + "|" + targetId + "|" + calleeKey;
            if (callEdgesSeen == null || callEdgesSeen.add(edgeKey)) {
                lines.add(sourceId + " -. \"calls:" + labelText + "\" .-> " + targetId);
            }
        }

        return labelText;
    }

    private record RenderedGraph(String entryId, String exitId) {
    }

    private String sanitizeId(String raw) {
        if (raw == null) {
            return "unknown";
        }
        return raw.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private int lineNumberOf(Map<String, Object> meta) {
        Object lineObj = meta != null ? meta.get("lineNumber") : null;
        if (lineObj instanceof Number n) {
            return n.intValue();
        }
        return Integer.MAX_VALUE;
    }

    private String inlineKey(Map<String, Object> meta) {
        Object calleeKey = meta.get("calleeKey");
        if (calleeKey instanceof String s && !s.isBlank()) {
            return "calleeKey:" + s;
        }
        Object callee = meta.get("callee");
        if (callee instanceof String s && !s.isBlank()) {
            return "callee:" + s;
        }
        Object display = meta.get("calleeDisplay");
        if (display instanceof String s && !s.isBlank()) {
            return "display:" + s;
        }
        return "metaHash:" + meta.hashCode();
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
        // explicit chain id from splitter
        Object chainA = from.meta().get("fluentChainId");
        Object chainB = to.meta().get("fluentChainId");
        if (chainA != null && chainA.equals(chainB)) {
            return true;
        }
        if (from.type() != NodeType.CALL || to.type() != NodeType.CALL) return false;
        String qa = qualifierOf(from.label());
        String qb = qualifierOf(to.label());
        return qa != null && qa.equals(qb);
    }

    private String qualifierOf(String label) {
        if (label == null) return null;
        int paren = label.indexOf('(');
        String prefix = paren > 0 ? label.substring(0, paren) : label;
        int lastDot = prefix.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return prefix.substring(0, lastDot);
    }
}
