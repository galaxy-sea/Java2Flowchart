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

package plus.wcj.jetbrains.plugins.java2flowchart.extract;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.ControlFlowGraph;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.Edge;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.EdgeType;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.Node;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.NodeType;
import plus.wcj.jetbrains.plugins.java2flowchart.settings.Java2FlowchartSettings;

import com.intellij.psi.JavaRecursiveElementWalkingVisitor;

import java.util.*;
import java.util.function.Supplier;

import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiNewExpression;

import java.util.stream.Collectors;

public class JavaFlowExtractor implements FlowExtractor {
    private static final Logger LOG = Logger.getInstance(JavaFlowExtractor.class);

    @Override
    public ControlFlowGraph extract(PsiMethod method, Java2FlowchartSettings.State state) {
        Objects.requireNonNull(method, "method");
        Java2FlowchartSettings.State safeState = state != null
                ? copyState(state)
                : copyState(defaultState());
        PsiCodeBlock body = method.getBody();
        java.util.Set<PsiMethod> visited = new java.util.HashSet<>();
        visited.add(method);
        Builder builder = new Builder(safeState, method, visited);
        return builder.build(method, body);
    }

    private static Java2FlowchartSettings.State defaultState() {
        try {
            return Java2FlowchartSettings.getInstance().getState();
        } catch (Throwable ignored) {
            return new Java2FlowchartSettings.State();
        }
    }

    private static Java2FlowchartSettings.State copyState(Java2FlowchartSettings.State s) {
        Java2FlowchartSettings.State copy = new Java2FlowchartSettings.State(
                s.getFoldFluentCalls(),
                s.getFoldNestedCalls(),
                s.getFoldSequentialCalls(),
                s.getFoldSequentialSetters(),
                s.getFoldSequentialGetters(),
                s.getFoldSequentialCtors(),
                s.getLanguage(),
                s.getJdkApiDepth(),
                s.getMergeCalls(),
                s.getCallDepth(),
                s.getUseJavadocLabels(),
                new java.util.ArrayList<>(),
                s.getTernaryExpandLevel(),
                s.getLabelMaxLength()
        );
        List<Java2FlowchartSettings.SkipRegexEntry> copied = new ArrayList<>();
        for (Java2FlowchartSettings.SkipRegexEntry entry : s.getSkipRegexEntries()) {
            copied.add(new Java2FlowchartSettings.SkipRegexEntry(entry.getEnabled(), entry.getPattern()));
        }
        copy.setSkipRegexEntries(copied);
        return copy;
    }

    private static final class Builder {
        private enum CallKind {SET, GET, CTOR, OTHER}

        private enum CallRelationType {FLUENT, NESTED}

        private final Java2FlowchartSettings.State state;
        private final boolean foldFluentCalls;
        private final boolean foldNestedCalls;
        private final boolean foldSequentialCalls;
        private final boolean foldSequentialSetters;
        private final boolean foldSequentialGetters;
        private final boolean foldSequentialCtors;
        private final boolean mergeCalls;
        private final int ternaryExpandLevel;
        private final int callDepth;
        private final int jdkApiDepth;
        private final boolean useJavadocLabels;
        private final List<String> skipRegexes;
        private final PsiMethod owner;
        private final java.util.Set<PsiMethod> visited;
        private final List<Node> nodes = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Deque<LoopContext> loopStack = new ArrayDeque<>();
        private final Deque<String> switchMergeStack = new ArrayDeque<>();
        private int idSequence = 0;
        private String endId;
        private boolean forceNoFold = false;
        private final com.intellij.openapi.editor.Document document;
        private final Map<Integer, Integer> lineCounters = new HashMap<>();

        private List<String> filterSkipRegexes(List<Java2FlowchartSettings.SkipRegexEntry> entries) {
            if (entries == null || entries.isEmpty()) {
                return List.of();
            }
            List<String> patterns = new ArrayList<>();
            for (Java2FlowchartSettings.SkipRegexEntry entry : entries) {
                if (entry.getEnabled() && !entry.getPattern().isBlank()) {
                    patterns.add(entry.getPattern());
                }
            }
            return patterns;
        }

        Builder(Java2FlowchartSettings.State state, PsiMethod owner, java.util.Set<PsiMethod> visited) {
            this.state = state;
            this.foldFluentCalls = state.getFoldFluentCalls();
            this.foldNestedCalls = state.getFoldNestedCalls();
            this.foldSequentialCalls = state.getFoldSequentialCalls();
            this.foldSequentialSetters = state.getFoldSequentialSetters();
            this.foldSequentialGetters = state.getFoldSequentialGetters();
            this.foldSequentialCtors = state.getFoldSequentialCtors();
            this.mergeCalls = state.getMergeCalls();
            this.ternaryExpandLevel = state.getTernaryExpandLevel();
            this.callDepth = state.getCallDepth();
            this.jdkApiDepth = state.getJdkApiDepth();
            this.useJavadocLabels = state.getUseJavadocLabels();
            this.skipRegexes = filterSkipRegexes(state.getSkipRegexEntries());
            this.owner = owner;
            this.visited = visited;
            this.document = com.intellij.psi.PsiDocumentManager.getInstance(owner.getProject()).getDocument(owner.getContainingFile());
        }

        ControlFlowGraph build(PsiMethod method, PsiCodeBlock body) {
            String startId = addNode(NodeType.START, methodSummary(method), method.getTextRange());
            endId = addNode(NodeType.END, "End " + method.getName(), method.getTextRange());
            List<Endpoint> tails = List.of(new Endpoint(startId, EdgeType.NORMAL, null));
            if (body != null) {
                tails = processStatements(Arrays.asList(body.getStatements()), tails);
            }
            connectToEnd(tails);
            if (foldSequentialCalls || foldSequentialSetters || foldSequentialGetters || foldSequentialCtors) {
                foldLinearActions();
            }
            return new ControlFlowGraph(startId, endId, nodes, edges);
        }

        private String methodSummary(PsiMethod method) {
            String fallback = method.getName();
            PsiDocComment doc = method.getDocComment();
            if (doc == null) {
                return fallback;
            }
            String desc = Arrays.stream(doc.getDescriptionElements())
                    .map(PsiElement::getText)
                    .collect(Collectors.joining(" "));
            desc = desc.replaceAll("\\s+", " ").trim();
            if (desc.isEmpty()) {
                return fallback;
            }
            int dot = desc.indexOf('.');
            if (dot > 0) {
                return desc.substring(0, dot + 1).trim();
            }
            return desc;
        }

        private String callSummary(PsiMethod method) {
            if (!useJavadocLabels) {
                return "";
            }
            PsiDocComment doc = method.getDocComment();
            if (doc == null) {
                return "";
            }
            String desc = Arrays.stream(doc.getDescriptionElements())
                    .map(PsiElement::getText)
                    .collect(Collectors.joining(" "));
            desc = desc.replaceAll("\\s+", " ").trim();
            if (desc.isEmpty()) {
                return "";
            }
            int dot = desc.indexOf('.');
            if (dot > 0) {
                return desc.substring(0, dot + 1).trim();
            }
            return desc;
        }

        private record CallInfo(NodeType type, String label, Map<String, Object> meta) {
        }

        private boolean isJdkMethod(PsiMethod method) {
            PsiClass containingClass = method.getContainingClass();
            if (containingClass == null) {
                return false;
            }
            String qname = containingClass.getQualifiedName();
            if (qname == null) {
                return false;
            }
            return qname.startsWith("java.") || qname.startsWith("javax.") || qname.startsWith("jdk.")
                    || qname.startsWith("sun.") || qname.startsWith("com.sun.");
        }

        private List<Endpoint> processStatements(List<PsiStatement> statements, List<Endpoint> incoming) {
            List<Endpoint> current = incoming;
            for (PsiStatement statement : statements) {
                current = handleStatement(statement, current);
                if (current.isEmpty()) {
                    break;
                }
            }
            return current;
        }

        private List<Endpoint> handleStatement(PsiStatement statement, List<Endpoint> incoming) {
            if (statement == null) {
                return incoming;
            }
            if (statement instanceof PsiBlockStatement block) {
                return processStatements(Arrays.asList(block.getCodeBlock().getStatements()), incoming);
            }
            if (statement instanceof PsiIfStatement ifStatement) {
                return handleIf(ifStatement, incoming);
            }
            if (statement instanceof PsiWhileStatement whileStatement) {
                return handleWhile(whileStatement, incoming);
            }
            if (statement instanceof PsiForStatement forStatement) {
                return handleFor(forStatement, incoming);
            }
            if (statement instanceof PsiForeachStatement foreachStatement) {
                return handleForeach(foreachStatement, incoming);
            }
            if (statement instanceof PsiDoWhileStatement doWhileStatement) {
                return handleDoWhile(doWhileStatement, incoming);
            }
            if (statement instanceof PsiReturnStatement returnStatement) {
                return handleReturn(returnStatement, incoming);
            }
            if (statement instanceof PsiBreakStatement) {
                return handleBreak(statement, incoming);
            }
            if (statement instanceof PsiContinueStatement) {
                return handleContinue(statement, incoming);
            }
            if (statement instanceof PsiSwitchStatement switchStatement) {
                return handleSwitch(switchStatement, incoming);
            }
            if (statement instanceof PsiThrowStatement throwStatement) {
                return handleThrow(throwStatement, incoming);
            }
            if (statement instanceof PsiTryStatement tryStatement) {
                return handleTry(tryStatement, incoming);
            }
            return handleAction(statement, incoming);
        }

        private List<Endpoint> handleIf(PsiIfStatement ifStatement, List<Endpoint> incoming) {
            Map<String, Object> extras = new HashMap<>();
            List<Map<String, Object>> inlineCalls = collectCalls(ifStatement.getCondition());
            if (!inlineCalls.isEmpty()) {
                extras.put("inlineCalls", inlineCalls);
            }
            String decisionId = addNode(NodeType.DECISION, labelFrom(ifStatement.getCondition(), "if (?)"), ifStatement.getTextRange(), extras);
            link(incoming, decisionId);

            List<Endpoint> thenIncoming = List.of(new Endpoint(decisionId, EdgeType.TRUE, "true"));
            PsiStatement elseBranch = ifStatement.getElseBranch();
            String elseLabel;
            if (elseBranch == null) {
                elseLabel = "false";
            } else if (elseBranch instanceof PsiIfStatement) {
                elseLabel = "false: else if ";
            } else {
                elseLabel = "false: else";
            }
            List<Endpoint> elseIncoming = List.of(new Endpoint(decisionId, EdgeType.FALSE, elseLabel));
            List<Endpoint> thenExits = handleStatement(ifStatement.getThenBranch(), thenIncoming);
            List<Endpoint> elseExits = handleStatement(elseBranch, elseIncoming);

            if (thenExits.isEmpty() && elseExits.isEmpty()) {
                return List.of();
            }
            if (thenExits.isEmpty()) {
                return elseExits;
            }
            if (elseExits.isEmpty()) {
                return thenExits;
            }
            // Let subsequent statements receive both exits without forcing an empty merge node
            List<Endpoint> combined = new ArrayList<>();
            combined.addAll(thenExits);
            combined.addAll(elseExits);
            return combined;
        }

