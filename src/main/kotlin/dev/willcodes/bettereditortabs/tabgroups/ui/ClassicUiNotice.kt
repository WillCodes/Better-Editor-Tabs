package dev.willcodes.bettereditortabs.tabgroups.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle.message
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * With the Classic UI the plugin stays inactive ([TabBar.isSupported]); this says so once per IDE session, in the first project that
 * opens. Stored groups are left untouched and come back once the New UI is on again.
 */
class ClassicUiNotice : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (TabBar.isSupported || !shown.compareAndSet(false, true)) return
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(message("notification.classicUi.title"), message("notification.classicUi.content"), NotificationType.WARNING)
            .notify(project)
    }

    private companion object {
        const val GROUP_ID = "Better Editor Tabs"
        val shown = AtomicBoolean(false)
    }
}
