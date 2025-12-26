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
import org.slf4j.LoggerFactory;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.ControlFlowGraph;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.Edge;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.EdgeType;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.Node;
import plus.wcj.jetbrains.plugins.java2flowchart.ir.NodeType;

import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiNewExpression;
import java.util.stream.Collectors;

public class JavaFlowExtractor implements FlowExtractor {
    private static final Logger LOG = Logger.getInstance(JavaFlowExtractor.class);
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(JavaFlowExtractor.class);

    @Override
    public ControlFlowGraph extract(PsiMethod method, ExtractOptions options) {
        Objects.requireNonNull(method, "method");
        ExtractOptions safeOptions = options == null ? ExtractOptions.defaultOptions() : options;
        PsiCodeBlock body = method.getBody();
        java.util.Set<PsiMethod> visited = new java.util.HashSet<>();
        visited.add(method);
        Builder builder = new Builder(safeOptions, method, visited);
        return builder.build(method, body);
    }

    private static final class Builder {
        private final ExtractOptions options;
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

        Builder(ExtractOptions options, PsiMethod owner, java.util.Set<PsiMethod> visited) {
            this.options = options;
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
            if (options.foldFluentCalls() || options.foldNestedCalls() || options.foldSequentialCalls()
                    || options.foldSequentialSetters() || options.foldSequentialGetters() || options.foldSequentialCtors()) {
                foldLinearActions();
            }
            return new ControlFlowGraph(startId, endId, nodes, edges);
        }
        
