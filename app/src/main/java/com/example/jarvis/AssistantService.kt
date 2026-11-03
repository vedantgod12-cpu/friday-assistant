package com.example.jarvis

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import kotlin.random.Random
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("DEPRECATION")
class AssistantService : Service(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private val starter = Runnable { startListening() }
    private var tts: TextToSpeech? = null
    private var speaking = false
    private var running = true

    companion object {
        var instance: AssistantService? = null
        @Volatile var status = "Service not started"
        @Volatile var announce = false
    }

    fun say(text: String) {
        handler.post { stopEngine(); handler.removeCallbacks(starter); speak(text) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private var alwaysOn = true    // wake-word mode (on by default)
    private var oneShot = false    // listening because the user pressed Talk
    private var inCall = false

    private fun buildNotif(): Notification {
        fun pi(a: String) = PendingIntent.getService(this, a.hashCode(),
            Intent(this, AssistantService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "jarvis")
            .setContentTitle(if (alwaysOn) "Friday is always listening" else "Friday is ready")
            .setContentText(if (alwaysOn) "Say \"Friday\" then a command" else "Tap Talk to give a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(Notification.Action.Builder(0, "Talk", pi("LISTEN")).build())
            .addAction(Notification.Action.Builder(0, if (alwaysOn) "Wake word: OFF" else "Wake word: ON", pi("TOGGLE_ALWAYS")).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "LISTEN" -> {
                if (model == null) { say("Still loading the speech model"); return START_STICKY }
                oneShot = true
                tts?.stop(); speaking = false
                try { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (e: Exception) {}
                stopEngine()
                restart(150)
            }
            "ASK" -> {
                val q = intent?.getStringExtra("q") ?: ""
                if (q.isNotBlank()) {
                    Chat.add(true, q)
                    try { handle(q.lowercase().trim()) } catch (e: Exception) { speak("Sorry, that did not work") }
                }
            }
            "ASSIST" -> openAssistant()
            "TOGGLE_ALWAYS" -> {
                alwaysOn = !alwaysOn
                getSystemService(NotificationManager::class.java).notify(1, buildNotif())
                if (alwaysOn) { say("Wake word mode on"); } else { stopEngine(); muteBeep(false); say("Wake word mode off") }
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
            val n = buildNotif()
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, n)
        } catch (e: Exception) { status = "Service failed to start: ${e.message}"; stopSelf(); return }
        tts = TextToSpeech(this, this)
        loadModel()
        try {
            val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(callReceiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(callReceiver, f)
        } catch (e: Exception) {}
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) { restart(500); return }
        t.language = Locale.getDefault()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { handler.post { speaking = false; restart(300) } }
            override fun onError(id: String?) { handler.post { speaking = false; restart(300) } }
        })
        speak("Friday is ready")
    }

    private fun speak(text: String, log: Boolean = true) {
        if (log) Chat.add(false, text)
        speaking = true
        stopEngine()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    private fun restart(ms: Long) {
        handler.removeCallbacks(starter)
        handler.postDelayed(starter, ms)
    }

    private fun muteBeep(on: Boolean) {}   // not needed: our own engine makes no beeps

    private var pauseUntil = 0L
    private val history = ArrayList<Pair<String, String>>()

    private fun showUi() {
        try { go(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (e: Exception) {}
    }

    // Sends the question to Google's Gemini and shows + speaks the answer in Friday's own UI
    private fun askGemini(q: String) {
        val key = getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isEmpty()) {
            Chat.add(false, "Tip: add your Gemini API key in the menu (gear icon) to get answers here.")
            openAssistant(); return
        }
        status = "Thinking..."
        history.add(Pair("user", q))
        val turns = history.takeLast(8).toList()
        Thread {
            val reply = try { callGemini(key, turns) } catch (e: Exception) { "I could not reach Gemini. Check your internet connection." }
            handler.post {
                if (!reply.startsWith("Gemini error") && !reply.startsWith("I could not")) history.add(Pair("model", reply))
                status = "Listening for \"Friday\"..."
                Chat.add(false, reply)
                speak(reply.replace("*", "").replace("#", "").take(1200), false)
            }
        }.start()
    }

    private fun callGemini(key: String, turns: List<Pair<String, String>>): String {
        val contents = JSONArray()
        for ((r, t) in turns) contents.put(JSONObject().put("role", r)
            .put("parts", JSONArray().put(JSONObject().put("text", t))))
        val sys = "You are Friday, a friendly voice assistant. Answer in one to three short sentences unless asked for more. No markdown."
        val body = JSONObject().put("contents", contents)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 30000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299
        val txt = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
        if (!ok) {
            val m = try { JSONObject(txt).getJSONObject("error").getString("message") } catch (e: Exception) { txt.take(150) }
            return "Gemini error ${c.responseCode}: $m"
        }
        return JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
    }

    // Hand over to the phone's default assistant (Google Assistant or Gemini) and stay quiet so it can use the mic
    private fun openAssistant() {
        status = "Opening your phone's assistant..."
        buzz()
        for (i in listOf(Intent(Intent.ACTION_ASSIST), Intent(Intent.ACTION_VOICE_COMMAND))) {
            try {
                go(i)
                pauseUntil = System.currentTimeMillis() + 15000
                restart(16000)
                return
            } catch (e: Exception) {}
        }
        speak("I could not open the assistant")
    }

    private fun buzz() {
        try { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (e: Exception) {}
    }

    // ---- our own offline speech engine (Vosk): no beeps, no audio focus, never pauses your music ----
    private var model: Model? = null
    private var svc: SpeechService? = null
    private var cmdMode = false

    private fun loadModel() {
        status = "Loading speech model (10-20 seconds)..."
        StorageService.unpack(this, "model-en", "model",
            StorageService.Callback<Model> { m -> model = m; status = "Model loaded"; restart(200) },
            StorageService.Callback<IOException> { e -> status = "MODEL FAILED: ${e?.message}"; say("The speech model failed to load") })
    }

    private fun stopEngine() {
        try { svc?.stop(); svc?.shutdown() } catch (e: Exception) {}
        svc = null
    }

    private fun startListening() {
        if (!running || speaking || (inCall && !oneShot)) return
        if (System.currentTimeMillis() < pauseUntil) { restart(1000); return }
        if (!alwaysOn && !oneShot && !ringing) { stopEngine(); return }
        if (svc != null) return
        val m = model ?: return
        try {
            val s = SpeechService(Recognizer(m, 16000.0f), 16000.0f)
            svc = s
            if (cmdMode || oneShot) s.startListening(vosk, 8000) else s.startListening(vosk)
            status = if (alwaysOn) "Listening for \"Friday\"..." else "Listening for your command..."
        } catch (e: Exception) { svc = null; status = "MIC ERROR: ${e.message}"; restart(3000) }
    }

    private val vosk = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(h: String?) {
            val p = try { JSONObject(h ?: "").optString("partial") } catch (e: Exception) { "" }
            if (p.isNotEmpty()) status = "Hearing: $p"
        }
        override fun onResult(h: String?) { onVoice(h) }
        override fun onFinalResult(h: String?) { onVoice(h) }
        override fun onError(e: Exception?) { status = "ENGINE ERROR: ${e?.message}"; stopEngine(); restart(3000) }
        override fun onTimeout() { cmdMode = false; oneShot = false; stopEngine(); restart(200) }
    }

    private fun onVoice(json: String?) {
        val text = try { JSONObject(json ?: "").optString("text") } catch (e: Exception) { "" }.lowercase().trim()
        if (text.isEmpty()) return
        status = "Heard: $text"
        if (ringing && Regex("answer|pick|accept|reject|decline|cut|hang").containsMatchIn(text)) {
            ringing = false; stopEngine()
            if (Regex("reject|decline|cut|hang").containsMatchIn(text)) endCall() else answer()
            restart(300); return
        }
        val i = text.indexOf("friday")
        val cmd = when {
            cmdMode || oneShot -> if (i >= 0) text.substring(i + 6).trim() else text
            i >= 0 -> text.substring(i + 6).trim()
            else -> return
        }
        stopEngine()
        if (cmd.isEmpty()) { cmdMode = true; buzz(); showUi(); restart(300); return }   // heard only "friday"
        cmdMode = false; oneShot = false
        Chat.add(true, cmd)
        try { handle(cmd) } catch (e: Exception) { speak("Sorry, that did not work") }
        if (!speaking) restart(300)
    }

    // ---- commands ----
    private fun go(i: Intent) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    private fun handle(t: String) {
        if (t.isNotEmpty() && extra(t)) return
        when {
            t.isEmpty() -> speak("Yes?")
            "goodbye" in t || "exit" in t || "stop listening" in t -> {
                running = false; speak("Goodbye")
                handler.postDelayed({ stopSelf() }, 1500)
            }
            "timer" in t -> {
                val m = Regex("(\\d+)\\s*(second|minute|hour)").find(t)
                if (m == null) { speak("For how long?"); return }
                val unit = mapOf("second" to 1, "minute" to 60, "hour" to 3600)[m.groupValues[2]]!!
                go(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, m.groupValues[1].toInt() * unit)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                speak("Timer started")
            }
            "alarm" in t -> {
                val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(t)
                if (m == null) { speak("What time?"); return }
                var h = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                val ap = m.groupValues[3].replace(".", "")
                if (ap == "pm" && h < 12) h += 12
                if (ap == "am" && h == 12) h = 0
                go(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h % 24)
                    .putExtra(AlarmClock.EXTRA_MINUTES, min)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                speak("Alarm set for %02d:%02d".format(h % 24, min))
            }
            "time" in t -> speak("It is " + SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()))
            "date" in t || "which day" in t ->
                speak("Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()))
            "battery" in t -> {
                val b = getSystemService(BatteryManager::class.java)
                speak("Battery is at ${b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent")
            }
            "flashlight" in t || "torch" in t -> {
                val on = "off" !in t
                val cm = getSystemService(CameraManager::class.java)
                cm.setTorchMode(cm.cameraIdList[0], on)
                speak(if (on) "Torch on" else "Torch off")
            }
            "volume" in t -> {
                val am = getSystemService(AudioManager::class.java)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val n = Regex("(\\d+)").find(t)?.groupValues?.get(1)?.toInt()
                when {
                    n != null -> am.setStreamVolume(AudioManager.STREAM_MUSIC, max * n.coerceIn(0, 100) / 100, AudioManager.FLAG_SHOW_UI)
                    "max" in t -> am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                    "down" in t || "low" in t -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    else -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                }
                speak("Okay")
            }
            t.startsWith("open ") || t.startsWith("launch ") -> openApp(t.substringAfter(" ").trim())
            t.startsWith("call ") -> call(t.removePrefix("call ").trim())
            t.startsWith("text ") || t.startsWith("send message to ") -> {
                val m = Regex("(?:text|send message to) (.+?) (?:saying|that|say) (.+)").find(t)
                val num = m?.let { findNumber(it.groupValues[1]) }
                if (m == null || num == null) { speak("I could not send that message"); return }
                SmsManager.getDefault().sendTextMessage(num, null, m.groupValues[2], null, null)
                speak("Message sent")
            }
            "wifi" in t || "wi-fi" in t -> { go(Intent(Settings.ACTION_WIFI_SETTINGS)); speak("Opening Wi-Fi settings") }
            "bluetooth" in t -> { go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); speak("Opening Bluetooth settings") }
            t.startsWith("search ") -> {
                go(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, t.removePrefix("search ").removePrefix("for ")))
                speak("Searching")
            }
            "hello" in t || t == "hi" -> speak("Hello! How can I help?")
            "joke" in t -> speak("Why do programmers prefer dark mode? Because light attracts bugs.")
            "help" in t || "what can you do" in t ->
                speak("I can control music, read your notifications, open apps, make and answer calls, check missed calls, text, set alarms, timers and reminders, take notes, do maths, navigate, tell the weather, flip a coin, and more.")
            else -> askGemini(t)
        }
    }

    private fun key(code: Int) {
        val am = getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun playSong(q0: String) {
        val q = q0.removeSuffix(" please").trim()
        val name = q.removeSuffix(" on youtube").removeSuffix(" on spotify")
        if (q.endsWith(" on youtube")) {
            go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(name))))
        } else {
            val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(SearchManager.QUERY, name)
            if (q.endsWith(" on spotify")) i.setPackage("com.spotify.music")
            go(i)
        }
        speak("Playing $name")
    }

    private fun readNotifications() {
        val on = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        if (!on) { speak("Please allow notification access in the Friday app first"); return }
        val l = synchronized(NotifListener.recent) { NotifListener.recent.take(5) }
        if (l.isEmpty()) speak("You have no new notifications")
        else speak("You have ${l.size} recent notifications. " + l.joinToString(". "))
    }

    // Extra Siri-style features. Returns true if the command was handled.
    private fun extra(t: String): Boolean {
        Regex("remind me in (\\d+) (second|minute|hour)s? (?:to|about) (.+)").find(t)?.let {
            val unit = mapOf("second" to 1, "minute" to 60, "hour" to 3600)[it.groupValues[2]]!!
            go(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, it.groupValues[1].toInt() * unit)
                .putExtra(AlarmClock.EXTRA_MESSAGE, it.groupValues[3])
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            speak("Okay, I will remind you"); return true
        }
        Regex("remind me at (\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)? (?:to|about) (.+)").find(t)?.let {
            var h = it.groupValues[1].toInt()
            val ap = it.groupValues[3].replace(".", "")
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            go(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h % 24)
                .putExtra(AlarmClock.EXTRA_MINUTES, it.groupValues[2].ifEmpty { "0" }.toInt())
                .putExtra(AlarmClock.EXTRA_MESSAGE, it.groupValues[4])
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            speak("Okay, I will remind you"); return true
        }
        if ("what" in t || "calculate" in t) {
            Regex("(-?\\d+(?:\\.\\d+)?)\\s*(plus|\\+|minus|-|times|x|multiplied by|\\*|divided by|over|/)\\s*(-?\\d+(?:\\.\\d+)?)").find(t)?.let {
                val a = it.groupValues[1].toDouble()
                val b = it.groupValues[3].toDouble()
                val r = when (it.groupValues[2]) {
                    "plus", "+" -> a + b
                    "minus", "-" -> a - b
                    "times", "x", "multiplied by", "*" -> a * b
                    else -> if (b == 0.0) { speak("I cannot divide by zero"); return true } else a / b
                }
                speak(if (r == Math.floor(r) && Math.abs(r) < 1e12) r.toLong().toString() else "%.4f".format(r))
                return true
            }
        }
        when {
            t.startsWith("call ") && "on speaker" in t -> {
                call(t.removePrefix("call ").replace(Regex(" on speaker(phone)?$"), "").trim())
                handler.postDelayed({ speaker(true) }, 4000); return true
            }
            "redial" in t || "call again" in t || "last number" in t -> { callLast(listOf(CallLog.Calls.OUTGOING_TYPE), "Redialing"); return true }
            "call back" in t || "return the call" in t || "return missed call" in t -> {
                callLast(listOf(CallLog.Calls.MISSED_TYPE, CallLog.Calls.INCOMING_TYPE), "Calling back"); return true
            }
            "missed call" in t || "who called" in t -> { missedCalls(); return true }
            "speaker" in t -> { speaker("off" !in t); return true }
            "hang up" in t || "end call" in t || "end the call" in t || "disconnect" in t -> { endCall(); return true }
            "answer" in t && "call" in t -> { answer(); return true }
            "pause" in t || "stop music" in t -> { key(KeyEvent.KEYCODE_MEDIA_PAUSE); return true }
            t == "play" || t == "resume" || "resume music" in t || "play music" in t -> { key(KeyEvent.KEYCODE_MEDIA_PLAY); return true }
            "next song" in t || "next track" in t || t == "skip" || "skip song" in t -> { key(KeyEvent.KEYCODE_MEDIA_NEXT); return true }
            "previous song" in t || "previous track" in t || "last song" in t -> { key(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return true }
            t.startsWith("play ") -> { playSong(t.removePrefix("play ")); return true }
            "notification" in t && "announce" in t -> {
                AssistantService.announce = "off" !in t
                speak(if (AssistantService.announce) "I will read new notifications aloud" else "Notification announcements off")
                return true
            }
            "notification" in t && "clear" in t -> { synchronized(NotifListener.recent) { NotifListener.recent.clear() }; speak("Cleared"); return true }
            "notification" in t -> { readNotifications(); return true }
            t.startsWith("note ") || t.startsWith("take a note") || t.startsWith("remember that ") -> {
                val txt = t.replace(Regex("^(take a note|note|remember that)( that)?"), "").trim()
                val p = getSharedPreferences("notes", MODE_PRIVATE)
                p.edit().putString("n", p.getString("n", "") + txt + ". ").apply()
                speak("Noted"); return true
            }
            "my notes" in t && "clear" in t -> { getSharedPreferences("notes", MODE_PRIVATE).edit().clear().apply(); speak("Notes cleared"); return true }
            "my notes" in t -> {
                val n = getSharedPreferences("notes", MODE_PRIVATE).getString("n", "") ?: ""
                speak(if (n.isEmpty()) "You have no notes" else "Your notes. $n"); return true
            }
            t.startsWith("navigate to ") || t.startsWith("directions to ") -> {
                val place = t.substringAfter(" to ")
                go(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))))
                speak("Starting navigation to $place"); return true
            }
            "weather" in t -> {
                go(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "weather today"))
                speak("Here is the weather"); return true
            }
            "take a photo" in t || "take a picture" in t || "selfie" in t -> {
                go(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); speak("Opening camera"); return true
            }
            "flip a coin" in t || "toss a coin" in t -> { speak(if (Random.nextBoolean()) "Heads" else "Tails"); return true }
            "roll a dice" in t || "roll a die" in t -> { speak("You rolled a ${Random.nextInt(1, 7)}"); return true }
            "who are you" in t || "your name" in t -> { speak("I am Friday, your offline assistant"); return true }
            "how are you" in t -> { speak("I am doing great, thanks for asking"); return true }
            "thank" in t -> { speak("You are welcome"); return true }
        }
        return false
    }

    // ---- calling ----
    private var ringing = false
    private var ringNum: String? = null

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val st = i?.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            if (st == TelephonyManager.EXTRA_STATE_RINGING) {
                val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                if (ringing && (num == null || num == ringNum)) return
                ringing = true; ringNum = num
                val who = if (num != null) nameFor(num) else "an unknown number"
                say("Incoming call from $who. Say answer or reject.")
            } else {
                ringing = false; ringNum = null
                if (st == TelephonyManager.EXTRA_STATE_OFFHOOK) { inCall = true; stopEngine(); muteBeep(false) }
                else { inCall = false; if (alwaysOn) restart(1000) else stopEngine() }
            }
        }
    }

    private fun nameFor(num: String): String {
        try {
            contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (e: Exception) {}
        return num.toCharArray().joinToString(" ")
    }

    @SuppressLint("MissingPermission")
    private fun dial(num: String) {
        getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", num, null), Bundle())
    }

    private fun lastCall(types: List<Int>): Pair<String, String>? {
        val sel = types.joinToString(" OR ") { "${CallLog.Calls.TYPE}=$it" }
        contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME), sel, null,
            "${CallLog.Calls.DATE} DESC")?.use {
            if (it.moveToFirst()) return Pair(it.getString(0) ?: "", it.getString(1) ?: "")
        }
        return null
    }

    private fun callLast(types: List<Int>, label: String) {
        val c = lastCall(types)
        if (c == null || c.first.isEmpty()) { speak("I could not find a number"); return }
        speak("$label " + c.second.ifEmpty { nameFor(c.first) })
        dial(c.first)
    }

    private fun missedCalls() {
        val out = ArrayList<String>()
        contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME),
            "${CallLog.Calls.TYPE}=${CallLog.Calls.MISSED_TYPE}", null,
            "${CallLog.Calls.DATE} DESC")?.use {
            while (it.moveToNext() && out.size < 5) {
                val n = it.getString(1) ?: ""
                out.add(n.ifEmpty { nameFor(it.getString(0) ?: "") })
            }
        }
        speak(if (out.isEmpty()) "You have no missed calls" else "Missed calls from " + out.joinToString(", "))
    }

    @SuppressLint("MissingPermission")
    private fun answer() {
        try { getSystemService(TelecomManager::class.java).acceptRingingCall() }
        catch (e: Exception) { speak("I could not answer the call") }
    }

    @SuppressLint("MissingPermission")
    private fun endCall() {
        if (Build.VERSION.SDK_INT < 28) { speak("Ending calls needs Android 9 or newer"); return }
        try { getSystemService(TelecomManager::class.java).endCall() }
        catch (e: Exception) { speak("I could not end the call") }
    }

    private fun speaker(on: Boolean) {
        val am = getSystemService(AudioManager::class.java)
        if (am.mode != AudioManager.MODE_IN_CALL && am.mode != AudioManager.MODE_IN_COMMUNICATION) {
            speak("You are not on a call"); return
        }
        am.isSpeakerphoneOn = on
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = pm.queryIntentActivities(li, 0)
            .firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name) }
        val launch = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (launch == null) { speak("I could not find $name"); return }
        go(launch); speak("Opening $name")
    }

    private fun findNumber(name: String): String? {
        if (name.matches(Regex("[\\d\\s+]+"))) return name.replace(" ", "")
        contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun call(name: String) {
        val num = findNumber(name)
        if (num == null) { speak("I could not find $name"); return }
        speak("Calling $name")
        getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", num, null), Bundle())
    }

    override fun onDestroy() {
        running = false
        instance = null
        try { unregisterReceiver(callReceiver) } catch (e: Exception) {}
        muteBeep(false)
        handler.removeCallbacksAndMessages(null)
        stopEngine()
        model?.close()
        tts?.shutdown()
        super.onDestroy()
    }
}
