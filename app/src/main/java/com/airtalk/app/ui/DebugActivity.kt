package com.airtalk.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.airtalk.app.R
import com.airtalk.app.util.DebugLog

class DebugActivity : AppCompatActivity() {
    private lateinit var logText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            logText.text = DebugLog.dump()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        logText = findViewById(R.id.logText)
        findViewById<Button>(R.id.clearBtn).setOnClickListener { DebugLog.clear() }
        findViewById<Button>(R.id.copyBtn).setOnClickListener {
            val text = DebugLog.dump()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AirTALK debug log", text))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }
        handler.post(refresh)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }
}