        private String methodSummary(PsiMethod method) {
            String fallback =  method.getName();
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
            if (!options.useJavadocLabels()) {
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

        private String methodDisplay(PsiMethod method) {
            String qname = method.getContainingClass() != null ? method.getContainingClass().getQualifiedName() : "";
            String params = Arrays.stream(method.getParameterList().getParameters())
                    .map(p -> p.getType().getPresentableText())
                    .collect(Collectors.joining(", "));
            String base = method.getName() + "(" + params + ")";
            if (qname == null || qname.isBlank()) {
                return base;
            }
            return qname + "#" + base;
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
            link(incoming, decisionId, EdgeType.NORMAL, null);

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
            link(incoming, headId, EdgeType.NORMAL, null);

            loopStack.push(new LoopContext(headId, afterLoop));
            List<Endpoint> bodyExits = handleStatement(whileStatement.getBody(), List.of(new Endpoint(headId, EdgeType.TRUE, "true")));
            loopStack.pop();

            link(bodyExits, headId, EdgeType.NORMAL, null);
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

            link(bodyExits, headId, EdgeType.NORMAL, null);
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
            link(afterInit, headId, EdgeType.NORMAL, null);

            loopStack.push(new LoopContext(headId, afterLoop));
            List<Endpoint> bodyExits = handleStatement(forStatement.getBody(), List.of(new Endpoint(headId, EdgeType.TRUE, "true")));
            loopStack.pop();

            PsiStatement update = forStatement.getUpdate();
            List<Endpoint> updateExits = bodyExits;
            if (update != null && !(update instanceof PsiEmptyStatement)) {
                updateExits = handleStatement(update, bodyExits);
            }
            link(updateExits, headId, EdgeType.NORMAL, null);
            edges.add(new Edge(headId, afterLoop, EdgeType.FALSE, infinite ? "false" : "false"));
            return List.of(new Endpoint(afterLoop, EdgeType.NORMAL, null));
        }

        private List<Endpoint> handleForeach(PsiForeachStatement foreachStatement, List<Endpoint> incoming) {
            String headId = addNode(NodeType.LOOP_HEAD, "for (" + safeLabel(foreachStatement.getIterationParameter().getName()) + " : " + labelFrom(foreachStatement.getIteratedValue(), "?") + ")", foreachStatement.getTextRange());
            String afterLoop = addNode(NodeType.MERGE, "", foreachStatement.getTextRange());
            link(incoming, headId, EdgeType.NORMAL, null);

            loopStack.push(new LoopContext(headId, afterLoop));
            List<Endpoint> bodyExits = handleStatement(foreachStatement.getBody(), List.of(new Endpoint(headId, EdgeType.TRUE, "next")));
            loopStack.pop();

            link(bodyExits, headId, EdgeType.NORMAL, null);
            edges.add(new Edge(headId, afterLoop, EdgeType.FALSE, "done"));
            return List.of(new Endpoint(afterLoop, EdgeType.NORMAL, null));
        }

        private List<Endpoint> handleReturn(PsiReturnStatement returnStatement, List<Endpoint> incoming) {
            String label = returnStatement.getReturnValue() == null ? "return" : "return " + labelFrom(returnStatement.getReturnValue(), "?");
            PsiExpression exprRaw = returnStatement.getReturnValue();
            PsiExpression expr = unwrap(exprRaw);
            if (expr == null) {
                String returnId = addNode(NodeType.RETURN, label, returnStatement.getTextRange());
                link(incoming, returnId, EdgeType.NORMAL, null);
                connectReturnEndpoints(List.of(new Endpoint(returnId, EdgeType.NORMAL, null)));
                return List.of();
            }
            List<Endpoint> exits = buildReturnExpr(expr, incoming, returnStatement.getTextRange(), options.ternaryExpandLevel());
            connectReturnEndpoints(exits);
            return List.of();
        }

        private List<Endpoint> handleBreak(PsiStatement breakStatement, List<Endpoint> incoming) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("noFold", true);
            String breakId = addNode(NodeType.ACTION, "break", breakStatement.getTextRange(), meta);
            link(incoming, breakId, EdgeType.NORMAL, null);
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
            link(incoming, continueId, EdgeType.NORMAL, null);
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
            link(incoming, switchId, EdgeType.NORMAL, null);
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
                        link(exits, mergeId, EdgeType.NORMAL, null);
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
            link(incoming, throwId, EdgeType.NORMAL, null);
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
            if (methodName == null) {
                return false;
            }
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
            link(incoming, decisionId, EdgeType.NORMAL, null);
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
                link(incoming, decisionId, EdgeType.NORMAL, null);
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
                    link(incoming, callId, EdgeType.NORMAL, null);
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
            link(incoming, returnId, EdgeType.NORMAL, null);
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
            link(incoming, returnId, EdgeType.NORMAL, null);
            edges.add(new Edge(returnId, sg.switchId(), EdgeType.RETURN, "switch"));
            return List.of(new Endpoint(returnId, EdgeType.NORMAL, null));
        }

        private record SwitchGraph(String switchId, String mergeId) {}

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
                        link(exits, mergeId, EdgeType.NORMAL, null);
                    } else if (stmt instanceof PsiSwitchLabelStatementBase label) {
                        String caseId = addNode(NodeType.DECISION, labelText(label), label.getTextRange());
                        edges.add(new Edge(switchId, caseId, EdgeType.NORMAL, null));
                        link(List.of(new Endpoint(caseId, EdgeType.NORMAL, null)), mergeId, EdgeType.NORMAL, null);
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
                link(incoming, actionId, EdgeType.NORMAL, null);
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
                link(incoming, actionId, EdgeType.NORMAL, null);
                return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
            }
            String actionId = addNode(NodeType.ACTION, safeLabel(rule.getText()), rule.getTextRange());
            link(incoming, actionId, EdgeType.NORMAL, null);
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
            link(incoming, tryId, EdgeType.NORMAL, null);
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
                link(sources, finallyId, EdgeType.NORMAL, null);
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
                        if (containsGetter(init)) {
                            meta = meta == null ? new HashMap<>() : meta;
                            meta.put("isGetter", true);
                        }
                        if (containsCtor(init)) {
                            meta = meta == null ? new HashMap<>() : meta;
                            meta.put("isCtor", true);
                        }
                        int depth = options.ternaryExpandLevel();
                        if (init instanceof PsiConditionalExpression cond && depth != 0) {
                            String lhsLabel = var.getType().getPresentableText() + " " + var.getName() + " = ...";
                            String lhsId = addNode(NodeType.ACTION, lhsLabel, decl.getTextRange());
                            return handleConditionalExpression(cond, List.of(new Endpoint(lhsId, EdgeType.RETURN, "=")), decl.getTextRange(), depth);
                        }
                        if (init instanceof PsiSwitchExpression switchExpr) {
                            SwitchGraph sg = buildSwitchGraph(switchExpr, options.ternaryExpandLevel(), init.getTextRange());
                            String declLabel = var.getType().getPresentableText() + " " + var.getName() + " = switch";
                            String actionId = addNode(NodeType.ACTION, declLabel, decl.getTextRange());
                            link(incoming, actionId, EdgeType.NORMAL, null);
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
                    int depth = options.ternaryExpandLevel();
                    if (depth != 0) {
                        return handleConditionalExpression(conditional, incoming, statement.getTextRange(), depth);
                    }
                    String actionId = addNode(NodeType.ACTION, safeLabel(expression.getText()), statement.getTextRange());
                    link(incoming, actionId, EdgeType.NORMAL, null);
                    return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
                }
                if (expression instanceof PsiAssignmentExpression assign && assign.getRExpression() instanceof PsiConditionalExpression cond) {
                    int depth = options.ternaryExpandLevel();
                    if (depth != 0) {
                        String lhsLabel = safeLabel(assign.getLExpression().getText()) + " = ...";
                        String lhsId = addNode(NodeType.ACTION, lhsLabel, statement.getTextRange());
                        return handleConditionalExpression(cond, List.of(new Endpoint(lhsId, EdgeType.RETURN, "=")), statement.getTextRange(), depth);
                    }
                }
                if (expression instanceof PsiAssignmentExpression assign2) {
                    if (containsGetter(assign2.getRExpression())) {
                        meta = meta == null ? new HashMap<>() : meta;
                        meta.put("isGetter", true);
                    }
                }
                if (expression instanceof PsiMethodCallExpression callExpression) {
                    if (!options.foldFluentCalls()) {
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
                        if (chain.size() > 1) {
                            String chainId = "chain_" + statement.getTextRange().getStartOffset();
                            List<Endpoint> current = incoming;
                            for (int idx = 0; idx < chain.size(); idx++) {
                                PsiMethodCallExpression mc = chain.get(idx);
                                CallInfo ci = buildCallInfo(mc);
                                if (ci == null) {
                                    continue;
                                }
                                Map<String, Object> m = new HashMap<>(ci.meta());
                                List<Map<String, Object>> inlineCalls = collectCallsFromArguments(mc);
                                if (!inlineCalls.isEmpty()) {
                                    m.put("inlineCalls", inlineCalls);
                                }
                                m.put("fluentChainId", chainId);
                                String chainLabel = idx == 0 ? ci.label() : "..." + stripQualifier(ci.label());
                                String id = addNode(ci.type(), chainLabel, mc.getTextRange(), m);
                                link(current, id, EdgeType.NORMAL, null);
                                current = List.of(new Endpoint(id, EdgeType.NORMAL, null));
                            }
                            return current;
                        }
                    }
                    CallInfo call = buildCallInfo(callExpression);
                    if (call != null) {
                        type = call.type();
                        label = call.label();
                        List<Map<String, Object>> inlineCalls = collectCallsFromArguments(callExpression);
                        if (!inlineCalls.isEmpty()) {
                            meta = new HashMap<>(call.meta());
                            meta.put("inlineCalls", inlineCalls);
                        } else {
                            meta = call.meta();
                        }
                    } else {
                        return incoming;
                    }
                } else if (expression instanceof PsiMethodCallExpression) {
                    if (containsGetter(expression)) {
                        meta = meta == null ? new HashMap<>() : meta;
                        meta.put("isGetter", true);
                    }
                } else if (expression instanceof PsiAssignmentExpression assign && assign.getRExpression() instanceof PsiSwitchExpression switchExpr) {
                    SwitchGraph sg = buildSwitchGraph((PsiSwitchExpression) assign.getRExpression(), options.ternaryExpandLevel(), expression.getTextRange());
                    String lhs = safeLabel(assign.getLExpression().getText());
                    String actionId = addNode(NodeType.ACTION, lhs + " = switch", statement.getTextRange());
                    link(incoming, actionId, EdgeType.NORMAL, null);
                    edges.add(new Edge(actionId, sg.switchId(), EdgeType.RETURN, "switch"));
                    return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
                } else if (expression instanceof PsiSwitchExpression switchExpr) {
                    SwitchGraph sg = buildSwitchGraph(switchExpr, options.ternaryExpandLevel(), expression.getTextRange());
                    String actionId = addNode(NodeType.ACTION, "switch", statement.getTextRange());
                    link(incoming, actionId, EdgeType.NORMAL, null);
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
            link(incoming, actionId, EdgeType.NORMAL, null);
            return List.of(new Endpoint(actionId, EdgeType.NORMAL, null));
        }

