package dev.willcodes.bettereditortabs.tabgroups.ui.projectview

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.UIUtil
import dev.willcodes.bettereditortabs.tabgroups.model.groups.AutoGroupRule
import dev.willcodes.bettereditortabs.tabgroups.model.groups.RuleMatching
import dev.willcodes.bettereditortabs.tabgroups.service.RuleFiles
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import java.awt.Color

/**
 * POP-4: while the group popup edits an auto-group rule ([TabGroupsService.rulePreview]), the Project view marks the files the rule
 * matches (every matching file of the project, open or not, grouped or not) with a light tint of the group's colour as the node
 * background. Runs off the EDT under a read action: reads the immutable preview value only and asks [RuleFiles] about one file.
 */
@JvmDefaultWithoutCompatibility // no bridge for the interface's deprecated `decorate(PackageDependenciesNode, ColoredTreeCellRenderer)`
class RulePreviewDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        val preview = project.getServiceIfCreated(TabGroupsService::class.java)?.rulePreview ?: return
        val file = node.virtualFile ?: return
        if (!matches(project, preview.rule, file)) return
        data.background = tint(preview.color)
    }

    private fun matches(project: Project, rule: AutoGroupRule, file: VirtualFile): Boolean =
        file.isValid && !file.isDirectory && RuleMatching.matches(rule, RuleFiles.of(project, file))

    internal companion object {
        /** The group colour mixed into the tree background, like the tab tint (LINE-3). */
        fun tint(color: Color): Color = ColorUtil.mix(UIUtil.getTreeBackground(), color, TINT)

        const val TINT = 0.3
    }
}
