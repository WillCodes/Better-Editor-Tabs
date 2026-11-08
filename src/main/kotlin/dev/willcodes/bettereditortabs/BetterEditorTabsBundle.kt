package dev.willcodes.bettereditortabs

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.BetterEditorTabsBundle"

object BetterEditorTabsBundle : DynamicBundle(BetterEditorTabsBundle::class.java, BUNDLE) {
    @JvmStatic
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)

    @JvmStatic
    fun lazyMessage(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): java.util.function.Supplier<@Nls String> =
        getLazyMessage(key, *params)
}
