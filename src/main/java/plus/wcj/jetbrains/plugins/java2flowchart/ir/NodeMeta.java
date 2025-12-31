package plus.wcj.jetbrains.plugins.java2flowchart.ir;

import com.intellij.openapi.util.TextRange;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Typed metadata previously stored as a map on {@link Node}. Also used to represent call metadata.
 */
@Data
@Accessors(chain = true)
public class NodeMeta {
    private TextRange textRange;
    private Integer lineNumber;
    private Integer startLine;
    private Integer endLine;
    private Boolean noFold;
    private Boolean chainSplit;
    private String fluentChainId;
    private Boolean isGetter;
    private Boolean isSetter;
    private Boolean isCtor;
    private Boolean skipCallRender;
    private Boolean isJdk;
    private Boolean inline;
    private String callee;
    private String calleeKey;
    private String calleeBody;
    private String calleeDisplay;
    private ControlFlowGraph calleeGraph;
    private List<String> mergedFrom = new ArrayList<>();
    private List<NodeMeta> inlineCalls = new ArrayList<>();

    public NodeMeta copy() {
        NodeMeta copy = new NodeMeta()
                .setTextRange(textRange)
                .setLineNumber(lineNumber)
                .setStartLine(startLine)
                .setEndLine(endLine)
                .setNoFold(noFold)
                .setChainSplit(chainSplit)
                .setFluentChainId(fluentChainId)
                .setIsGetter(isGetter)
                .setIsSetter(isSetter)
                .setIsCtor(isCtor)
                .setSkipCallRender(skipCallRender)
                .setIsJdk(isJdk)
                .setInline(inline)
                .setCallee(callee)
                .setCalleeKey(calleeKey)
                .setCalleeBody(calleeBody)
                .setCalleeDisplay(calleeDisplay)
                .setCalleeGraph(calleeGraph);
        if (mergedFrom != null) {
            copy.setMergedFrom(new ArrayList<>(mergedFrom));
        }
        if (inlineCalls != null && !inlineCalls.isEmpty()) {
            List<NodeMeta> copied = new ArrayList<>(inlineCalls.size());
            for (NodeMeta meta : inlineCalls) {
                copied.add(meta.copy());
            }
            copy.setInlineCalls(copied);
        }
        return copy;
    }

    public void addInline(NodeMeta meta) {
        if (meta != null) {
            inlineCalls = inlineCalls == null ? new ArrayList<>() : inlineCalls;
            inlineCalls.add(meta);
        }
    }

    public void addInlineAll(Collection<NodeMeta> metas) {
        if (metas != null && !metas.isEmpty()) {
            inlineCalls = inlineCalls == null ? new ArrayList<>() : inlineCalls;
            for (NodeMeta meta : metas) {
                if (meta != null) {
                    inlineCalls.add(meta);
                }
            }
        }
    }

    public void addMergedFrom(String id) {
        if (id != null) {
            mergedFrom = mergedFrom == null ? new ArrayList<>() : mergedFrom;
            mergedFrom.add(id);
        }
    }

    public void addMergedFromAll(Collection<String> ids) {
        if (ids != null) {
            ids.forEach(this::addMergedFrom);
        }
    }

    public boolean hasNoFold() {
        return Boolean.TRUE.equals(noFold);
    }

    public boolean hasSkipCallRender() {
        return Boolean.TRUE.equals(skipCallRender);
    }

    public boolean hasChainSplit() {
        return Boolean.TRUE.equals(chainSplit);
    }

    public boolean hasInline() {
        return Boolean.TRUE.equals(inline);
    }

    public boolean hasGetterFlag() {
        return Boolean.TRUE.equals(isGetter);
    }

    public boolean hasSetterFlag() {
        return Boolean.TRUE.equals(isSetter);
    }

    public boolean hasCtorFlag() {
        return Boolean.TRUE.equals(isCtor);
    }


    public void mergeCallMeta(NodeMeta source) {
        if (source == null) {
            return;
        }
        if (callee == null) {
            callee = source.callee;
            calleeKey = source.calleeKey;
            calleeBody = source.calleeBody;
            calleeDisplay = source.calleeDisplay;
            calleeGraph = source.calleeGraph;
            skipCallRender = firstNonNull(skipCallRender, source.skipCallRender);
            inline = firstNonNull(inline, source.inline);
            isJdk = firstNonNull(isJdk, source.isJdk);
            isGetter = firstNonNull(isGetter, source.isGetter);
            isSetter = firstNonNull(isSetter, source.isSetter);
            isCtor = firstNonNull(isCtor, source.isCtor);
            fluentChainId = firstNonNull(fluentChainId, source.fluentChainId);
            chainSplit = firstNonNull(chainSplit, source.chainSplit);
            lineNumber = firstNonNull(lineNumber, source.lineNumber);
        } else if (source.getCallee() != null || source.getCalleeGraph() != null || source.hasInline()) {
            addInline(source.copy());
        }
        addInlineAll(source.inlineCalls);
    }

    private <T> T firstNonNull(T v1, T v2) {
        return v1 != null ? v1 : v2;
    }

    public void mergeMeta(NodeMeta extras) {
        if (extras == null) {
            return;
        }
        NodeMeta copy = extras.copy();
        if (copy.getTextRange() != null) setTextRange(copy.getTextRange());
        if (copy.getLineNumber() != null) setLineNumber(copy.getLineNumber());
        if (copy.getStartLine() != null) setStartLine(copy.getStartLine());
        if (copy.getEndLine() != null) setEndLine(copy.getEndLine());
        if (copy.getNoFold() != null) setNoFold(copy.getNoFold());
        if (copy.getChainSplit() != null) setChainSplit(copy.getChainSplit());
        if (copy.getFluentChainId() != null) setFluentChainId(copy.getFluentChainId());
        if (copy.getIsGetter() != null) setIsGetter(copy.getIsGetter());
        if (copy.getIsSetter() != null) setIsSetter(copy.getIsSetter());
        if (copy.getIsCtor() != null) setIsCtor(copy.getIsCtor());
        if (copy.getSkipCallRender() != null) setSkipCallRender(copy.getSkipCallRender());
        if (copy.getIsJdk() != null) setIsJdk(copy.getIsJdk());
        if (copy.getInline() != null) setInline(copy.getInline());
        if (copy.getCallee() != null) setCallee(copy.getCallee());
        if (copy.getCalleeKey() != null) setCalleeKey(copy.getCalleeKey());
        if (copy.getCalleeBody() != null) setCalleeBody(copy.getCalleeBody());
        if (copy.getCalleeDisplay() != null) setCalleeDisplay(copy.getCalleeDisplay());
        if (copy.getCalleeGraph() != null) setCalleeGraph(copy.getCalleeGraph());
        addMergedFromAll(copy.getMergedFrom());
        addInlineAll(copy.getInlineCalls());
    }
}
