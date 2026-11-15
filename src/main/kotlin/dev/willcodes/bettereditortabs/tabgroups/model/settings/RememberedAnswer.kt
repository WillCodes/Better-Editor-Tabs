package dev.willcodes.bettereditortabs.tabgroups.model.settings

/**
 * How a remembered "Don't ask again" answer (LIFE-3a, MEM-5, RULE-6) maps to the settings page's pair of controls: an "ask" checkbox
 * and an answer combo box (§14). Stored, an answer is one of [valid] (an enum name) or null, which means "ask every time".
 *
 * - The checkbox is ticked while the stored value is null (or not one of [valid], which the service also treats as "ask").
 * - The combo shows the stored answer, or [default] (the non-destructive one) while the stored value is null.
 * - Applying with the box ticked stores null whatever the combo shows; unticked it stores the combo's answer.
 */
class RememberedAnswer(private val valid: List<String>, private val default: String) {
    init {
        require(default in valid) { "default $default is not one of $valid" }
    }

    /** Whether the question is still asked for [stored]. */
    fun asks(stored: String?): Boolean = stored == null || stored !in valid

    /** The answer the combo box shows for [stored]. */
    fun shown(stored: String?): String = if (stored != null && stored in valid) stored else default

    /** The value to store for a checkbox state [ask] and a combo answer [answer]. */
    fun toStored(ask: Boolean, answer: String): String? = if (ask) null else answer.takeIf { it in valid } ?: default
}