        private List<Endpoint> handleWhile(PsiWhileStatement whileStatement, List<Endpoint> incoming) {
            Map<String, Object> extras = new HashMap<>();
            List<Map<String, Object>> inlineCalls = collectCalls(whileStatement.getCondition());
            if (!inlineCalls.isEmpty()) {
                extras.put("inlineCalls", inlineCalls);
            }
            String headId = addNode(NodeType.LOOP_HEAD, labelFrom(whileStatement.getCondition(), "while (?)"), whileStatement.getTextRange(), extras);
            String afterLoop = addNode(NodeType.MERGE, "", whileStatement.getTextRange());
            link(incoming, headId);

            loopStack.push(new LoopContext(headId, afterLoop));
            List<Endpoint> bodyExits = handleStatement(whileStatement.getBody(), List.of(new Endpoint(headId, EdgeType.TRUE, "true")));
            loopStack.pop();

            link(bodyExits, headId);
            edges.add(new Edge(headId, afterLoop, EdgeType.FALSE, "false"));
            return List.of(new Endpoint(afterLoop, EdgeType.NORMAL, null));
        }

        private List<Endpoint> handleDoWhile(PsiDoWhileStatement doWhileStatement, List<Endpoint> incoming) {
            Map<String, Object> extras = new HashMap<>();
            List<Map<String, Object>> inlineCalls = collectCalls(doWhileStatement.getCondition());
            if (!inlineCalls.isEmpty()) {
                extras.put("inlineCalls", inlineCalls);
            }
            String headId = addNode(NodeType.LOOP_HEAD, labelFrom(doWhileStatement.getCondition(), "do-while (?)"), doWhileStatement.getTextRange(), extras);
            String afterLoop = addNode(NodeType.MERGE, "", doWhileStatement.getTextRange());

            loopStack.push(new LoopContext(headId, afterLoop));
            int edgeStart = edges.size();
            List<Endpoint> bodyExits = handleStatement(doWhileStatement.getBody(), incoming);
            String bodyEntry = findBodyEntry(incoming, edgeStart);
            loopStack.pop();

            link(bodyExits, headId);
            if (bodyEntry != null) {
                edges.add(new Edge(headId, bodyEntry, EdgeType.TRUE, "true"));
            }
            edges.add(new Edge(headId, afterLoop, EdgeType.FALSE, "false"));
            return List.of(new Endpoint(afterLoop, EdgeType.NORMAL, null));
        }

        private String findBodyEntry(List<Endpoint> incoming, int edgeStart) {
            if (incoming.isEmpty()) {
                return null;
            }
            var incomingIds = incoming.stream().map(Endpoint::from).collect(Collectors.toSet());
            for (int i = edgeStart; i < edges.size(); i++) {
                Edge e = edges.get(i);
                if (incomingIds.contains(e.from())) {
                    return e.to();
                }
            }
            return null;
        }

        private List<Endpoint> handleFor(PsiForStatement forStatement, List<Endpoint> incoming) {
            List<Endpoint> afterInit = incoming;
            PsiStatement initialization = forStatement.getInitialization();
            if (initialization != null && !(initialization instanceof PsiEmptyStatement)) {
                afterInit = handleStatement(initialization, incoming);
            }
            boolean infinite = forStatement.getCondition() == null;
            String conditionLabel = infinite ? "true" : labelFrom(forStatement.getCondition(), "for (?)");
            Map<String, Object> extras = new HashMap<>();
            List<Map<String, Object>> inlineCalls = collectCalls(forStatement.getCondition());
            if (!inlineCalls.isEmpty()) {
                extras.put("inlineCalls", inlineCalls);
            }
            String headId = addNode(NodeType.LOOP_HEAD, conditionLabel, forStatement.getTextRange(), extras);
            String afterLoop = addNode(NodeType.MERGE, "", forStatement.getTextRange());
            link(afterInit, headId);

            loopStack.push(new LoopContext(headId, afterLoop));
            List<Endpoint> bodyExits = handleStatement(forStatement.getBody(), List.of(new Endpoint(headId, EdgeType.TRUE, "true")));
            loopStack.pop();

            PsiStatement update = forStatement.getUpdate();
            List<Endpoint> updateExits = bodyExits;
            if (update != null && !(update instanceof PsiEmptyStatement)) {
                updateExits = handleStatement(update, bodyExits);
            }
            link(updateExits, headId);
            edges.add(new Edge(headId, afterLoop, EdgeType.FALSE, "false"));
            return List.of(new Endpoint(afterLoop, EdgeType.NORMAL, null));
        }

        private List<Endpoint> handleForeach(PsiForeachStatement foreachStatement, List<Endpoint> incoming) {
            String headId = addNode(NodeType.LOOP_HEAD, "for (" + safeLabel(foreachStatement.getIterationParameter().getName()) + " : " + labelFrom(foreachStatement.getIteratedValue(), "?") + ")", foreachStatement.getTextRange());
            String afterLoop = addNode(NodeType.MERGE, "", foreachStatement.getTextRange());
            link(incoming, headId);

            loopStack.push(new LoopContext(headId, afterLoop));
            List<Endpoint> bodyExits = handleStatement(foreachStatement.getBody(), List.of(new Endpoint(headId, EdgeType.TRUE, "next")));
            loopStack.pop();

            link(bodyExits, headId);
            edges.add(new Edge(headId, afterLoop, EdgeType.FALSE, "done"));
            return List.of(new Endpoint(afterLoop, EdgeType.NORMAL, null));
        }

        private List<Endpoint> handleReturn(PsiReturnStatement returnStatement, List<Endpoint> incoming) {
            String label = returnStatement.getReturnValue() == null ? "return" : "return " + labelFrom(returnStatement.getReturnValue(), "?");
            PsiExpression exprRaw = returnStatement.getReturnValue();
            PsiExpression expr = unwrap(exprRaw);
            if (expr == null) {
                String returnId = addNode(NodeType.RETURN, label, returnStatement.getTextRange());
                link(incoming, returnId);
                connectReturnEndpoints(List.of(new Endpoint(returnId, EdgeType.NORMAL, null)));
                return List.of();
            }
            List<Endpoint> exits = buildReturnExpr(expr, incoming, returnStatement.getTextRange(), ternaryExpandLevel);
            connectReturnEndpoints(exits);
            return List.of();
        }

        private List<Endpoint> handleBreak(PsiStatement breakStatement, List<Endpoint> incoming) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("noFold", true);
            String breakId = addNode(NodeType.ACTION, "break", breakStatement.getTextRange(), meta);
            link(incoming, breakId);
            LoopContext loop = loopStack.peek();
            if (loop == null) {
                LOG.warn("break outside loop at " + safeLabel(breakStatement.getText()));
                return List.of(new Endpoint(breakId, EdgeType.NORMAL, null));
            }
            edges.add(new Edge(breakId, loop.breakTarget(), EdgeType.BREAK, "break"));
            return List.of();
        }

        private List<Endpoint> handleContinue(PsiStatement continueStatement, List<Endpoint> incoming) {
            String continueId = addNode(NodeType.ACTION, "continue", continueStatement.getTextRange());
            link(incoming, continueId);
            LoopContext loop = loopStack.peek();
            if (loop == null) {
                LOG.warn("continue outside loop at " + safeLabel(continueStatement.getText()));
                return List.of(new Endpoint(continueId, EdgeType.NORMAL, null));
            }
            edges.add(new Edge(continueId, loop.continueTarget(), EdgeType.CONTINUE, "continue"));
            return List.of();
        }

        private List<Endpoint> handleSwitch(PsiSwitchStatement switchStatement, List<Endpoint> incoming) {
            String switchId = addNode(NodeType.DECISION, "switch " + labelFrom(switchStatement.getExpression(), "?"), switchStatement.getTextRange());
            link(incoming, switchId);
            String mergeId = addNode(NodeType.MERGE, "end switch", switchStatement.getTextRange());
            PsiCodeBlock body = switchStatement.getBody();
            if (body == null) {
                edges.add(new Edge(switchId, mergeId, EdgeType.NORMAL, null));
                return List.of(new Endpoint(mergeId, EdgeType.NORMAL, null));
            }
            switchMergeStack.push(mergeId);
            List<CaseBlock> blocks = collectCaseBlocks(body);
            if (blocks.isEmpty()) {
                edges.add(new Edge(switchId, mergeId, EdgeType.NORMAL, null));
            } else {
                for (CaseBlock block : blocks) {
                    String caseId = addNode(NodeType.DECISION, block.label(), block.range());
                    edges.add(new Edge(switchId, caseId, EdgeType.NORMAL, null));
                    List<Endpoint> starts = List.of(new Endpoint(caseId, EdgeType.NORMAL, null));
                    List<Endpoint> exits = processStatements(block.statements(), starts);
                    boolean hasTerminal = blockHasTerminal(block.statements());
                    if (exits.isEmpty() && !hasTerminal) {
                        edges.add(new Edge(caseId, mergeId, EdgeType.NORMAL, null));
                    } else {
                        link(exits, mergeId);
                    }
                }
            }
            switchMergeStack.pop();
            addTypeLinkForSwitch(switchId, switchStatement.getExpression(), switchStatement.getTextRange());
            return List.of(new Endpoint(mergeId, EdgeType.NORMAL, null));
        }

        private List<Endpoint> handleThrow(PsiThrowStatement throwStatement, List<Endpoint> incoming) {
            String label = "throw " + labelFrom(throwStatement.getException(), "?");
            String throwId = addNode(NodeType.THROW, label, throwStatement.getTextRange());
            link(incoming, throwId);
            edges.add(new Edge(throwId, endId, EdgeType.EXCEPTION, null));
            if (!switchMergeStack.isEmpty()) {
                edges.add(new Edge(throwId, switchMergeStack.peek(), EdgeType.RETURN, "throw"));
            }
            return List.of();
        }

        private String labelText(PsiSwitchLabelStatementBase label) {
            if (label.isDefaultCase()) {
                return "default";
            }
            PsiExpressionList values = label.getCaseValues();
            if (values != null) {
                String joined = Arrays.stream(values.getExpressions())
                        .map(PsiElement::getText)
                        .collect(Collectors.joining(", "));
                if (!joined.isBlank()) {
                    return "case: " + safeLabel(joined);
                }
            }
            String text = label.getText();
            text = text.replace("case", "").replace(":", "").trim();
            if (!text.isEmpty()) {
                return "case: " + safeLabel(text);
            }
            return "?";
        }

        private List<CaseBlock> collectCaseBlocks(PsiCodeBlock body) {
            List<CaseBlock> blocks = new ArrayList<>();
            String currentLabel = null;
            List<PsiStatement> buffer = new ArrayList<>();
            TextRange currentRange = null;
            for (PsiStatement stmt : body.getStatements()) {
                if (stmt instanceof PsiSwitchLabelStatementBase label) {
                    if (currentLabel != null) {
                        blocks.add(new CaseBlock(currentLabel, new ArrayList<>(buffer), currentRange));
                    }
                    buffer.clear();
                    currentLabel = labelText(label);
                    currentRange = label.getTextRange();
                } else if (currentLabel != null) {
                    buffer.add(stmt);
                }
            }
            if (currentLabel != null) {
                blocks.add(new CaseBlock(currentLabel, buffer, currentRange));
            }
            return blocks;
        }

        private boolean blockHasTerminal(List<PsiStatement> statements) {
            for (PsiStatement stmt : statements) {
                if (containsTerminal(stmt)) {
                    return true;
                }
            }
            return false;
        }

