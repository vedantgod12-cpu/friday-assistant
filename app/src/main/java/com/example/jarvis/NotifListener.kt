package com.example.jarvis

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListener : NotificationListenerService() {
    companion object { val recent = ArrayList<String>() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (x: Exception) { sbn.packageName }
        val line = "$app. $title. $text"
        synchronized(recent) {
            recent.add(0, line)
            while (recent.size > 15) recent.removeAt(recent.size - 1)
        }
        if (AssistantService.announce) AssistantService.instance?.say(line)
    }
}
