package plus.wcj.jetbrains.plugins.java2flowchart.extract;

import java.util.ArrayList;
import java.util.List;

public record ExtractOptions(boolean foldFluentCalls, boolean foldNestedCalls, boolean foldSequentialCalls,
                             boolean foldSequentialSetters, boolean foldSequentialGetters, boolean foldSequentialCtors,
                             int jdkApiDepth, boolean mergeCalls, int ternaryExpandLevel, int callDepth, boolean useJavadocLabels,
                             List<String> skipRegexes) {
    public static ExtractOptions defaultOptions() {
        boolean foldFluent = true;
        boolean foldNested = true;
        boolean foldSeq = true;
        boolean foldSet = true;
        boolean foldGet = true;
        boolean foldCtor = true;
        int depth = 0;
        boolean merge = true;
        int ternary = -1;
        int callDepth = 1;
        boolean useJavadoc = true;
        List<String> skipList = new ArrayList<>();
        try {
            var state = plus.wcj.jetbrains.plugins.java2flowchart.settings.Java2FlowchartSettings.getInstance().getState();
            foldFluent = state.getFoldFluentCalls();
            foldNested = state.getFoldNestedCalls();
            foldSeq = state.getFoldSequentialCalls();
            foldSet = state.getFoldSequentialSetters();
            foldGet = state.getFoldSequentialGetters();
            foldCtor = state.getFoldSequentialCtors();
            depth = state.getJdkApiDepth();
            merge = state.getMergeCalls();
            ternary = state.getTernaryExpandLevel();
            callDepth = state.getCallDepth();
            useJavadoc = state.getUseJavadocLabels();
            if (state.getSkipRegexEntries() != null && !state.getSkipRegexEntries().isEmpty()) {
                for (var entry : state.getSkipRegexEntries()) {
                    if (entry.getEnabled() && entry.getPattern() != null && !entry.getPattern().isBlank()) {
                        skipList.add(entry.getPattern());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // enforce parent/child relationship: if sequential folding is off, all sub-folds are off
        if (!foldSeq) {
            foldSet = false;
            foldGet = false;
            foldCtor = false;
        }
        return new ExtractOptions(foldFluent, foldNested, foldSeq, foldSet, foldGet, foldCtor, depth, merge, ternary, callDepth, useJavadoc, skipList);
    }
}
