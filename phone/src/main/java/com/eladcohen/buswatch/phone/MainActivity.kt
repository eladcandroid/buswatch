package com.eladcohen.buswatch.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny control panel: enable the a11y service, open Nearby Bus. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        root.addView(
            TextView(this).apply {
                text = "Bus Bridge\n\n" +
                    "Streams the Nearby Bus arrivals screen to the watch on port ${BusAccessibilityService.PORT} over Wi-Fi.\n\n" +
                    "1. Enable the accessibility service below.\n" +
                    "2. Open Nearby Bus and view a stop.\n" +
                    "The watch shows it live while both are on the same Wi-Fi."
                textSize = 16f
            },
        )

        root.addView(
            Button(this).apply {
                text = "Enable accessibility service"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            },
        )

        root.addView(
            Button(this).apply {
                text = "Open Nearby Bus"
                setOnClickListener {
                    packageManager
                        .getLaunchIntentForPackage(BusAccessibilityService.TARGET_PKG)
                        ?.let { startActivity(it) }
                }
            },
        )

        setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}
