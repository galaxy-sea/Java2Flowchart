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

package plus.wcj.jetbrains.plugins.java2flowchart.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Tag

@Service(Service.Level.APP)
@State(name = "Java2FlowchartSettings", storages = [Storage("java2flowchart.xml")])
class Java2FlowchartSettings : PersistentStateComponent<Java2FlowchartSettings.State> {
    companion object {
        @JvmStatic
        fun getInstance(): Java2FlowchartSettings = service()
    }

    data class State(
        var foldFluentCalls: Boolean = true,
        var foldNestedCalls: Boolean = true,
        var foldSequentialCalls: Boolean = true,
        var foldSequentialSetters: Boolean = true,
        var foldSequentialGetters: Boolean = true,
        var foldSequentialCtors: Boolean = true,
        var language: Language = Language.EN,
        var jdkApiDepth: Int = 0,
        var mergeCalls: Boolean = true,
        /**
         * Call expansion depth. 0 = only selected method, 1 = expand its direct callees, 2+ = deeper, -1 = unlimited.
         */
        var callDepth: Int = 1,
        /**
         * Whether to use Javadoc first sentence as node labels when available.
         */
        var useJavadocLabels: Boolean = true,
        /**
         * Structured regex entries (enabled + pattern).
         */
        var skipRegexEntries: MutableList<SkipRegexEntry> = mutableListOf(
            SkipRegexEntry(true, "^.*[.#]get(?!Class\\b)[A-Z]\\w*\\(\\)$"),
            SkipRegexEntry(true, "^.*[.#]set[A-Z]\\w*\\([^(),]+\\)$"),
            SkipRegexEntry(true, "^.*[.#]is[A-Z]\\w*\\(\\)$"),
            SkipRegexEntry(true, "^.*[.#]toString\\(\\)$"),
            SkipRegexEntry(true, "^.*[.#]hashCode\\(\\)$")
        ),
        /**
         * -1: fully expand ternary expressions
         * 0 : do not expand
         * N : expand up to N levels
         */
        var ternaryExpandLevel: Int = -1,
        /**
         * Max label length; -1 means no truncation.
         */
        var labelMaxLength: Int = 80
    )

    @Tag("SkipRegexEntry")
    data class SkipRegexEntry(var enabled: Boolean = true, var pattern: String = "")

    enum class Language {
        EN, ZH
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}
