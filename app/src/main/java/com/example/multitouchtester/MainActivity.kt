package com.example.multitouchtester

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Landing screen. This tool needs two permissions the OS will not grant silently:
 *   1. "Draw over other apps"   -> lets us show the floating control panel
 *   2. Accessibility Service    -> lets us call dispatchGesture() to hold synthetic touches
 * Both require an explicit, user-driven trip to Settings. We never try to auto-grant
 * or trick the user into enabling these - that would defeat their purpose as guardrails.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Multi-Touch Tester"
            textSize = 22f
        }

        val explanation = TextView(this).apply {
            text = "A QA tool for testing multi-touch input handling. It places two " +
                    "markers you can drag to any screen coordinate, then holds two " +
                    "synthetic touch contacts down simultaneously until you release them. " +
                    "It requires two permissions below, both granted manually in Settings."
            textSize = 14f
            setPadding(0, 24, 0, 24)
        }

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 24)
        }

        val overlayBtn = Button(this).apply {
            text = "1. Grant \"Draw over other apps\""
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        val accessibilityBtn = Button(this).apply {
            text = "2. Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val launchBtn = Button(this).apply {
            text = "3. Open Floating Control Panel"
            setOnClickListener {
                if (!isAccessibilityServiceEnabled()) {
                    statusText.text =
                        "Accessibility Service is not enabled yet. Please complete step 2 first."
                } else if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                    statusText.text =
                        "Overlay permission is not granted yet. Please complete step 1 first."
                } else {
                    statusText.text = "Panel requested. Look for the floating control panel."
                    // The accessibility service itself owns the overlay lifecycle; we just
                    // nudge it (it's already running once enabled in Settings). Broadcasting
                    // an explicit intent keeps this decoupled from service internals.
                    sendBroadcast(Intent(ACTION_SHOW_PANEL).setPackage(packageName))
                }
            }
        }

        root.addView(title)
        root.addView(explanation)
        root.addView(overlayBtn)
        root.addView(accessibilityBtn)
        root.addView(launchBtn)
        root.addView(statusText)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = isAccessibilityServiceEnabled()
        statusText.text = "Overlay permission: ${if (overlayOk) "granted" else "NOT granted"}\n" +
                "Accessibility service: ${if (a11yOk) "enabled" else "NOT enabled"}"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${MultiTouchAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (component in splitter) {
            if (component.equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }

    companion object {
        const val ACTION_SHOW_PANEL = "com.example.multitouchtester.ACTION_SHOW_PANEL"
    }
}
