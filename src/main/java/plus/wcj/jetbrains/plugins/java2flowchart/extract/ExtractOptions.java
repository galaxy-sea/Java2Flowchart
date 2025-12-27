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

import java.util.ArrayList;
import java.util.List;

public record ExtractOptions( boolean foldSequentialCalls,
                             boolean foldSequentialSetters, boolean foldSequentialGetters, boolean foldSequentialCtors,
                             int jdkApiDepth, boolean mergeCalls, int ternaryExpandLevel, int callDepth, boolean useJavadocLabels,
                             List<String> skipRegexes) {
    public static ExtractOptions defaultOptions() {
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
        // foldFluentCalls / foldNestedCalls are always treated as enabled
        return new ExtractOptions( foldSeq, foldSet, foldGet, foldCtor, depth, merge, ternary, callDepth, useJavadoc, skipList);
    }
}
