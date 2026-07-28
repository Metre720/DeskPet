package com.example.deskpet
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var isMiniMode = false
    private var miniIsLeft = false
    private var savedY = 300
    private val handler = Handler(Looper.getMainLooper())
    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 80
        private const val PET_HEIGHT_DP = 55
        private const val EDGE_THRESHOLD_DP = 25
        private const val MINI_VISIBLE_DP = 40
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        setupOverlay()
    }
    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }
        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }
        windowManager?.addView(overlayView, params)
    }
    // === GESTURE HANDLING ===
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        if (!hasMoved && !isMiniMode) { onDragStart() }
                        hasMoved = true
                        if (!isMiniMode) {
                            params?.x = initialX + dx
                            params?.y = initialY + dy
                            windowManager?.updateViewLayout(overlayView, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (isMiniMode && !hasMoved) {
                        exitMiniMode()
                    } else if (hasMoved && !isMiniMode) {
                        onDragEnd()
                        checkEdgeSnap()
                    } else if (!hasMoved && !isMiniMode) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }
    private fun checkEdgeSnap() {
        val currentX = params?.x ?: 0
        val edgeThreshold = dpToPx(EDGE_THRESHOLD_DP)
        val petWidth = dpToPx(PET_SIZE_DP)
        when {
            currentX < edgeThreshold -> enterMiniMode(isLeft = true)
            currentX > screenWidth - petWidth - edgeThreshold -> enterMiniMode(isLeft = false)
        }
    }
    private fun enterMiniMode(isLeft: Boolean) {
        isMiniMode = true
        miniIsLeft = isLeft
        savedY = params?.y ?: 300
        val petWidth = dpToPx(PET_SIZE_DP)
        val visible = dpToPx(MINI_VISIBLE_DP)
        params?.x = if (isLeft) -(petWidth - visible) else screenWidth - visible
        windowManager?.updateViewLayout(overlayView, params)
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.enterMini(${isLeft})", null
        )
    }
    private fun exitMiniMode() {
        isMiniMode = false
        val targetX = if (miniIsLeft) dpToPx(10) else screenWidth - dpToPx(PET_SIZE_DP) - dpToPx(10)
        params?.x = targetX
        windowManager?.updateViewLayout(overlayView, params)
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.exitMini()", null
        )
    }
    private fun onTap() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onTap()", null
        )
    }
    private fun onDoubleTap() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onDoubleTap()", null
        )
    }
    private fun onLongPress() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onLongPress()", null
        )
    }
    private fun onDragStart() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onDragStart()", null
        )
    }
    private fun onDragEnd() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onDragEnd()", null
        )
    }
    // === NOTIFICATION ===
    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeskPet")
            .setContentText("蹲在屏幕上看着你")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
    // === UTILS ===
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    override fun onDestroy() {
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
