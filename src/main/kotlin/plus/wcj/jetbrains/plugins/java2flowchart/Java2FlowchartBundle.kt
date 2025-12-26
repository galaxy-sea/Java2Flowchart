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
