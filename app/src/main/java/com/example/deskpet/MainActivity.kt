package com.example.deskpet

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.LinearLayout
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "\uD83E\uDD80 DeskPet"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = if (Settings.canDrawOverlays(this@MainActivity))
                "✅ 悬浮窗权限已授予" else "❌ 需要悬浮窗权限"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        val btnPermission = Button(this).apply {
            text = "授予悬浮窗权限"
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
            }
        }

        val btnStart = Button(this).apply {
            text = "启动桌宠"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startService(Intent(this@MainActivity, OverlayService::class.java))
                    finish()
                }
            }
        }

        val btnStop = Button(this).apply {
            text = "关闭桌宠"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
            }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(btnPermission)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)
    }
}
