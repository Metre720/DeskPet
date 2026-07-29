package com.example.deskpet
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import java.io.File
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
    private var screenshotObserver: FileObserver? = null
    private var batteryReceiver: BroadcastReceiver? = null
    companion object {
        private const val CHANNEL_ID = "pet_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 65
        private const val PET_HEIGHT_DP = 72
        private const val EDGE_THRESHOLD_DP = 25
        private const val MINI_VISIBLE_DP = 32
        var notificationCallback: ((String) -> Unit)? = null
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
        setupScreenshotObserver()
        setupBatteryReceiver()
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
        notificationCallback = { pkg ->
            handler.post {
                overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.onNotification('$pkg')", null
                )
            }
        }
    }
    // === GESTURE HANDLING ===
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchStartTime = 0L
    private var hasMoved = false
    private var tapCount = 0
    private var isLongPressing = false
    private var lastMoveX = 0f
    private var lastMoveTime = 0L
    private var isFlung = false
    private val FLING_VELOCITY_THRESHOLD = 700f
    private val tapResetRunnable = Runnable { tapCount = 0 }
    private val longPressStartRunnable = Runnable {
        if (!hasMoved) {
            isLongPressing = true
            onLongPressStart()
            handler.postDelayed(longPressPhase2Runnable, 3400)
        }
    }
    private val longPressPhase2Runnable = Runnable {
        if (isLongPressing) { onLongPressPhase2() }
    }
    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    lastMoveX = event.rawX
                    lastMoveTime = System.currentTimeMillis()
                    hasMoved = false
                    isFlung = false
                    handler.postDelayed(longPressStartRunnable, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        handler.removeCallbacks(longPressStartRunnable)
                        handler.removeCallbacks(longPressPhase2Runnable)
                        if (!hasMoved && !isMiniMode) { onDragStart() }
                        hasMoved = true
                        if (!isMiniMode) {
                            params?.x = initialX + dx
                            params?.y = initialY + dy
                            windowManager?.updateViewLayout(overlayView, params)
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastMoveTime > 10) {
                            lastMoveX = event.rawX
                            lastMoveTime = now
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressStartRunnable)
                    handler.removeCallbacks(longPressPhase2Runnable)
                    if (isLongPressing) {
                        isLongPressing = false
                        val holdTime = System.currentTimeMillis() - touchStartTime
                        if (holdTime < 2000) onLongPressShort() else onLongPressEnd()
                    } else if (isMiniMode && hasMoved) {
                        exitMiniMode()
                    } else if (isMiniMode && !hasMoved) {
                        onTapCount(1)
                    } else if (hasMoved && !isMiniMode) {
                        val upTime = System.currentTimeMillis()
                        val dt = (upTime - lastMoveTime).coerceAtLeast(1)
                        val velocityX = Math.abs(event.rawX - lastMoveX) / dt * 1000f
                        if (velocityX > FLING_VELOCITY_THRESHOLD) {
                            val flingLeft = (event.rawX - lastMoveX) < 0
                            onFling(flingLeft)
                        } else if (!checkEdgeSnap()) {
                            onDragEnd()
                        }
                    } else if (!hasMoved && !isMiniMode) {
                        tapCount++
                        handler.removeCallbacks(tapResetRunnable)
                        handler.postDelayed(tapResetRunnable, 2000)
                        onTapCount(tapCount)
                    }
                    true
                }
                else -> false
            }
        }
    }
    private fun checkEdgeSnap(): Boolean {
        val currentX = params?.x ?: 0
        val edgeThreshold = dpToPx(EDGE_THRESHOLD_DP)
        val petWidth = dpToPx(PET_SIZE_DP)
        return when {
            currentX < edgeThreshold -> { enterMiniMode(isLeft = true); true }
            currentX > screenWidth - petWidth - edgeThreshold -> { enterMiniMode(isLeft = false); true }
            else -> false
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
    private fun onTapCount(count: Int) {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onTapCount($count)", null
        )
    }
    private fun onLongPressStart() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onLongPressStart()", null
        )
    }
    private fun onLongPressPhase2() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onLongPressPhase2()", null
        )
    }
    private fun onLongPressShort() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onLongPressShort()", null
        )
    }
    private fun onLongPressEnd() {
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onLongPressEnd()", null
        )
    }
    private fun onFling(toLeft: Boolean) {
        isFlung = true
        val returnX = initialX
        val returnY = initialY
        val startX = params?.x ?: 0
        val startY = params?.y ?: 0
        overlayView?.evaluateJavascript(
            "window.petEngine && window.petEngine.onFling($toLeft)", null
        )
        handler.postDelayed({
            overlayView?.evaluateJavascript(
                "window.petEngine && window.petEngine.onFlingReturn($toLeft)", null
            )
            val steps = 30
            val stepDelay = 50L
            var currentStep = 0
            val animator = object : Runnable {
                override fun run() {
                    currentStep++
                    val t = currentStep.toFloat() / steps
                    val ease = 1f - (1f - t) * (1f - t)
                    val currentX = (startX + (returnX - startX) * ease).toInt()
                    val currentY = (startY + (returnY - startY) * ease).toInt()
                    params?.x = currentX
                    params?.y = currentY
                    windowManager?.updateViewLayout(overlayView, params)
                    if (currentStep < steps) {
                        handler.postDelayed(this, stepDelay)
                    } else {
                        isFlung = false
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onFlingDone()", null
                        )
                    }
                }
            }
            handler.post(animator)
        }, 1500)
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
    // === SCREENSHOT OBSERVER ===
    private fun setupScreenshotObserver() {
        val screenshotDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Screenshots"
        )
        if (!screenshotDir.exists()) screenshotDir.mkdirs()
        screenshotObserver = object : FileObserver(screenshotDir.absolutePath, CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg")) {
                    handler.post {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onScreenshot()", null
                        )
                    }
                }
            }
        }
        screenshotObserver?.startWatching()
    }
    // === BATTERY RECEIVER ===
    private fun setupBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_LOW -> {
                        handler.post {
                            overlayView?.evaluateJavascript(
                                "window.petEngine && window.petEngine.onLowBattery()", null
                            )
                        }
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        handler.post {
                            overlayView?.evaluateJavascript(
                                "window.petEngine && window.petEngine.onCharging()", null
                            )
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        registerReceiver(batteryReceiver, filter)
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
        screenshotObserver?.stopWatching()
        screenshotObserver = null
        batteryReceiver?.let { unregisterReceiver(it) }
        batteryReceiver = null
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