        private boolean shouldSkipByRegex(PsiMethod method, java.util.List<String> patterns) {
            if (method == null || patterns == null || patterns.isEmpty()) {
                return false;
            }
            String methodName = method.getName();
            String qname = method.getContainingClass() != null ? method.getContainingClass().getQualifiedName() : null;
            String params = java.util.Arrays.stream(method.getParameterList().getParameters())
                    .map(p -> p.getType().getCanonicalText())
                    .collect(java.util.stream.Collectors.joining(","));
            String signature = (qname != null ? qname + "#" : "") + methodName + "(" + params + ")";
            for (String trimmed : patterns) {
                if (trimmed == null || trimmed.isBlank()) {
                    continue;
                }
                try {
                    if (signature.matches(trimmed)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            return false;
        }

        private boolean containsTerminal(PsiElement element) {
            if (element instanceof PsiReturnStatement || element instanceof PsiThrowStatement) {
                return true;
            }
            for (PsiElement child : element.getChildren()) {
                if (containsTerminal(child)) {
                    return true;
                }
            }
            return false;
        }

        private List<Endpoint> handleConditionalExpression(PsiConditionalExpression conditional, List<Endpoint> incoming, TextRange range, int expandDepth) {
            PsiExpression condition = unwrap(conditional.getCondition());
            Map<String, Object> extras = new HashMap<>();
            List<Map<String, Object>> inlineCalls = collectCalls(condition);
            if (!inlineCalls.isEmpty()) {
                extras.put("inlineCalls", inlineCalls);
            }
            String decisionId = addNode(NodeType.DECISION, labelFrom(condition, "?"), range, extras);
            link(incoming, decisionId);
            int nextDepth = expandDepth < 0 ? -1 : Math.max(0, expandDepth - 1);
            List<Endpoint> exits = new ArrayList<>();
            PsiExpression thenExpr = unwrap(conditional.getThenExpression());
            PsiExpression elseExpr = unwrap(conditional.getElseExpression());

            if (thenExpr instanceof PsiConditionalExpression nested && expandDepth != 0) {
                exits.addAll(handleConditionalExpression(nested, List.of(new Endpoint(decisionId, EdgeType.TRUE, "true")), thenExpr.getTextRange(), nextDepth));
            } else {
                String thenId = addNode(NodeType.ACTION, labelFrom(thenExpr, "?"), thenExpr != null ? thenExpr.getTextRange() : range);
                edges.add(new Edge(decisionId, thenId, EdgeType.TRUE, "true"));
                exits.add(new Endpoint(thenId, EdgeType.NORMAL, null));
            }

            if (elseExpr instanceof PsiConditionalExpression nestedElse && expandDepth != 0) {
                exits.addAll(handleConditionalExpression(nestedElse, List.of(new Endpoint(decisionId, EdgeType.FALSE, "false")), elseExpr.getTextRange(), nextDepth));
            } else {
                String elseId = addNode(NodeType.ACTION, labelFrom(elseExpr, "?"), elseExpr != null ? elseExpr.getTextRange() : range);
                edges.add(new Edge(decisionId, elseId, EdgeType.FALSE, "false"));
                exits.add(new Endpoint(elseId, EdgeType.NORMAL, null));
            }
            return exits;
        }

        private List<Endpoint> buildReturnExpr(PsiExpression expr, List<Endpoint> incoming, TextRange fallbackRange, int expandDepth) {
            expr = unwrap(expr);
            boolean expandTernary = expandDepth != 0;
            int nextDepth = expandDepth < 0 ? -1 : Math.max(0, expandDepth - 1);
            if (expr instanceof PsiSwitchExpression switchExpression) {
                return handleSwitchExpressionReturn(switchExpression, incoming, fallbackRange != null ? fallbackRange : expr.getTextRange(), nextDepth);
            }
            if (expr instanceof PsiConditionalExpression conditional && expandTernary) {
                PsiExpression condition = unwrap(conditional.getCondition());
                String decisionId = addNode(NodeType.DECISION, labelFrom(condition, "?"), conditional.getTextRange());
                link(incoming, decisionId);
                List<Endpoint> result = new ArrayList<>();
                PsiExpression thenExpr = unwrap(conditional.getThenExpression());
                PsiExpression elseExpr = unwrap(conditional.getElseExpression());
                if (thenExpr != null) {
                    result.addAll(buildReturnExpr(thenExpr, List.of(new Endpoint(decisionId, EdgeType.TRUE, "true")), thenExpr.getTextRange(), nextDepth));
                }
                if (elseExpr != null) {
                    result.addAll(buildReturnExpr(elseExpr, List.of(new Endpoint(decisionId, EdgeType.FALSE, "false")), elseExpr.getTextRange(), nextDepth));
                }
                return result;
            }
            if (expr instanceof PsiMethodCallExpression callExpression) {
                CallInfo call = buildCallInfo(callExpression);
                if (call != null) {
                    Map<String, Object> meta = call.meta();
                    List<Map<String, Object>> inlineCalls = collectCallsFromArguments(callExpression);
                    if (!inlineCalls.isEmpty()) {
                        meta = new HashMap<>(meta);
                        meta.put("inlineCalls", inlineCalls);
                    }
                    String callId = addNode(call.type(), "return " + call.label(), expr.getTextRange(), meta);
                    link(incoming, callId);
                    return List.of(new Endpoint(callId, EdgeType.NORMAL, null));
                }
            }
            Map<String, Object> meta = null;
            List<Map<String, Object>> inlineCalls = collectCalls(expr);
            if (!inlineCalls.isEmpty()) {
                meta = new HashMap<>();
                meta.put("inlineCalls", inlineCalls);
            }
            String returnId = addNode(NodeType.RETURN, "return " + labelFrom(expr, "?"), fallbackRange != null ? fallbackRange : expr.getTextRange(), meta);
            link(incoming, returnId);
            return List.of(new Endpoint(returnId, EdgeType.NORMAL, null));
        }

        private List<Map<String, Object>> collectCalls(PsiExpression expr) {
            if (expr == null) {
                return List.of();
            }
            List<Map<String, Object>> calls = new ArrayList<>();
            expr.accept(new JavaRecursiveElementWalkingVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);
                    CallInfo call = buildCallInfo(expression);
                    if (call != null && !Boolean.TRUE.equals(call.meta().get("skipCallRender"))) {
                        Map<String, Object> meta = new HashMap<>(call.meta());
                        meta.put("label", call.label());
                        calls.add(meta);
                    }
                }
            });
            return calls;
        }