        private CallInfo buildCallInfo(PsiMethodCallExpression callExpression) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("buildCallInfo enter: " + safeLabel(callExpression.getText()));
            }
            PsiMethod target = callExpression.resolveMethod();
            if (target == null) {
                return new CallInfo(NodeType.CALL, safeLabel(callExpression.getText()), Map.of());
            }
            boolean matchedSkipRegex = !options.skipRegexes().isEmpty() && shouldSkipByRegex(target, options.skipRegexes());
            boolean isJdk = isJdkMethod(target);
            int jdkDepth = options.jdkApiDepth();
            if (isJdk && jdkDepth < 0) {
                return null; // skip entirely
            }
            String label;
            String argsText = callExpression.getArgumentList() != null ? callExpression.getArgumentList().getText() : "()";
            String argDisplay = argsText != null ? argsText : "()";
            String summary = callSummary(target);
            String targetName = target.getName() != null ? target.getName() : safeLabel(callExpression.getMethodExpression().getText());
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
            if (options.callDepth() == 0) {
                meta.put("skipCallRender", true);
            }
            int callDepth = options.callDepth();
            boolean allowExpand = callDepth != 0 && (!isJdk || jdkDepth > 0) && !matchedSkipRegex;
            int nextDepth = isJdk ? jdkDepth - 1 : jdkDepth;
            if (allowExpand && !visited.contains(target) && target.getBody() != null) {
                java.util.Set<PsiMethod> nestedVisited = new java.util.HashSet<>(visited);
                nestedVisited.add(target);
                int nextCallDepth = callDepth > 0 ? callDepth - 1 : callDepth;
                ExtractOptions nestedOptions = new ExtractOptions(
                        options.foldFluentCalls(),
                        options.foldNestedCalls(),
                        options.foldSequentialCalls(),
                        options.foldSequentialSetters(),
                        options.foldSequentialGetters(),
                        options.foldSequentialCtors(),
                        nextDepth,
                        options.mergeCalls(),
                        options.ternaryExpandLevel(),
                        nextCallDepth,
                        options.useJavadocLabels(),
                        options.skipRegexes()
                );
                Builder nested = new Builder(nestedOptions, target, nestedVisited);
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

        private void link(List<Endpoint> incoming, String to, EdgeType edgeType, String label) {
            for (Endpoint endpoint : incoming) {
                edges.add(new Edge(endpoint.from(), to, edgeType != null ? edgeType : endpoint.type(), label != null ? label : endpoint.label()));
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
                        int line = document.getLineNumber(range.getStartOffset()) + 1;
                        meta.put("lineNumber", line);
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

        private String shorten(String raw, int max) {
            String s = safeLabel(raw);
            int limit = labelMaxLength();
            if (limit < 0) {
                return s;
            }
            int use = Math.min(limit, max);
            if (s.length() > use) {
                return s.substring(0, use) + "...";
            }
            return s;
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
                    Map<String, Object> callMetaA = extractCallMeta(node.meta());
                    Map<String, Object> callMetaB = extractCallMeta(target.meta());
                    boolean skipA = Boolean.TRUE.equals(node.meta().get("skipCallRender"));
                    boolean skipB = Boolean.TRUE.equals(target.meta().get("skipCallRender"));
                    if (!(skipA && skipB)) {
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
            String qa = qualifierOf(a.label());
            String qb = qualifierOf(b.label());
            boolean sameQualifier = qa != null && qb != null && qa.equals(qb);

            boolean seqAllowed = options.foldSequentialCalls();
            boolean setter = seqAllowed && options.foldSequentialSetters() && isSetter(a.label()) && isSetter(b.label());
            boolean getter = options.foldSequentialGetters() &&
                    (isGetter(a.label()) || isGetterNode(a)) &&
                    (isGetter(b.label()) || isGetterNode(b));
            boolean ctor = options.foldSequentialCtors() && (isCtorNode(a) && isCtorNode(b) || isCtor(a.label()) && isCtor(b.label())); // allow ctor merge when ctor folding enabled

            if (setter || getter || ctor) {
                return true;
            }
            // fluent chain merge on same qualifier
            if (options.foldFluentCalls() && sameQualifier) {
                return true;
            }
            // nested call merge fallback (restrict to same qualifier to avoid merging unrelated sequential calls)
            if (options.foldNestedCalls() && sameQualifier && isCall(a.label()) && isCall(b.label())) {
                return true;
            }
            return false;
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

        @SuppressWarnings("unchecked")
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
                    hasPrimary = true;
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

        private boolean sameLhs(String a, String b) {
            String la = lhsVar(a);
            String lb = lhsVar(b);
            return la != null && la.equals(lb);
        }

        private String lhsVar(String label) {
            if (label == null) return null;
            int eq = label.indexOf('=');
            if (eq <= 0) return null;
            return label.substring(0, eq).trim();
        }

        private String qualifierOf(String label) {
            if (label == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Za-z_][\\w$.]*)\\.[A-Za-z_][\\w$]*\\(").matcher(label);
            if (m.find()) {
                return m.group(1);
            }
            return null;
        }

        private boolean isCall(String label) {
            return label != null && label.contains("(") && label.contains(")");
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

        private String stripQualifier(String label) {
            if (label == null) {
                return "";
            }
            int lastDot = label.lastIndexOf('.');
            if (lastDot >= 0) {
                return label.substring(lastDot + 1);
            }
            return label;
        }

        private boolean isGetterPair(Node a, Node b) {
            return options.foldSequentialGetters() &&
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
    }

    private record Endpoint(String from, EdgeType type, String label) {
    }

    private record LoopContext(String continueTarget, String breakTarget) {
    }
}
