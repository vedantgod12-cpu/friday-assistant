package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        fun btn(label: String, action: () -> Unit) {
            box.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }
        box.addView(TextView(this).apply {
            text = "Friday offline assistant\n\nDo steps 1 to 4, then say \"Friday\" followed by a command. Say \"Friday help\" to hear what I can do.\n"
            textSize = 17f
        })
        btn("1. Grant permissions") {
            requestPermissions(arrayOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS,
                Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS), 1)
        }
        btn("2. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("3. Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btn("4. Start assistant") {
            startForegroundService(Intent(this, AssistantService::class.java))
        }
        btn("Stop assistant") {
            stopService(Intent(this, AssistantService::class.java))
        }
        setContentView(box)
    }
}
