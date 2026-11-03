package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var sub: TextView
    private val ui by lazy { Handler(mainLooper) }
    private val tick = object : Runnable {
        override fun run() { sub.text = AssistantService.status; ui.postDelayed(this, 700) }
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun bg(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun svc(action: String, q: String? = null) {
        val i = Intent(this, AssistantService::class.java).setAction(action)
        if (q != null) i.putExtra("q", q)
        startForegroundService(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0B0D12.toInt()
        window.navigationBarColor = 0xFF0B0D12.toInt()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0D12.toInt()) }

        // header
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(36), dp(12), dp(12)) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply { text = "Friday"; textSize = 26f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
        sub = TextView(this).apply { textSize = 12f; setTextColor(0xFF7A8094.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        titles.addView(sub)
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            text = "\u2699"; textSize = 26f; setTextColor(Color.WHITE); setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { menu() }
        })
        root.addView(head)

        // chat
        scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // input bar
        val input = EditText(this).apply {
            hint = "Ask Friday anything"; setTextColor(Color.WHITE); setHintTextColor(0xFF7A8094.toInt())
            textSize = 16f; maxLines = 4; background = bg(0xFF1B1F2A.toInt(), 24)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val send: () -> Unit = {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) { input.setText(""); svc("ASK", t) }
        }
        fun circle(label: String, color: Int, onTap: () -> Unit) = TextView(this).apply {
            text = label; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = bg(color, 28); setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { setMargins(dp(8), 0, 0, 0) }
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(circle("\uD83C\uDFA4", 0xFF4F7CFF.toInt()) { svc("LISTEN") })
        bar.addView(circle("\u27A4", 0xFF2B7A4B.toInt()) { send() })
        root.addView(bar)

        setContentView(root)
        if (Chat.msgs.isEmpty()) Chat.add(false, "Hi, I'm Friday. Say \"Friday\" and then a command, tap the mic, or type below. Open the gear menu to set me up.")
    }

    override fun onResume() {
        super.onResume()
        Chat.onChange = { render() }
        render(); ui.post(tick)
    }

    override fun onPause() {
        Chat.onChange = null; ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        list.removeAllViews()
        val copy = synchronized(Chat.msgs) { ArrayList(Chat.msgs) }
        for ((u, t) in copy) list.addView(bubble(u, t))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(user: Boolean, t: String) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.WHITE)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        background = bg(if (user) 0xFF4F7CFF.toInt() else 0xFF1B1F2A.toInt(), 18)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (user) Gravity.END else Gravity.START
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
    }

    private fun menu() {
        val items = arrayOf("1. Grant permissions", "2. Allow display over other apps", "3. Allow notification access",
            "4. Gemini API key", "5. Start assistant", "Stop assistant", "Wake word on/off",
            "Open phone assistant (Google / Gemini app)")
        AlertDialog.Builder(this).setItems(items) { _, w ->
            when (w) {
                0 -> requestPermissions(arrayOf(
                    Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS,
                    Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS), 1)
                1 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                2 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                3 -> keyDialog()
                4 -> svc("START")
                5 -> stopService(Intent(this, AssistantService::class.java))
                6 -> svc("TOGGLE_ALWAYS")
                7 -> svc("ASSIST")
            }
        }.show()
    }

    private fun keyDialog() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val e = EditText(this).apply {
            setText(p.getString("key", "")); hint = "Paste your Gemini API key"
            setTextColor(Color.WHITE); setHintTextColor(0xFF7A8094.toInt()); setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Gemini API key").setView(e)
            .setPositiveButton("Save") { _, _ ->
                p.edit().putString("key", e.text.toString().trim()).apply()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }
}
