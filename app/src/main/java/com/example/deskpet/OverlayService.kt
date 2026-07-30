package com.example.deskpet
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
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
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.view.inputmethod.InputMethodManager
import java.util.Calendar
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
    private var musicCheckRunnable: Runnable? = null
    private var isMusicPlaying = false
    private var keyboardCheckRunnable: Runnable? = null
    private var isKeyboardShowing = false
    private var foregroundCheckRunnable: Runnable? = null
    private var currentForegroundState = ""
    private var lateNightActive = false
    private var lateNightCheckRunnable: Runnable? = null
    private var screenOffTime = 0L
    private var screenReceiver: BroadcastReceiver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var alarmReceiver: BroadcastReceiver? = null
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
        setupMusicChecker()
        setupKeyboardChecker()
        setupForegroundChecker()
        setupLateNightChecker()
        setupScreenReceiver()
        setupPackageReceiver()
        setupNetworkCallback()
        setupAlarmReceiver()
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
    private var isFlung = false
    private var velocityTracker: VelocityTracker? = null
    private val FLING_VELOCITY_THRESHOLD = 2500f
    private val FLING_DIST_THRESHOLD = 200f
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
                    hasMoved = false
                    isFlung = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    handler.postDelayed(longPressStartRunnable, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
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
                        velocityTracker?.addMovement(event)
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vx = velocityTracker?.xVelocity ?: 0f
                        val vy = velocityTracker?.yVelocity ?: 0f
                        val speed = Math.sqrt((vx * vx + vy * vy).toDouble()).toFloat()
                        val totalDist = Math.sqrt(
                            ((event.rawX - initialTouchX) * (event.rawX - initialTouchX) +
                             (event.rawY - initialTouchY) * (event.rawY - initialTouchY)).toDouble()
                        ).toFloat()
                        velocityTracker?.recycle()
                        velocityTracker = null
                        if (totalDist > FLING_DIST_THRESHOLD && speed > FLING_VELOCITY_THRESHOLD) {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            val flingLeft = if (Math.abs(dx) >= Math.abs(dy)) dx < 0 else dy < 0
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
    // === MUSIC DETECTION ===
    private fun setupMusicChecker() {
        musicCheckRunnable = object : Runnable {
            override fun run() {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val playing = audioManager.isMusicActive
                if (playing && !isMusicPlaying) {
                    isMusicPlaying = true
                    handler.post {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onMusicStart()", null
                        )
                    }
                } else if (!playing && isMusicPlaying) {
                    isMusicPlaying = false
                    handler.post {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onMusicStop()", null
                        )
                    }
                }
                handler.postDelayed(this, 3000)
            }
        }
        handler.postDelayed(musicCheckRunnable!!, 3000)
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
    // === KEYBOARD DETECTION ===
    private fun setupKeyboardChecker() {
        keyboardCheckRunnable = object : Runnable {
            override fun run() {
                val visible = isKeyboardVisible()
                if (visible && !isKeyboardShowing) {
                    isKeyboardShowing = true
                    handler.post {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onKeyboardShow()", null
                        )
                    }
                } else if (!visible && isKeyboardShowing) {
                    isKeyboardShowing = false
                    handler.post {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onKeyboardHide()", null
                        )
                    }
                }
                handler.postDelayed(this, 2000)
            }
        }
        handler.postDelayed(keyboardCheckRunnable!!, 2000)
    }
    private fun isKeyboardVisible(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val insets = overlayView?.rootWindowInsets
                if (insets != null) {
                    return insets.isVisible(android.view.WindowInsets.Type.ime())
                }
            }
            val rect = android.graphics.Rect()
            overlayView?.getWindowVisibleDisplayFrame(rect)
            val screenHeight = resources.displayMetrics.heightPixels
            val keyboardHeight = screenHeight - rect.bottom
            keyboardHeight > 200
        } catch (e: Exception) { false }
    }
    // === FOREGROUND APP DETECTION ===
    private val gamePackages = setOf("com.netease.tom", "com.tencent.tmgp.sgame", "com.miHoYo.Yuanshen")
    private val studyPackages = setOf("cn.wps.moffice_eng", "com.baidu.homework")
    private val operitPackage = "com.ai.assistance.operit"
    private fun setupForegroundChecker() {
        foregroundCheckRunnable = object : Runnable {
            override fun run() {
                val pkg = getForegroundPackage()
                val newState = when {
                    pkg in gamePackages -> "game"
                    pkg == operitPackage -> "operit"
                    pkg in studyPackages -> "study"
                    else -> ""
                }
                if (newState != currentForegroundState) {
                    val oldState = currentForegroundState
                    currentForegroundState = newState
                    handler.post { onForegroundStateChanged(oldState, newState) }
                }
                handler.postDelayed(this, 3000)
            }
        }
        handler.postDelayed(foregroundCheckRunnable!!, 5000)
    }
    private fun getForegroundPackage(): String {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return ""
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 5000, now)
        var lastPkg = ""
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPkg = event.packageName
            }
        }
        return lastPkg
    }
    private fun onForegroundStateChanged(old: String, new: String) {
        if (old.isNotEmpty()) {
            val stopMethod = when (old) {
                "game" -> "onGameStop"
                "operit" -> "onOperitStop"
                "study" -> "onStudyStop"
                else -> null
            }
            stopMethod?.let {
                overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.$it()", null
                )
            }
        }
        if (new.isNotEmpty()) {
            val startMethod = when (new) {
                "game" -> "onGameStart"
                "operit" -> "onOperitStart"
                "study" -> "onStudyStart"
                else -> null
            }
            startMethod?.let {
                overlayView?.evaluateJavascript(
                    "window.petEngine && window.petEngine.$it()", null
                )
            }
        }
    }
    // === LATE NIGHT DETECTION ===
    private fun setupLateNightChecker() {
        lateNightCheckRunnable = object : Runnable {
            override fun run() {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (hour >= 1 && hour < 6 && !lateNightActive) {
                    lateNightActive = true
                    handler.post {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onLateNight()", null
                        )
                    }
                } else if ((hour >= 6 || hour < 1) && lateNightActive) {
                    lateNightActive = false
                    handler.post {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onLateNightEnd()", null
                        )
                    }
                }
                handler.postDelayed(this, 60000)
            }
        }
        handler.postDelayed(lateNightCheckRunnable!!, 10000)
    }
    // === SCREEN OFF DETECTION (for late night end) ===
    private fun setupScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOffTime = System.currentTimeMillis()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        if (lateNightActive && screenOffTime > 0) {
                            val offDuration = System.currentTimeMillis() - screenOffTime
                            if (offDuration > 600000) {
                                lateNightActive = false
                                handler.post {
                                    overlayView?.evaluateJavascript(
                                        "window.petEngine && window.petEngine.onLateNightEnd()", null
                                    )
                                }
                            }
                        }
                        screenOffTime = 0L
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }
    // === APP INSTALL DETECTION ===
    private fun setupPackageReceiver() {
        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_PACKAGE_ADDED) {
                    handler.post {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onAppInstall()", null
                        )
                    }
                    handler.postDelayed({
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onAppInstallDone()", null
                        )
                    }, 15000)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
    }
    // === WIFI DETECTION ===
    private fun setupNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    handler.post {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onWifiConnected()", null
                        )
                    }
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.registerNetworkCallback(request, networkCallback!!)
    }
    // === ALARM DETECTION ===
    private fun setupAlarmReceiver() {
        alarmReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action.contains("ALARM") || action.contains("alarm")) {
                    handler.post {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onAlarmRing()", null
                        )
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.android.deskclock.ALARM_ALERT")
            addAction("com.android.alarmclock.ALARM_ALERT")
            addAction("android.intent.action.ALARM_CHANGED")
        }
        registerReceiver(alarmReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
    }
    // === UTILS ===
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    override fun onDestroy() {
        musicCheckRunnable?.let { handler.removeCallbacks(it) }
        musicCheckRunnable = null
        keyboardCheckRunnable?.let { handler.removeCallbacks(it) }
        keyboardCheckRunnable = null
        foregroundCheckRunnable?.let { handler.removeCallbacks(it) }
        foregroundCheckRunnable = null
        lateNightCheckRunnable?.let { handler.removeCallbacks(it) }
        lateNightCheckRunnable = null
        screenshotObserver?.stopWatching()
        screenshotObserver = null
        batteryReceiver?.let { unregisterReceiver(it) }
        batteryReceiver = null
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
        packageReceiver?.let { unregisterReceiver(it) }
        packageReceiver = null
        alarmReceiver?.let { unregisterReceiver(it) }
        alarmReceiver = null
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }
        networkCallback = null
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}

