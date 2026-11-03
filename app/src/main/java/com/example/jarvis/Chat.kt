package com.example.jarvis

import android.os.Handler
import android.os.Looper

object Chat {
    val msgs = ArrayList<Pair<Boolean, String>>()
    @Volatile var onChange: (() -> Unit)? = null

    fun add(user: Boolean, text: String) {
        synchronized(msgs) {
            msgs.add(Pair(user, text))
            if (msgs.size > 100) msgs.removeAt(0)
        }
        Handler(Looper.getMainLooper()).post { onChange?.invoke() }
    }
}
