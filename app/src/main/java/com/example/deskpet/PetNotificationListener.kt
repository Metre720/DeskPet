package com.example.deskpet

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class PetNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == "com.example.deskpet") return
        OverlayService.notificationCallback?.invoke(pkg)
    }
}
