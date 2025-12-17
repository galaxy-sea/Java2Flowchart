package plus.wcj.jetbrains.plugins.java2flowchart

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey
import plus.wcj.jetbrains.plugins.java2flowchart.settings.Java2FlowchartSettings
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

private const val BUNDLE = "messages.MyMessageBundle"

object Java2FlowchartBundle : DynamicBundle(BUNDLE) {
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        return getMessage(key, *params)
    }

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, language: Java2FlowchartSettings.Language, vararg params: Any): String {
        val locale = when (language) {
            Java2FlowchartSettings.Language.ZH -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.ENGLISH
        }
        // Force a bundle lookup with explicit locale and no fallback to avoid sticky default-locale caches
        val control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)
        val bundle = ResourceBundle.getBundle(BUNDLE, locale, Java2FlowchartBundle::class.java.classLoader, control)
        val pattern = bundle.getString(key)
        return MessageFormat(pattern, locale).format(params)
    }
}