        private List<Map<String, Object>> collectCallsFromArguments(PsiMethodCallExpression callExpression) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (PsiExpression arg : callExpression.getArgumentList().getExpressions()) {
                calls.addAll(collectCalls(arg));
            }
            return calls;
        }

        private String ruleLabel(PsiSwitchLabeledRuleStatement rule) {
            PsiElement first = rule.getFirstChild();
            String firstText = first != null ? first.getText() : "";
            if ("default".equals(firstText)) {
                return "default";
            }
            if ("case".equals(firstText)) {
                String raw = rule.getText();
                int arrow = raw.indexOf("->");
                if (arrow > 4) {
                    String mid = raw.substring(4, arrow).replace(":", "").trim();
                    if (!mid.isEmpty()) {
                        return "case: " + safeLabel(mid);
                    }
                }
                return "case";
            }
            return "case";
        }

        private void addTypeLinkForSwitch(String switchId, PsiExpression switchExpr, TextRange range) {
            String kind = typeKind(switchExpr.getType());
            if (kind == null) {
                return;
            }
            String typeName = switchExpr.getType() != null ? safeLabel(switchExpr.getType().getPresentableText()) : "type";
            Map<String, Object> meta = new HashMap<>();
            meta.put("noFold", true);
            String typeNode = addNode(NodeType.ACTION, typeName, range, meta);
            edges.add(new Edge(switchId, typeNode, EdgeType.RETURN, kind));
        }

        private String typeKind(PsiType type) {
            if (type instanceof PsiClassType ct) {
                PsiClass cls = ct.resolve();
                if (cls != null) {
                    if (cls.isEnum()) {
                        return "enum";
                    }
                    if (cls.hasModifierProperty("sealed")) {
                        return "sealed";
                    }
                }
            }
            return null;
        }

        private List<Endpoint> handleSwitchExpressionReturn(PsiSwitchExpression switchExpression, List<Endpoint> incoming, TextRange range, int nextDepth) {
            SwitchGraph sg = buildSwitchGraph(switchExpression, nextDepth, range);
            String returnId = addNode(NodeType.RETURN, "return switch", range);
            // 返回节点直接接在上游语句后面，虚线指向 switch 以示取值来源
            link(incoming, returnId);
            edges.add(new Edge(returnId, sg.switchId(), EdgeType.RETURN, "switch"));
            return List.of(new Endpoint(returnId, EdgeType.NORMAL, null));
        }

        private record SwitchGraph(String switchId, String mergeId) {
        }

        private SwitchGraph buildSwitchGraph(PsiSwitchExpression switchExpression, int nextDepth, TextRange range) {
            String switchId = addNode(NodeType.DECISION, "switch " + labelFrom(switchExpression.getExpression(), "?"), range);
            String mergeId = addNode(NodeType.MERGE, "end switch", range);

            PsiCodeBlock body = switchExpression.getBody();
            if (body != null) {
                for (PsiStatement stmt : body.getStatements()) {
                    if (stmt instanceof PsiSwitchLabeledRuleStatement rule) {
                        String caseLabel = ruleLabel(rule);
                        String caseId = addNode(NodeType.DECISION, caseLabel, stmt.getTextRange());
                        edges.add(new Edge(switchId, caseId, EdgeType.NORMAL, null));
                        List<Endpoint> starts = List.of(new Endpoint(caseId, EdgeType.NORMAL, null));
                        List<Endpoint> exits = handleSwitchRule(rule, starts, nextDepth);
                        link(exits, mergeId);
                    } else if (stmt instanceof PsiSwitchLabelStatementBase label) {
                        String caseId = addNode(NodeType.DECISION, labelText(label), label.getTextRange());
                        edges.add(new Edge(switchId, caseId, EdgeType.NORMAL, null));
                        link(List.of(new Endpoint(caseId, EdgeType.NORMAL, null)), mergeId);
                    }
                }
            }
            addTypeLinkForSwitch(switchId, switchExpression.getExpression(), range);
            return new SwitchGraph(switchId, mergeId);
        }

        private List<Endpoint> handleSwitchRule(PsiSwitchLabeledRuleStatement rule, List<Endpoint> incoming, int nextDepth) {
            PsiStatement body = rule.getBody();
            if (body instanceof PsiExpressionStatement exprStmt) {
                PsiExpression expr = exprStmt.getExpression();
                if (expr instanceof PsiConditionalExpression conditional && nextDepth != 0) {
                    return handleConditionalExpression(conditional, incoming, expr.getTextRange(), nextDepth);
                }
                String actionId = addNode(NodeType.ACTION, safeLabel(expr.getText()), exprStmt.getTextRange());
                link(incoming, actionId);
                return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
            }
            if (body instanceof PsiBlockStatement blockStmt) {
                PsiCodeBlock block = blockStmt.getCodeBlock();
                return withNoFold(() -> processStatements(Arrays.asList(block.getStatements()), incoming));
            }
            if (body instanceof PsiYieldStatement yield) {
                PsiExpression expr = yield.getExpression();
                if (expr instanceof PsiConditionalExpression conditional && nextDepth != 0) {
                    return handleConditionalExpression(conditional, incoming, expr.getTextRange(), nextDepth);
                }
                String actionId = addNode(NodeType.ACTION, "yield " + labelFrom(expr, "?"), yield.getTextRange());
                link(incoming, actionId);
                return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
            }
            String actionId = addNode(NodeType.ACTION, safeLabel(rule.getText()), rule.getTextRange());
            link(incoming, actionId);
            return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
        }

        private PsiExpression unwrap(PsiExpression expr) {
            PsiExpression current = expr;
            while (current instanceof PsiParenthesizedExpression paren && paren.getExpression() != null) {
                current = paren.getExpression();
            }
            return current;
        }

        private void connectReturnEndpoints(List<Endpoint> exits) {
            for (Endpoint ep : exits) {
                edges.add(new Edge(ep.from(), endId, EdgeType.NORMAL, null));
                if (!switchMergeStack.isEmpty()) {
                    edges.add(new Edge(ep.from(), switchMergeStack.peek(), EdgeType.RETURN, ep.label() != null ? ep.label() : "return"));
                }
            }
        }

        private record CaseBlock(String label, List<PsiStatement> statements, TextRange range) {
        }

        private List<Endpoint> handleTry(PsiTryStatement tryStatement, List<Endpoint> incoming) {
            String tryId = addNode(NodeType.ACTION, "try", tryStatement.getTryBlock() != null ? tryStatement.getTryBlock().getTextRange() : tryStatement.getTextRange());
            link(incoming, tryId);
            List<Endpoint> normalExit = tryStatement.getTryBlock() != null
                    ? processStatements(Arrays.asList(tryStatement.getTryBlock().getStatements()), List.of(new Endpoint(tryId, EdgeType.NORMAL, null)))
                    : List.of(new Endpoint(tryId, EdgeType.NORMAL, null));

            List<Endpoint> catchExits = new ArrayList<>();
            for (PsiCatchSection catchSection : tryStatement.getCatchSections()) {
                String catchId = addNode(NodeType.ACTION, "catch (" + safeLabel(catchSection.getParameter() != null ? catchSection.getParameter().getType().getPresentableText() : "?") + ")", catchSection.getTextRange());
                edges.add(new Edge(tryId, catchId, EdgeType.EXCEPTION, "exception"));
                List<Endpoint> bodyExits = catchSection.getCatchBlock() != null
                        ? processStatements(Arrays.asList(catchSection.getCatchBlock().getStatements()), List.of(new Endpoint(catchId, EdgeType.NORMAL, null)))
                        : List.of(new Endpoint(catchId, EdgeType.NORMAL, null));
                catchExits.addAll(bodyExits);
            }

            List<Endpoint> finallyExits = new ArrayList<>();
            PsiCodeBlock finallyBlock = tryStatement.getFinallyBlock();
            if (finallyBlock != null) {
                List<Endpoint> sources = new ArrayList<>();
                sources.addAll(normalExit);
                sources.addAll(catchExits);
                String finallyId = addNode(NodeType.ACTION, "finally", finallyBlock.getTextRange());
                link(sources, finallyId);
                finallyExits.addAll(processStatements(Arrays.asList(finallyBlock.getStatements()), List.of(new Endpoint(finallyId, EdgeType.NORMAL, null))));
            } else {
                finallyExits.addAll(normalExit);
                finallyExits.addAll(catchExits);
            }
            return finallyExits;
        }

        private List<Endpoint> handleAction(PsiStatement statement, List<Endpoint> incoming) {
            String label = safeLabel(statement.getText());
            NodeType type = NodeType.ACTION;
            Map<String, Object> meta = null;
            if (statement instanceof PsiDeclarationStatement decl) {
                for (PsiElement elem : decl.getDeclaredElements()) {
                    if (elem instanceof PsiLocalVariable var) {
                        PsiExpression init = var.getInitializer();
                        if (init instanceof PsiMethodCallExpression initCall && (!foldFluentCalls && !foldNestedCalls)) {
                            String lhs = var.getType().getPresentableText() + " " + var.getName() + " = ...";
                            String lhsId = addNode(NodeType.ACTION, lhs, decl.getTextRange());
                            link(incoming, lhsId);
                            return handleMethodCallUnfoldWithNested(initCall, List.of(new Endpoint(lhsId, EdgeType.NORMAL, null)), decl.getTextRange(), lhsId, false, true);
                        } else if (init instanceof PsiMethodCallExpression initCall && (!foldNestedCalls)) {
                            String lhs = var.getType().getPresentableText() + " " + var.getName() + " = ...";
                            String lhsId = addNode(NodeType.ACTION, lhs, decl.getTextRange());
                            link(incoming, lhsId);
                            return handleMethodCallUnfoldWithNested(initCall, List.of(new Endpoint(lhsId, EdgeType.NORMAL, null)), decl.getTextRange(), lhsId, false, false);
                        } else if (init instanceof PsiMethodCallExpression initCall && (!foldFluentCalls)) {
                            String lhs = var.getType().getPresentableText() + " " + var.getName() + " = ...";
                            String lhsId = addNode(NodeType.ACTION, lhs, decl.getTextRange());
                            link(incoming, lhsId);
                            return handleMethodCallUnfoldForFluent(initCall, List.of(new Endpoint(lhsId, EdgeType.NORMAL, null)), decl.getTextRange(), lhsId, false);
                        }
                        if (containsGetter(init)) {
                            meta = meta == null ? new HashMap<>() : meta;
                            meta.put("isGetter", true);
                        }
                        if (containsCtor(init)) {
                            meta = meta == null ? new HashMap<>() : meta;
                            meta.put("isCtor", true);
                        }
                        int depth = ternaryExpandLevel;
                        if (init instanceof PsiConditionalExpression cond && depth != 0) {
                            String lhsLabel = var.getType().getPresentableText() + " " + var.getName() + " = ...";
                            String lhsId = addNode(NodeType.ACTION, lhsLabel, decl.getTextRange());
                            return handleConditionalExpression(cond, List.of(new Endpoint(lhsId, EdgeType.RETURN, "=")), decl.getTextRange(), depth);
                        }
                        if (init instanceof PsiSwitchExpression switchExpr) {
                            SwitchGraph sg = buildSwitchGraph(switchExpr, ternaryExpandLevel, init.getTextRange());
                            String declLabel = var.getType().getPresentableText() + " " + var.getName() + " = switch";
                            String actionId = addNode(NodeType.ACTION, declLabel, decl.getTextRange());
                            link(incoming, actionId);
                            edges.add(new Edge(actionId, sg.switchId(), EdgeType.RETURN, "switch"));
                            return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
                        }
                        List<Map<String, Object>> inlineCalls = collectCalls(init);
                        if (!inlineCalls.isEmpty()) {
                            meta = meta == null ? new HashMap<>() : meta;
                            meta.put("inlineCalls", inlineCalls);
                        }
                    }
                }
            }
            if (statement instanceof PsiExpressionStatement exprStmt) {
                PsiExpression expression = exprStmt.getExpression();
                if (expression instanceof PsiConditionalExpression conditional) {
                    int depth = ternaryExpandLevel;
                    if (depth != 0) {
                        return handleConditionalExpression(conditional, incoming, statement.getTextRange(), depth);
                    }
                    String actionId = addNode(NodeType.ACTION, safeLabel(expression.getText()), statement.getTextRange());
                    link(incoming, actionId);
                    return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
                }
                if (expression instanceof PsiAssignmentExpression assign && assign.getRExpression() instanceof PsiConditionalExpression cond) {
                    int depth = ternaryExpandLevel;
                    if (depth != 0) {
                        String lhsLabel = safeLabel(assign.getLExpression().getText()) + " = ...";
                        String lhsId = addNode(NodeType.ACTION, lhsLabel, statement.getTextRange());
                        return handleConditionalExpression(cond, List.of(new Endpoint(lhsId, EdgeType.RETURN, "=")), statement.getTextRange(), depth);
                    }
                }
                if (expression instanceof PsiAssignmentExpression assign2) {
                    // unfold nested calls on RHS（在需要时展开：若未折叠嵌套或需要收集调用信息）
                    PsiExpression rhs = assign2.getRExpression();
                    if (rhs instanceof PsiMethodCallExpression rhsCall) {
                        if (!foldFluentCalls && !foldNestedCalls) {
                            String lhsLabel = safeLabel(assign2.getLExpression().getText()) + " = ...";
                            String lhsId = addNode(NodeType.ACTION, lhsLabel, statement.getTextRange());
                            link(incoming, lhsId);
                            return handleMethodCallUnfoldWithNested(rhsCall, List.of(new Endpoint(lhsId, EdgeType.NORMAL, null)), statement.getTextRange(), lhsId, false, true);
                        } else if (!foldNestedCalls) {
                            String lhsLabel = safeLabel(assign2.getLExpression().getText()) + " = ...";
                            String lhsId = addNode(NodeType.ACTION, lhsLabel, statement.getTextRange());
                            link(incoming, lhsId);
                            return handleMethodCallUnfoldWithNested(rhsCall, List.of(new Endpoint(lhsId, EdgeType.NORMAL, null)), statement.getTextRange(), lhsId, false, false);
                        } else if (!foldFluentCalls) {
                            String lhsLabel = safeLabel(assign2.getLExpression().getText()) + " = ...";
                            String lhsId = addNode(NodeType.ACTION, lhsLabel, statement.getTextRange());
                            link(incoming, lhsId);
                            return handleMethodCallUnfoldForFluent(rhsCall, List.of(new Endpoint(lhsId, EdgeType.NORMAL, null)), statement.getTextRange(), lhsId, false);
                        }
                        String lhsLabel = safeLabel(assign2.getLExpression().getText()) + " = ...";
                        String lhsId = addNode(NodeType.ACTION, lhsLabel, statement.getTextRange());
                        link(incoming, lhsId);
                        List<Endpoint> current = List.of(new Endpoint(lhsId, EdgeType.NORMAL, null));
                        CallInfo call = buildCallInfo(rhsCall);
                        if (call == null) {
                            return incoming;
                        }
                        String callId = addNode(call.type(), call.label(), rhsCall.getTextRange(), call.meta());
                        link(current, callId);
                        return List.of(new Endpoint(callId, EdgeType.NORMAL, null));
                    }
                    if (containsGetter(assign2.getRExpression())) {
                        meta = meta == null ? new HashMap<>() : meta;
                        meta.put("isGetter", true);
                    }
                }
                List<Map<String, Object>> metaFromChainInline = new ArrayList<>();
                if (expression instanceof PsiMethodCallExpression callExpression) {
                    if (!foldFluentCalls && !foldNestedCalls) {
                        return handleMethodCallUnfoldWithNested(callExpression, incoming, statement.getTextRange(), null, true, true);
                    } else if (!foldNestedCalls) {
                        return handleMethodCallUnfoldWithNested(callExpression, incoming, statement.getTextRange(), null, true, false);
                    } else if (!foldFluentCalls) {
                        String baseLabel = safeLabel(expression.getText());
                        String baseId = addNode(NodeType.ACTION, baseLabel, statement.getTextRange());
                        link(incoming, baseId);
                        return handleMethodCallUnfoldForFluent(callExpression, List.of(new Endpoint(baseId, EdgeType.NORMAL, null)), statement.getTextRange(), baseId, true);
                    }
                    // 处理链式调用：未折叠时拆分，折叠时仍收集全部调用信息到 inlineCalls
                    {
                        List<PsiMethodCallExpression> chain = new ArrayList<>();
                        PsiMethodCallExpression cur = callExpression;
                        while (cur != null) {
                            chain.add(0, cur); // insert at front to get root-first order
                            PsiExpression q = cur.getMethodExpression().getQualifierExpression();
                            if (q instanceof PsiMethodCallExpression qm) {
                                cur = qm;
                            } else {
                                cur = null;
                            }
                        }
                        // 折叠链式调用时，收集整个链的调用信息为 inlineCalls，保留主节点
                        if (chain.size() > 1) {
                            List<Map<String, Object>> chainInline = new ArrayList<>();
                            for (PsiMethodCallExpression mc : chain) {
                                CallInfo ci = buildCallInfo(mc);
                                if (ci == null) {
                                    continue;
                                }
                                Map<String, Object> m = new HashMap<>(ci.meta());
                                List<Map<String, Object>> inlineCalls = collectCallsFromArguments(mc);
                                if (!inlineCalls.isEmpty()) {
                                    m.put("inlineCalls", inlineCalls);
                                }
                                chainInline.add(m);
                            }
                            // merge into the main call meta below
                            metaFromChainInline.addAll(chainInline);
                        }
                    }
                    // 嵌套调用：未折叠则拆分，折叠时把调用信息写入 inlineCalls
                    List<Map<String, Object>> foldedNestedInline = new ArrayList<>();
                    List<PsiMethodCallExpression> nestedCalls = collectNestedCalls(callExpression);
                    if (nestedCalls.size() > 1) {
                        for (PsiMethodCallExpression mc : nestedCalls) {
                            CallInfo ci = buildCallInfo(mc);
                            if (ci == null) continue;
                            Map<String, Object> m = new HashMap<>(ci.meta());
                            List<Map<String, Object>> inlineCalls = collectCallsFromArguments(mc);
                            if (!inlineCalls.isEmpty()) {
                                m.put("inlineCalls", inlineCalls);
                            }
                            foldedNestedInline.add(m);
                        }
                    }
                    CallInfo call = buildCallInfo(callExpression);
                    if (call != null) {
                        type = call.type();
                        label = call.label();
                        meta = call.meta() != null ? new HashMap<>(call.meta()) : new HashMap<>();

                        List<Map<String, Object>> mergedInline = new ArrayList<>();
                        Object existingInline = meta.get("inlineCalls");
                        if (existingInline instanceof List<?>) {
                            for (Object o : (List<?>) existingInline) {
                                if (o instanceof Map<?, ?> m) {
                                    mergedInline.add((Map<String, Object>) m);
                                }
                            }
                        }
                        List<Map<String, Object>> inlineCalls = collectCallsFromArguments(callExpression);
                        if (!inlineCalls.isEmpty()) {
                            mergedInline.addAll(inlineCalls);
                        }
                        if (!foldedNestedInline.isEmpty()) {
                            mergedInline.addAll(foldedNestedInline);
                        }
                        if (!metaFromChainInline.isEmpty()) {
                            mergedInline.addAll(metaFromChainInline);
                        }

                        List<PsiMethodCallExpression> evalOrder = collectNestedCalls(callExpression);
                        List<Map<String, Object>> orderedInline = new ArrayList<>();
                        for (int i = 0; i < evalOrder.size() - 1; i++) { // skip outermost
                            PsiMethodCallExpression mc = evalOrder.get(i);
                            CallInfo ci = buildCallInfo(mc);
                            if (ci == null || Boolean.TRUE.equals(ci.meta().get("skipCallRender"))) {
                                continue;
                            }
                            Map<String, Object> m = new HashMap<>(ci.meta());
                            m.put("label", ci.label());
                            List<Map<String, Object>> argCalls = collectCallsFromArguments(mc);
                            if (!argCalls.isEmpty()) {
                                m.put("inlineCalls", argCalls);
                            }
                            orderedInline.add(m);
                        }
                        if (!orderedInline.isEmpty()) {
                            mergedInline = orderedInline;
                        }

                        if (!mergedInline.isEmpty()) {
                            meta.put("inlineCalls", mergedInline);
                        }
                    } else {
                        return incoming;
                    }
                } else if (expression instanceof PsiAssignmentExpression assign && assign.getRExpression() instanceof PsiSwitchExpression) {
                    SwitchGraph sg = buildSwitchGraph((PsiSwitchExpression) assign.getRExpression(), ternaryExpandLevel, expression.getTextRange());
                    String lhs = safeLabel(assign.getLExpression().getText());
                    String actionId = addNode(NodeType.ACTION, lhs + " = switch", statement.getTextRange());
                    link(incoming, actionId);
                    edges.add(new Edge(actionId, sg.switchId(), EdgeType.RETURN, "switch"));
                    return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
                } else if (expression instanceof PsiSwitchExpression switchExpr) {
                    SwitchGraph sg = buildSwitchGraph(switchExpr, ternaryExpandLevel, expression.getTextRange());
                    String actionId = addNode(NodeType.ACTION, "switch", statement.getTextRange());
                    link(incoming, actionId);
                    edges.add(new Edge(actionId, sg.switchId(), EdgeType.RETURN, "switch"));
                    return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
                } else {
                    if (containsCtor(expression)) {
                        meta = meta == null ? new HashMap<>() : meta;
                        meta.put("isCtor", true);
                    }
                    List<Map<String, Object>> inlineCalls = collectCalls(expression);
                    if (!inlineCalls.isEmpty()) {
                        meta = new HashMap<>();
                        meta.put("inlineCalls", inlineCalls);
                    }
                }
            }
            String actionId = addNode(type, label, statement.getTextRange(), meta);
            link(incoming, actionId);
            return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
        }

        private List<Endpoint> handleMethodCallUnfoldForFluent(PsiMethodCallExpression root,
                                                               List<Endpoint> incoming,
                                                               TextRange range,
                                                               String baseId,
                                                               boolean replaceAnchorLabelWhenNoEllipsis) {
            String anchorId = baseId;
            if (anchorId == null) {
                anchorId = addNode(NodeType.ACTION, safeLabel(root.getText()), range);
                link(incoming, anchorId);
            } else {
                boolean alreadyLinked = false;
                for (Endpoint e : incoming) {
                    if (anchorId.equals(e.from())) {
                        alreadyLinked = true;
                        break;
                    }
                }
                if (!alreadyLinked) {
                    link(incoming, anchorId);
                }
            }

            boolean includeFluent = !foldFluentCalls;
            CallGraph graph = collectCallGraphForFluent(root, includeFluent);
            java.util.Map<PsiMethodCallExpression, String> idMap = new java.util.LinkedHashMap<>();
            java.util.Set<PsiMethodCallExpression> remaining = new java.util.LinkedHashSet<>(graph.order());
            java.util.List<PsiMethodCallExpression> creationOrder = new java.util.ArrayList<>();
            for (PsiMethodCallExpression call : collectNestedCalls(root)) {
                if (remaining.remove(call)) {
                    creationOrder.add(call);
                }
            }
            creationOrder.addAll(remaining); // any call not covered by nested traversal
            CallInfo mergedIntoAnchor = null;
            boolean markChainSplit = includeFluent && (graph.order().size() > 1
                    || graph.relations().stream().anyMatch(r -> r.type() == CallRelationType.FLUENT));
            for (PsiMethodCallExpression call : creationOrder) {
                CallInfo info = buildCallInfo(call);
                if (info == null) {
                    continue;
                }
                Map<String, Object> meta = info.meta() != null ? new HashMap<>(info.meta()) : new HashMap<>();
                if (markChainSplit) {
                    meta.put("chainSplit", true);
                }
                List<Map<String, Object>> inlineCalls = collectCallsFromArguments(call);
                if (!inlineCalls.isEmpty()) {
                    meta.put("inlineCalls", inlineCalls);
                }
                if (mergedIntoAnchor == null && includeFluent) {
                    mergedIntoAnchor = new CallInfo(info.type(), info.label(), meta);
                    continue; // first call merged into anchor node
                }
                String labelText = info.label();
                if (includeFluent) {
                    labelText = shortCallLabel(call);
                }
                if (includeFluent && !labelText.startsWith("...")) {
                    labelText = "..." + labelText;
                }
                String id = addNode(info.type(), labelText, call.getTextRange(), meta);
                idMap.put(call, id);
            }
            if (mergedIntoAnchor != null) {
                mergeFirstCallIntoAnchor(anchorId, mergedIntoAnchor, replaceAnchorLabelWhenNoEllipsis);
            }

            if (idMap.isEmpty()) {
                return List.of(new Endpoint(anchorId, EdgeType.NORMAL, null));
            }

            java.util.Map<PsiMethodCallExpression, CallRelationType> firstRelationType = new java.util.HashMap<>();
            for (CallRelation relation : graph.relations()) {
                firstRelationType.putIfAbsent(relation.child(), relation.type());
            }

            java.util.List<PsiMethodCallExpression> evalOrder = collectNestedCalls(root).stream()
                    .filter(idMap::containsKey)
                    .filter(c -> includeFluent)
                    .toList();
            if (evalOrder.isEmpty()) {
                evalOrder = idMap.keySet().stream().toList();
            }
            int fluentIdx = 0;
            int nestedIdx = 0;
            for (int i = 0; i < evalOrder.size(); i++) {
                PsiMethodCallExpression call = evalOrder.get(i);
                CallRelationType relType = firstRelationType.getOrDefault(call, CallRelationType.FLUENT);
                String labelText;
                if (relType == CallRelationType.NESTED) {
                    nestedIdx++;
                    labelText = "nested:" + nestedIdx;
                } else {
                    fluentIdx++;
                    labelText = "fluent:" + fluentIdx;
                }
                String from = i == 0 ? anchorId : idMap.get(evalOrder.get(i - 1));
                String to = idMap.get(call);
                if (from != null && to != null) {
                    edges.add(new Edge(from, to, EdgeType.NORMAL, labelText));
                }
            }

            return List.of(new Endpoint(anchorId, EdgeType.NORMAL, null));
        }

        private List<Endpoint> handleMethodCallUnfoldWithNested(PsiMethodCallExpression root,
                                                                List<Endpoint> incoming,
                                                                TextRange range,
                                                                String baseId,
                                                                boolean replaceAnchorLabelWhenNoEllipsis,
                                                                boolean expandFluent) {
            ChainBuild chain = buildChainWithNested(root, incoming, range, baseId, replaceAnchorLabelWhenNoEllipsis, expandFluent);
            Map<PsiMethodCallExpression, String> nodeMap = new java.util.LinkedHashMap<>(chain.nodeIds());
            java.util.Set<PsiMethodCallExpression> visiting = new java.util.HashSet<>();
            for (Map.Entry<PsiMethodCallExpression, String> entry : chain.nodeIds().entrySet()) {
                unfoldNestedArguments(entry.getKey(), entry.getValue(), expandFluent, nodeMap, visiting);
            }
            return List.of(new Endpoint(chain.anchorId(), EdgeType.NORMAL, null));
        }

        private ChainBuild buildChainWithNested(PsiMethodCallExpression root,
                                                List<Endpoint> incoming,
                                                TextRange range,
                                                String baseId,
                                                boolean replaceAnchorLabelWhenNoEllipsis,
                                                boolean expandFluent) {
            List<PsiMethodCallExpression> chain = collectFluentChain(root);
            Map<PsiMethodCallExpression, String> nodeIds = new java.util.LinkedHashMap<>();

            if (!expandFluent) {
                String label = collapsedChainLabel(chain);
                String anchorId = baseId;
                Map<String, Object> extras = new HashMap<>();
                String finalLabel = label;
                if (anchorId != null) {
                    Node existing = findNode(anchorId);
                    if (existing != null) {
                        finalLabel = mergeAnchorLabel(existing.label(), label, true);
                    }
                }
                if (anchorId == null) {
                    anchorId = addNode(NodeType.ACTION, finalLabel, range, extras);
                    link(incoming, anchorId);
                } else {
                    updateNode(anchorId, finalLabel, extras);
                }
                for (PsiMethodCallExpression call : chain) {
                    nodeIds.put(call, anchorId);
                }
                return new ChainBuild(anchorId, nodeIds);
            }

            String chainTag = "chain-" + System.identityHashCode(root);
            boolean markChainSplit = chain.size() > 1;
            String anchorId = baseId;
            String previous = null;
            for (int i = 0; i < chain.size(); i++) {
                PsiMethodCallExpression call = chain.get(i);
                CallInfo info = buildCallInfo(call);
                if (info == null) {
                    continue;
                }
                boolean hasNested = hasNestedMethodCall(call);
                Map<String, Object> meta = info.meta() != null ? new HashMap<>(info.meta()) : new HashMap<>();
                if (markChainSplit) {
                    meta.put("chainSplit", true);
                    meta.put("fluentChainId", chainTag);
                }
                String label = callLabelForChain(call, hasNested, i > 0);
                if (i == 0 && anchorId != null) {
                    CallInfo merged = new CallInfo(info.type(), label, meta);
                    mergeFirstCallIntoAnchor(anchorId, merged, replaceAnchorLabelWhenNoEllipsis || hasNested);
                    nodeIds.put(call, anchorId);
                    previous = anchorId;
                    continue;
                }
                String id = addNode(info.type(), label, call.getTextRange(), meta);
                if (anchorId == null) {
                    anchorId = id;
                    link(incoming, anchorId);
                } else if (previous != null) {
                    edges.add(new Edge(previous, id, EdgeType.NORMAL, "fluent"));
                }
                nodeIds.put(call, id);
                previous = id;
            }
            if (anchorId == null) {
                String fallbackLabel = callLabelForChain(root, hasNestedMethodCall(root), false);
                anchorId = addNode(NodeType.ACTION, fallbackLabel, range, Map.of());
                link(incoming, anchorId);
                nodeIds.put(root, anchorId);
            }
            return new ChainBuild(anchorId, nodeIds);
        }

        private void unfoldNestedArguments(PsiMethodCallExpression parent,
                                           String parentId,
                                           boolean expandFluent,
                                           Map<PsiMethodCallExpression, String> created,
                                           java.util.Set<PsiMethodCallExpression> visiting) {
            if (parent == null || parentId == null) {
                return;
            }
            if (!visiting.add(parent)) {
                return;
            }
            for (PsiExpression arg : parent.getArgumentList().getExpressions()) {
                for (PsiMethodCallExpression nested : collectTopLevelMethodCalls(arg)) {
                    String nestedId = created.get(nested);
                    if (nestedId == null) {
                        ChainBuild nestedChain = buildChainWithNested(nested, List.of(), nested.getTextRange(), null, false, expandFluent);
                        nestedId = nestedChain.anchorId();
                        nestedChain.nodeIds().forEach(created::putIfAbsent);
                    }
                    edges.add(new Edge(nestedId, parentId, EdgeType.NORMAL, "nested"));
                    unfoldNestedArguments(nested, nestedId, expandFluent, created, visiting);
                }
            }
        }

        private List<PsiMethodCallExpression> collectFluentChain(PsiMethodCallExpression root) {
            List<PsiMethodCallExpression> chain = new ArrayList<>();
            PsiMethodCallExpression current = root;
            while (current != null) {
                chain.add(0, current);
                PsiExpression q = current.getMethodExpression().getQualifierExpression();
                if (q instanceof PsiMethodCallExpression qm) {
                    current = qm;
                } else {
                    current = null;
                }
            }
            return chain;
        }

        private List<PsiMethodCallExpression> collectTopLevelMethodCalls(PsiExpression expr) {
            List<PsiMethodCallExpression> calls = new ArrayList<>();
            if (expr == null) {
                return calls;
            }
            expr.accept(new JavaRecursiveElementWalkingVisitor() {
                int depth = 0;

                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    if (depth == 0) {
                        calls.add(expression);
                    }
                    depth++;
                    super.visitMethodCallExpression(expression);
                    depth--;
                }
            });
            return calls;
        }

        private boolean hasNestedMethodCall(PsiMethodCallExpression call) {
            if (call == null) {
                return false;
            }
            final boolean[] found = {false};
            for (PsiExpression arg : call.getArgumentList().getExpressions()) {
                arg.accept(new JavaRecursiveElementWalkingVisitor() {
                    @Override
                    public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                        found[0] = true;
                    }
                });
                if (found[0]) {
                    break;
                }
            }
            return found[0];
        }

        private String callLabelForChain(PsiMethodCallExpression call, boolean maskArgs, boolean prefixEllipsis) {
            String name = call.getMethodExpression().getReferenceName();
            if (name == null || name.isBlank()) {
                name = safeLabel(call.getMethodExpression().getText());
            }
            String args = call.getArgumentList().getText();
            if (maskArgs) {
                args = "(...)";
            } else if (args == null || args.isBlank()) {
                args = "()";
            }
            String label = name + args;
            if (prefixEllipsis && !label.startsWith("...")) {
                label = "..." + label;
            }
            return label;
        }

        private String collapsedChainLabel(List<PsiMethodCallExpression> chain) {
            return chain.stream()
                    .map(call -> callLabelForChain(call, hasNestedMethodCall(call), false))
                    .collect(Collectors.joining("."));
        }

        private void updateNode(String nodeId, String newLabel, Map<String, Object> extraMeta) {
            if (nodeId == null) {
                return;
            }
            for (int i = 0; i < nodes.size(); i++) {
                Node n = nodes.get(i);
                if (!nodeId.equals(n.id())) {
                    continue;
                }
                Map<String, Object> meta = new HashMap<>(n.meta());
                if (extraMeta != null) {
                    meta.putAll(extraMeta);
                }
                nodes.set(i, new Node(n.id(), n.type(), newLabel != null ? newLabel : n.label(), meta));
                return;
            }
        }

        private Node findNode(String id) {
            if (id == null) {
                return null;
            }
            for (Node n : nodes) {
                if (id.equals(n.id())) {
                    return n;
                }
            }
            return null;
        }

        private void mergeFirstCallIntoAnchor(String anchorId, CallInfo firstCall, boolean replaceAnchorLabelWhenNoEllipsis) {
            Node anchorNode = null;
            int anchorIndex = -1;
            for (int i = 0; i < nodes.size(); i++) {
                if (anchorId.equals(nodes.get(i).id())) {
                    anchorNode = nodes.get(i);
                    anchorIndex = i;
                    break;
                }
            }
            if (anchorNode == null) {
                return;
            }
            Map<String, Object> mergedMeta = new HashMap<>(anchorNode.meta());
            Map<String, Object> callMeta = firstCall.meta() != null ? new HashMap<>(firstCall.meta()) : Map.of();
            // keep existing inlineCalls, append the first call so call-chain仍可渲染
            List<Map<String, Object>> inline = copyInline(mergedMeta.get("inlineCalls"));
            if (!callMeta.isEmpty()) {
                inline.add(callMeta);
            }
            if (!inline.isEmpty()) {
                mergedMeta.put("inlineCalls", inline);
            }
            if (Boolean.TRUE.equals(callMeta.get("chainSplit")) || Boolean.TRUE.equals(mergedMeta.get("chainSplit"))) {
                mergedMeta.put("chainSplit", true);
            }
            String mergedLabel = mergeAnchorLabel(anchorNode.label(), firstCall.label(), replaceAnchorLabelWhenNoEllipsis);
            Node mergedNode = new Node(anchorNode.id(), anchorNode.type(), mergedLabel, mergedMeta);
            nodes.set(anchorIndex, mergedNode);
        }

        private String mergeAnchorLabel(String anchorLabel, String callLabel, boolean replaceWhenNoEllipsis) {
            if (anchorLabel == null || anchorLabel.isBlank()) {
                return callLabel;
            }
            if (callLabel == null || callLabel.isBlank()) {
                return anchorLabel;
            }
            if (anchorLabel.contains("...")) {
                return anchorLabel.replaceFirst("\\.\\.\\.", callLabel);
            }
            if (replaceWhenNoEllipsis) {
                return callLabel;
            }
            return anchorLabel + "</br>" + callLabel;
        }

        private CallInfo buildCallInfo(PsiMethodCallExpression callExpression) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("buildCallInfo enter: " + safeLabel(callExpression.getText()));
            }
            PsiMethod target = callExpression.resolveMethod();
            if (target == null) {
                return new CallInfo(NodeType.CALL, safeLabel(callExpression.getText()), Map.of());
            }
            boolean matchedSkipRegex = !skipRegexes.isEmpty() && shouldSkipByRegex(target, skipRegexes);
            boolean isJdk = isJdkMethod(target);
            int jdkDepth = jdkApiDepth;
            if (isJdk && jdkDepth < 0) {
                return null; // skip entirely
            }
            String label;
            String argsText = callExpression.getArgumentList().getText();
            String argDisplay = argsText != null ? argsText : "()";
            String summary = callSummary(target);
            String targetName = target.getName();
            String qualifier = null;
            try {
                PsiExpression qExpr = callExpression.getMethodExpression().getQualifierExpression();
                if (qExpr != null) {
                    qualifier = safeLabel(qExpr.getText());
                }
            } catch (Throwable ignored) {
            }
            if (owner.isEquivalentTo(target)) {
                String base = summary.isBlank() ? safeLabel(callExpression.getMethodExpression().getText()) : summary;
                label = "recursive call: " + base + argDisplay;
            } else {
                String base = summary.isBlank() ? targetName : summary;
                if (qualifier != null && !qualifier.isBlank()) {
                    base = qualifier + "." + base;
                }
                label = base + argDisplay;
            }
            if (isJdk && jdkDepth == 0) {
                label = safeLabel(callExpression.getText());
            }
            String signature = target.getName() + "(" + target.getParameterList().getText() + ")";
            String qname = target.getContainingClass() != null ? target.getContainingClass().getQualifiedName() : null;
            String calleeKey = qname != null ? qname + "." + signature : signature;
            String calleeDisplay = (summary.isBlank() ? targetName : summary) + argDisplay;
            String bodyText = target.getBody() != null ? safeLabel(target.getBody().getText()) : signature;
            Map<String, Object> meta = new HashMap<>();
            meta.put("callee", signature);
            meta.put("calleeKey", calleeKey);
            meta.put("calleeBody", bodyText);
            meta.put("calleeDisplay", calleeDisplay);
            meta.put("isJdk", isJdk);
            if (document != null) {
                try {
                    int line = document.getLineNumber(callExpression.getTextRange().getStartOffset()) + 1;
                    meta.put("lineNumber", line);
                } catch (Throwable ignored) {
                }
            }
            if (matchedSkipRegex) {
                meta.put("skipCallRender", true);
            } else if (isJdk && jdkDepth == 0) {
                meta.put("skipCallRender", true);
            }
            if (callDepth == 0) {
                meta.put("skipCallRender", true);
            }
            boolean allowExpand = callDepth != 0 && (!isJdk || jdkDepth > 0) && !matchedSkipRegex;
            int nextDepth = isJdk ? jdkDepth - 1 : jdkDepth;
            if (allowExpand && !visited.contains(target) && target.getBody() != null) {
                java.util.Set<PsiMethod> nestedVisited = new java.util.HashSet<>(visited);
                nestedVisited.add(target);
                int nextCallDepth = callDepth > 0 ? callDepth - 1 : callDepth;
                Java2FlowchartSettings.State nestedState = copyState(state);
                nestedState.setJdkApiDepth(nextDepth);
                nestedState.setCallDepth(nextCallDepth);
                Builder nested = new Builder(nestedState, target, nestedVisited);
                ControlFlowGraph calleeGraph = nested.build(target, target.getBody());
                meta.put("calleeGraph", calleeGraph);
            }
            return new CallInfo(NodeType.CALL, label, meta);
        }

        private void connectToEnd(List<Endpoint> exits) {
            for (Endpoint exit : exits) {
                edges.add(new Edge(exit.from(), endId, EdgeType.NORMAL, exit.label()));
            }
        }

        private void link(List<Endpoint> incoming, String to) {
            for (Endpoint endpoint : incoming) {
                edges.add(new Edge(endpoint.from(), to, EdgeType.NORMAL, endpoint.label()));
            }
        }

        private String addNode(NodeType type, String label, TextRange range) {
            return addNode(type, label, range, null);
        }

        private String addNode(NodeType type, String label, TextRange range, Map<String, Object> extras) {
            String id = nextId(range);
            Map<String, Object> meta = new HashMap<>();
            if (range != null) {
                meta.put("textRange", range);
                if (document != null) {
                    try {
                        int startLine = document.getLineNumber(range.getStartOffset()) + 1;
                        int endLine = document.getLineNumber(range.getEndOffset()) + 1;
                        meta.put("lineNumber", startLine);
                        meta.put("startLine", startLine);
                        meta.put("endLine", endLine);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (forceNoFold) {
                meta.put("noFold", true);
            }
            if (extras != null) {
                meta.putAll(extras);
            }
            nodes.add(new Node(id, type, label, meta));
            return id;
        }

        private String nextId(TextRange range) {
            if (document != null && range != null) {
                int line = document.getLineNumber(range.getStartOffset()) + 1;
                int count = lineCounters.merge(line, 1, Integer::sum);
                if (count == 1) {
                    return "L" + line;
                }
                return "L" + line + "_" + count;
            }
            return "n" + (++idSequence);
        }

        private String labelFrom(PsiElement element, String fallback) {
            return element == null ? fallback : safeLabel(element.getText());
        }

        private String safeLabel(String raw) {
            if (raw == null) {
                return "";
            }
            String singleLine = raw.replaceAll("\\s+", " ").trim();
            int max = labelMaxLength();
            if (max >= 0 && singleLine.length() > max) {
                return singleLine.substring(0, max) + "...";
            }
            return singleLine;
        }


        private int labelMaxLength() {
            try {
                return plus.wcj.jetbrains.plugins.java2flowchart.settings.Java2FlowchartSettings.getInstance().getState().getLabelMaxLength();
            } catch (Throwable ignored) {
                return 80;
            }
        }

        private <T> T withNoFold(Supplier<T> supplier) {
            boolean prev = forceNoFold;
            forceNoFold = true;
            try {
                return supplier.get();
            } finally {
                forceNoFold = prev;
            }
        }

        private void foldLinearActions() {
            List<Edge> originalEdgesSnapshot = new ArrayList<>(edges);
            boolean changed;
            do {
                changed = false;
                Map<String, Node> nodeById = nodes.stream().collect(Collectors.toMap(Node::id, n -> n));
                Map<String, List<Edge>> outgoing = new HashMap<>();
                Map<String, List<Edge>> incoming = new HashMap<>();
                for (Edge edge : edges) {
                    outgoing.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
                    incoming.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge);
                }
                List<String> toRemoveNodes = new ArrayList<>();
                List<Edge> newEdges = new ArrayList<>(edges);
                for (Node node : nodes) {
                    if (node.type() != NodeType.ACTION && node.type() != NodeType.CALL) {
                        continue;
                    }
                    List<Edge> outs = outgoing.getOrDefault(node.id(), List.of());
                    List<Edge> ins = incoming.getOrDefault(node.id(), List.of());
                    if (Boolean.TRUE.equals(node.meta().get("noFold"))) {
                        continue;
                    }
                    if (outs.size() != 1) {
                        continue;
                    }
                    if (ins.isEmpty()) {
                        continue;
                    }
                    Edge out = outs.get(0);
                    Edge in = ins.get(0);
                    if (out.type() != EdgeType.NORMAL || in.type() != EdgeType.NORMAL) {
                        continue;
                    }
                    Node target = nodeById.get(out.to());
                    if (target == null || (target.type() != NodeType.ACTION && target.type() != NodeType.CALL)) {
                        continue;
                    }
                    if (Boolean.TRUE.equals(target.meta().get("noFold"))) {
                        continue;
                    }
                    // avoid merging when the next node leads back to a loop head (keeps for-update visible)
                    boolean targetLeadsToLoopHead = outgoing.getOrDefault(target.id(), List.of()).stream()
                            .anyMatch(e -> nodeById.get(e.to()) != null && nodeById.get(e.to()).type() == NodeType.LOOP_HEAD);
                    if (targetLeadsToLoopHead) {
                        continue;
                    }
                    List<Edge> targetIncomingAll = incoming.getOrDefault(target.id(), List.of());
                    List<Edge> targetIncomingNormal = targetIncomingAll.stream()
                            .filter(e -> e.type() == EdgeType.NORMAL)
                            .toList();
                    boolean getterPair = isGetterPair(node, target);
                    if (targetIncomingNormal.size() != 1 && !getterPair) {
                        continue;
                    }
                    if (!allowMerge(node, target)) {
                        continue;
                    }
                    // Merge node into target
                    String mergedLabel = mergeLabels(node.label(), target.label());
                    Map<String, Object> mergedMeta = new HashMap<>(node.meta());
                    mergedMeta.putAll(target.meta());
                    // normalize line range to keep min start / max end for subsequent merge decisions
                    mergeLineRange(mergedMeta, node.meta(), target.meta());
                    Map<String, Object> callMetaA = extractCallMeta(node.meta());
                    Map<String, Object> callMetaB = extractCallMeta(target.meta());
                    boolean skipA = Boolean.TRUE.equals(node.meta().get("skipCallRender"));
                    boolean skipB = Boolean.TRUE.equals(target.meta().get("skipCallRender"));
                    if (skipA || skipB) {
                        mergedMeta.put("skipCallRender", true);
                    } else {
                        mergedMeta.remove("skipCallRender");
                    }
                    java.util.Set<String> mergedFrom = new java.util.LinkedHashSet<>(mergedSources(node));
                    mergedFrom.addAll(mergedSources(target));
                    mergedMeta.put("mergedFrom", new java.util.ArrayList<>(mergedFrom));
                    mergeCallMeta(mergedMeta, callMetaA, callMetaB);
                    Node mergedNode = new Node(node.id(), node.type(), mergedLabel, mergedMeta);
                    nodeById.put(node.id(), mergedNode);
                    toRemoveNodes.add(target.id());
                    // redirect edges from target to node
                    for (Edge targetOut : outgoing.getOrDefault(target.id(), List.of())) {
                        newEdges.add(new Edge(node.id(), targetOut.to(), targetOut.type(), targetOut.label()));
                    }
                    newEdges.remove(out);
                    newEdges.removeAll(targetIncomingNormal);
                    // Rewrite all edges that referenced target to point to the merged node, preserving CALL edges.
                    List<Edge> rewritten = new ArrayList<>();
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (Edge e : newEdges) {
                        String from = e.from().equals(target.id()) ? node.id() : e.from();
                        String to = e.to().equals(target.id()) ? node.id() : e.to();
                        if (from.equals(to)) {
                            continue; // avoid self-loop introduced by merge
                        }
                        String key = from + "|" + to + "|" + e.type() + "|" + (e.label() == null ? "" : e.label());
                        if (seen.add(key)) {
                            rewritten.add(new Edge(from, to, e.type(), e.label()));
                        }
                    }
                    newEdges = rewritten;
                    changed = true;
                    break;
                }
                if (changed) {
                    nodes.clear();
                    nodes.addAll(nodeById.values().stream()
                            .filter(n -> !toRemoveNodes.contains(n.id()))
                            .toList());
                    edges.clear();
                    edges.addAll(newEdges.stream()
                            .filter(e -> !toRemoveNodes.contains(e.from()) && !toRemoveNodes.contains(e.to()))
                            .toList());
                }
            } while (changed);

            // Restore edges for merged nodes based on recorded mergedFrom ids.
            reconcileEdgesWithMergedSources(originalEdgesSnapshot);
        }

        private void reconcileEdgesWithMergedSources(List<Edge> originalEdges) {
            if (originalEdges.isEmpty()) {
                return;
            }
            Map<String, String> alias = new HashMap<>();
            for (Node n : nodes) {
                Object m = n.meta().get("mergedFrom");
                if (m instanceof java.util.Collection<?> col) {
                    for (Object o : col) {
                        alias.put(String.valueOf(o), n.id());
                    }
                }
                alias.put(n.id(), n.id());
            }
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (Edge e : edges) {
                existing.add(e.from() + "|" + e.to() + "|" + e.type() + "|" + (e.label() == null ? "" : e.label()));
            }
            for (Edge e : originalEdges) {
                String from = alias.get(e.from());
                String to = alias.get(e.to());
                if (from == null || to == null || from.equals(to)) {
                    continue;
                }
                String key = from + "|" + to + "|" + e.type() + "|" + (e.label() == null ? "" : e.label());
                if (existing.add(key)) {
                    edges.add(new Edge(from, to, e.type(), e.label()));
                }
            }
        }

        private boolean allowMerge(Node a, Node b) {
            if (Boolean.TRUE.equals(a.meta().get("chainSplit")) || Boolean.TRUE.equals(b.meta().get("chainSplit"))) {
                return false; // keep chain-split nodes separate
            }
            String qa = qualifierOf(a.label());
            String qb = qualifierOf(b.label());
            boolean sameQualifier = qa != null && qa.equals(qb);

            CallKind kindA = callKind(a);
            CallKind kindB = callKind(b);
            if (kindA != kindB) {
                return false; // never mix different call kinds
            }

            if (separatedByBlankLine(a, b)) {
                return false;
            }

            boolean seqAllowed = foldSequentialCalls;
            boolean setter = seqAllowed && effectiveFoldSequentialSetters() && kindA == CallKind.SET;
            boolean getter = effectiveFoldSequentialGetters() && kindA == CallKind.GET;
            boolean ctor = seqAllowed && effectiveFoldSequentialCtors() && kindA == CallKind.CTOR;

            if (setter || getter || ctor) {
                return true;
            }
            return sameQualifier;
        }

        private Map<String, Object> extractCallMeta(Map<String, Object> meta) {
            if (meta == null) return null;
            boolean hasCall = meta.containsKey("callee") || meta.containsKey("calleeGraph");
            boolean hasInline = meta.get("inlineCalls") instanceof List<?>;
            if (!hasCall && !hasInline) {
                return null;
            }
            Map<String, Object> copy = new HashMap<>();
            for (String k : List.of("callee", "calleeKey", "calleeBody", "calleeDisplay", "calleeGraph", "skipCallRender", "inline", "lineNumber")) {
                if (meta.containsKey(k)) {
                    copy.put(k, meta.get(k));
                }
            }
            Object inline = meta.get("inlineCalls");
            if (inline instanceof List<?>) {
                copy.put("inlineCalls", inline);
            }
            return copy;
        }

        private void mergeCallMeta(Map<String, Object> mergedMeta, Map<String, Object> callA, Map<String, Object> callB) {
            // Use a linked map to preserve order and avoid duplicates by key.
            Map<String, Map<String, Object>> inlineMap = new java.util.LinkedHashMap<>();
            java.util.function.Consumer<Map<String, Object>> addInline = m -> {
                if (m == null) return;
                String key = inlineKey(m);
                inlineMap.putIfAbsent(key, m);
            };

            copyInline(mergedMeta.get("inlineCalls")).forEach(addInline);
            copyInline(callA != null ? callA.get("inlineCalls") : null).forEach(addInline);
            copyInline(callB != null ? callB.get("inlineCalls") : null).forEach(addInline);

            boolean hasPrimary = mergedMeta.containsKey("callee") || mergedMeta.containsKey("calleeGraph");
            if (callA != null && (callA.containsKey("callee") || callA.containsKey("calleeGraph"))) {
                if (!hasPrimary) {
                    mergedMeta.putAll(callA);
                    hasPrimary = true;
                } else {
                    addInline.accept(copyWithoutInline(callA));
                }
            }
            if (callB != null && (callB.containsKey("callee") || callB.containsKey("calleeGraph"))) {
                if (!hasPrimary) {
                    mergedMeta.putAll(callB);
                } else {
                    addInline.accept(copyWithoutInline(callB));
                }
            }
            if (!inlineMap.isEmpty()) {
                mergedMeta.put("inlineCalls", new ArrayList<>(inlineMap.values()));
            }
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> copyInline(Object raw) {
            List<Map<String, Object>> res = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        res.add(new HashMap<>((Map<String, Object>) m));
                    }
                }
            }
            return res;
        }

        private Map<String, Object> copyWithoutInline(Map<String, Object> meta) {
            Map<String, Object> cp = new HashMap<>(meta);
            cp.remove("inlineCalls");
            return cp;
        }

        private String inlineKey(Map<String, Object> meta) {
            Object calleeKey = meta.get("calleeKey");
            if (calleeKey != null) {
                return "calleeKey:" + calleeKey;
            }
            Object callee = meta.get("callee");
            if (callee != null) {
                return "callee:" + callee;
            }
            Object display = meta.get("calleeDisplay");
            if (display != null) {
                return "display:" + display;
            }
            return "metaHash:" + meta.hashCode();
        }

        private String mergeLabels(String a, String b) {
            if (a == null || a.isBlank()) return b;
            if (b == null || b.isBlank()) return a;
            return a + "</br>" + b;
        }

        private boolean isSetter(String label) {
            if (label == null) return false;
            String normalized = normalizeLabel(label);
            return normalized.matches(".*\\bset[A-Z].*\\(.*\\)");
        }

        private boolean isGetter(String label) {
            if (label == null) return false;
            String normalized = normalizeLabel(label);
            return normalized.matches(".*\\b(get|is)[A-Z].*\\(.*\\)");
        }

        private boolean isCtor(String label) {
            if (label == null) return false;
            String normalized = label.replace("\n", " ");
            return normalized.matches(".*=\\s*new\\s+.+\\(.*\\)") || normalized.matches("\\bnew\\s+.+\\(.*\\)");
        }



        private String qualifierOf(String label) {
            if (label == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Za-z_][\\w$.]*)\\.[A-Za-z_][\\w$]*\\(").matcher(label);
            if (m.find()) {
                return m.group(1);
            }
            return null;
        }


        private String normalizeLabel(String label) {
            return label
                    .replace("\\n", " ")
                    .replace("<br/>", " ")
                    .replace("</br>", " ")
                    .replace("\n", " ")
                    .trim();
        }

        private boolean isCtorNode(Node node) {
            return node != null && Boolean.TRUE.equals(node.meta().get("isCtor"));
        }

        private boolean containsCtor(PsiExpression expr) {
            if (expr == null) {
                return false;
            }
            final boolean[] found = {false};
            expr.accept(new JavaRecursiveElementWalkingVisitor() {
                @Override
                public void visitNewExpression(PsiNewExpression expression) {
                    found[0] = true;
                    super.visitNewExpression(expression);
                }
            });
            return found[0];
        }

        private boolean isGetterNode(Node node) {
            return node != null && Boolean.TRUE.equals(node.meta().get("isGetter"));
        }

        private CallKind callKind(Node node) {
            if (node == null) return CallKind.OTHER;
            String label = node.label();
            if (isCtorNode(node) || isCtor(label)) {
                return CallKind.CTOR;
            }
            if (isSetter(label)) {
                return CallKind.SET;
            }
            if (isGetter(label) || isGetterNode(node)) {
                return CallKind.GET;
            }
            return CallKind.OTHER;
        }

        private boolean effectiveFoldSequentialSetters() {
            return foldSequentialCalls && foldSequentialSetters;
        }

        private boolean effectiveFoldSequentialGetters() {
            return foldSequentialCalls && foldSequentialGetters;
        }

        private boolean effectiveFoldSequentialCtors() {
            return foldSequentialCalls && foldSequentialCtors;
        }

        private boolean separatedByBlankLine(Node a, Node b) {
            Integer endA = asInt(a.meta().get("endLine"));
            Integer startB = asInt(b.meta().get("startLine"));
            if (endA == null || startB == null || document == null) {
                return false;
            }
            if (startB <= endA) {
                return false;
            }
            // check lines strictly between endA and startB for blank-only lines (whitespace counts as blank)
            for (int ln = endA; ln < startB - 1; ln++) {
                try {
                    int lineStart = document.getLineStartOffset(ln);
                    int lineEnd = document.getLineEndOffset(ln);
                    String text = document.getText(new com.intellij.openapi.util.TextRange(lineStart, lineEnd));
                    if (text.trim().isEmpty()) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            return false;
        }

        private Integer asInt(Object o) {
            if (o instanceof Number n) {
                return n.intValue();
            }
            return null;
        }

        private void mergeLineRange(Map<String, Object> mergedMeta, Map<String, Object> aMeta, Map<String, Object> bMeta) {
            Integer aStart = asInt(aMeta.get("startLine"));
            Integer bStart = asInt(bMeta.get("startLine"));
            Integer aEnd = asInt(aMeta.get("endLine"));
            Integer bEnd = asInt(bMeta.get("endLine"));
            int start = java.util.stream.Stream.of(aStart, bStart)
                    .filter(java.util.Objects::nonNull)
                    .min(Integer::compareTo)
                    .orElseGet(() -> asInt(mergedMeta.get("startLine")) != null ? asInt(mergedMeta.get("startLine")) : 0);
            int end = java.util.stream.Stream.of(aEnd, bEnd)
                    .filter(java.util.Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElseGet(() -> asInt(mergedMeta.get("endLine")) != null ? asInt(mergedMeta.get("endLine")) : start);
            mergedMeta.put("startLine", start);
            mergedMeta.put("endLine", end);
            mergedMeta.put("lineNumber", start);
        }

        private CallGraph collectCallGraphForFluent(PsiMethodCallExpression root, boolean includeFluent) {
            java.util.Set<PsiMethodCallExpression> order = new java.util.LinkedHashSet<>();
            java.util.List<CallRelation> relations = new java.util.ArrayList<>();
            java.util.Set<String> relationKeys = new java.util.HashSet<>();
            collectCallGraphForFluent(root, null, null, order, relations, relationKeys, includeFluent);
            return new CallGraph(new java.util.ArrayList<>(order), relations);
        }

        private void collectCallGraphForFluent(PsiMethodCallExpression expr,
                                               PsiMethodCallExpression parent,
                                               CallRelationType relationType,
                                               java.util.Set<PsiMethodCallExpression> order,
                                               java.util.List<CallRelation> relations,
                                               java.util.Set<String> relationKeys,
                                               boolean includeFluent) {
            boolean added = order.add(expr);
            if (parent != null && relationType != null) {
                String key = System.identityHashCode(expr) + "|" + System.identityHashCode(parent) + "|" + relationType;
                if (relationKeys.add(key)) {
                    relations.add(new CallRelation(expr, parent, relationType));
                }
            }
            if (!added) {
                return;
            }
            if (includeFluent) {
                collectCallExpressionsForFluent(expr.getMethodExpression().getQualifierExpression(), expr, order, relations, relationKeys);
            }
        }

        private void collectCallExpressionsForFluent(PsiExpression expr,
                                                     PsiMethodCallExpression parent,
                                                     Set<PsiMethodCallExpression> order,
                                                     List<CallRelation> relations,
                                                     Set<String> relationKeys) {
            if (expr == null) {
                return;
            }
            expr.accept(new JavaRecursiveElementWalkingVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    collectCallGraphForFluent(expression, parent, CallRelationType.FLUENT, order, relations, relationKeys, true);
                }
            });
        }

        private List<PsiMethodCallExpression> collectNestedCalls(PsiMethodCallExpression root) {
            List<PsiMethodCallExpression> ordered = new ArrayList<>();
            java.util.function.Consumer<PsiExpression> walk = new java.util.function.Consumer<>() {
                @Override
                public void accept(PsiExpression expr) {
                    if (expr == null) return;
                    if (expr instanceof PsiMethodCallExpression mc) {
                        // qualifier first
                        accept(mc.getMethodExpression().getQualifierExpression());
                        // arguments left-to-right
                        for (PsiExpression arg : mc.getArgumentList().getExpressions()) {
                            accept(arg);
                        }
                        ordered.add(mc); // then the call itself
                    } else {
                        expr.acceptChildren(new com.intellij.psi.JavaRecursiveElementWalkingVisitor() {
                            @Override
                            public void visitExpression(PsiExpression expression) {
                                accept(expression);
                            }
                        });
                    }
                }
            };
            walk.accept(root);
            return ordered;
        }

        private boolean isGetterPair(Node a, Node b) {
            return foldSequentialGetters &&
                    (isGetter(a.label()) || isGetterNode(a)) &&
                    (isGetter(b.label()) || isGetterNode(b));
        }

        private List<String> mergedSources(Node node) {
            if (node == null) {
                return List.of();
            }
            Object m = node.meta().get("mergedFrom");
            if (m instanceof List<?>) {
                java.util.List<String> list = new java.util.ArrayList<>();
                for (Object o : (List<?>) m) {
                    if (o != null) {
                        list.add(o.toString());
                    }
                }
                if (!list.isEmpty()) {
                    return list;
                }
            }
            return List.of(node.id());
        }

        private boolean containsGetter(PsiExpression expr) {
            if (expr == null) return false;
            final boolean[] found = {false};
            expr.accept(new JavaRecursiveElementWalkingVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    PsiReferenceExpression ref = expression.getMethodExpression();
                    String name = ref.getReferenceName();
                    if (name != null && (name.startsWith("get") || name.startsWith("is"))) {
                        found[0] = true;
                    }
                    super.visitMethodCallExpression(expression);
                }
            });
            return found[0];
        }

        private String shortCallLabel(PsiMethodCallExpression call) {
            String name = call.getMethodExpression().getReferenceName();
            if (name == null || name.isBlank()) {
                name = safeLabel(call.getMethodExpression().getText());
            }
            String args = call.getArgumentList().getText();
            return name + args;
        }
    }

    private record Endpoint(String from, EdgeType type, String label) {
    }

    private record LoopContext(String continueTarget, String breakTarget) {
    }

    private record CallRelation(PsiMethodCallExpression child, PsiMethodCallExpression parent,
                                Builder.CallRelationType type) {
    }

    private record CallGraph(java.util.List<PsiMethodCallExpression> order, java.util.List<CallRelation> relations) {
    }

    private record ChainBuild(String anchorId, Map<PsiMethodCallExpression, String> nodeIds) {
    }
}
