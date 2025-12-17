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
