package com.example.deskpet
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
class PetNotificationListener : NotificationListenerService() {
    private val lastNotifyTime = mutableMapOf<String, Long>()
    private val throttleMs = 30000L
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == "com.example.deskpet") return
        val now = System.currentTimeMillis()
        val last = lastNotifyTime[pkg] ?: 0L
        if (now - last < throttleMs) return
        lastNotifyTime[pkg] = now
        OverlayService.notificationCallback?.invoke(pkg)
    }
}
