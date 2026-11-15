package dev.willcodes.bettereditortabs.tabgroups.model.groups

import dev.willcodes.bettereditortabs.tabgroups.model.persist.rewriteUrl

/** The rule types of RULE-1 (§10), in the order the popup's combo box lists them (§6). */
enum class RuleKind { MODULE, DIRECTORY, PATH_REGEX }

/**
 * A group's auto-group rule (RULE-1). [enabled] is the popup's *Auto-group* checkbox: a rule that is not enabled keeps its type and
 * value (so unticking and ticking again restores them) but never matches and does not exempt the group from MEM-5 (RULE-6).
 * [value] depends on [kind]: the module name ([RuleKind.MODULE]), the directory's VFS URL ([RuleKind.DIRECTORY], rewritten on
 * moves and renames like stored files, MEM-4) or the regex pattern ([RuleKind.PATH_REGEX]).
 */
data class AutoGroupRule(val enabled: Boolean, val kind: RuleKind, val value: String) {
    /** The compiled pattern of a [RuleKind.PATH_REGEX] rule; null for other kinds or an invalid pattern (which matches nothing). */
    val regex: Regex? by lazy { if (kind == RuleKind.PATH_REGEX) RuleMatching.compile(value) else null }

    /** True if this rule can pull files into its group: enabled and with a usable value. */
    val isActive: Boolean
        get() = enabled && value.isNotEmpty() && (kind != RuleKind.PATH_REGEX || regex != null)

    /** MEM-4: this rule after [oldUrl] moved to [newUrl]; only a [RuleKind.DIRECTORY] rule's value is a URL. The same rule if nothing changes. */
    fun withUrlMoved(oldUrl: String, newUrl: String): AutoGroupRule {
        if (kind != RuleKind.DIRECTORY) return this
        val moved = rewriteUrl(value, oldUrl, newUrl)
        return if (moved == value) this else copy(value = moved)
    }
}

/**
 * What a rule needs to know about one file (RULE-1), gathered by the service: [url] the file's VFS URL, [moduleName] the module it
 * belongs to (null if none), [projectRelativePath] its path relative to the project's base directory with forward slashes (null for a
 * file outside the project), [absolutePath] its system-independent absolute path (forward slashes).
 */
data class RuleFile(val url: String, val moduleName: String?, val projectRelativePath: String?, val absolutePath: String)

/** The group [RuleMatching.firstMatch] picked and whether it already has an instance in the split ([inSplit]). */
class RuleHit(val group: TabGroup, val inSplit: Boolean)

/**
 * The rules of §10 as pure functions (RULE-1, RULE-3). No platform types: the service gathers a [RuleFile] and acts on the answer.
 */
object RuleMatching {
    /** The pattern compiled, or null if it is not a valid Java regex (the popup shows an inline error and does not save it). */
    fun compile(pattern: String): Regex? = try {
        if (pattern.isEmpty()) null else Regex(pattern)
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * RULE-1: does [file] match [rule]? A rule that is not [AutoGroupRule.isActive] matches nothing.
     * - [RuleKind.MODULE]: the file's module has exactly the rule's name.
     * - [RuleKind.DIRECTORY]: the file is below the directory (its URL starts with the directory's URL and a slash; `/src/foo` does
     *   not match `/src/foobar/x`; a trailing slash of the value is ignored).
     * - [RuleKind.PATH_REGEX]: the pattern is found anywhere in the project-relative path (the absolute path for a file outside the
     *   project), written with forward slashes.
     */
    fun matches(rule: AutoGroupRule, file: RuleFile): Boolean {
        if (!rule.isActive) return false
        return when (rule.kind) {
            RuleKind.MODULE -> file.moduleName == rule.value
            RuleKind.DIRECTORY -> {
                val dir = rule.value.trimEnd('/')
                dir.isNotEmpty() && file.url.startsWith("$dir/")
            }
            RuleKind.PATH_REGEX -> rule.regex?.containsMatchIn(file.projectRelativePath ?: file.absolutePath) == true
        }
    }

    /**
     * RULE-3: the group whose rule matches [file] first. [inSplit]: the groups with an instance in the target split, in strip order;
     * [others]: the project's other groups (no instance there), in creation order; they are looked at after the first list. Groups
     * without an active rule are skipped. Null if nothing matches.
     */
    fun firstMatch(inSplit: List<TabGroup>, others: List<TabGroup>, file: RuleFile): RuleHit? {
        for (group in inSplit) if (group.rule?.let { matches(it, file) } == true) return RuleHit(group, inSplit = true)
        for (group in others) if (group.rule?.let { matches(it, file) } == true) return RuleHit(group, inSplit = false)
        return null
    }
}
