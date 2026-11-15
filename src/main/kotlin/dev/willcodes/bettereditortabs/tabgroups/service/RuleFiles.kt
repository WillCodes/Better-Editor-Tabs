package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.RuleFile

/**
 * What the auto-group rules need to know about a file, read with public API only: the module through
 * `ModuleUtilCore.findModuleForFile` (a read action, which the EDT does not hold implicitly since 2026.1; `ReadAction.computeBlocking`, as `compute` is deprecated), the path relative to the
 * project's base path (`Project.getBasePath`) with forward slashes, null for a file outside the project, and the file's own path.
 */
internal object RuleFiles {
    fun of(project: Project, file: VirtualFile): RuleFile {
        val module = ReadAction.computeBlocking<String?, RuntimeException> { ModuleUtilCore.findModuleForFile(file, project)?.name }
        return RuleFile(file.url, module, relativePath(project.basePath, file), file.path)
    }

    /** [file]'s path below [basePath] (forward slashes), or null if it is not below it. */
    fun relativePath(basePath: String?, file: VirtualFile): String? {
        val base = basePath?.let { FileUtil.toSystemIndependentName(it).trimEnd('/') } ?: return null
        val path = FileUtil.toSystemIndependentName(file.path)
        val prefix = "$base/"
        return if (path.startsWith(prefix, ignoreCase = !SystemInfo.isFileSystemCaseSensitive)) path.substring(prefix.length) else null
    }
}
