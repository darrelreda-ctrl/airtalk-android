package com.airtalk.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.airtalk.app.AirTalkApp
import com.airtalk.app.FilterStore
import com.airtalk.app.R
import com.airtalk.app.model.FilterConfig
import com.airtalk.app.rtc.CallListener
import com.airtalk.app.rtc.CallState
import com.airtalk.app.ui.DebugActivity

class MainActivity : AppCompatActivity(), CallListener {

    private val app by lazy { application as AirTalkApp }
    private lateinit var statusText: TextView
    private lateinit var onlineText: TextView
    private lateinit var startButton: Button

    private val genderGroup: RadioGroup by lazy { findViewById(R.id.genderGroup) }
    private val strictCheck: CheckBox by lazy { findViewById(R.id.strictCheck) }
    private val callbackCheck: CheckBox by lazy { findViewById(R.id.callbackCheck) }
    private val interestsInput: EditText by lazy { findViewById(R.id.interestsInput) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        onlineText = findViewById(R.id.onlineText)
        startButton = findViewById(R.id.startButton)

        startButton.setOnClickListener {
            if (checkMicPermission()) startCall()
        }

        findViewById<Button>(R.id.debugButton).setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        app.callManager.listener = this
        statusText.text = "Connected"
        onlineText.text = ""
    }

    override fun onPause() {
        super.onPause()
        app.callManager.listener = null
    }

    private fun checkMicPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCall()
        } else {
            Toast.makeText(this, "Microphone permission is required to talk", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCall() {
        val gender = when (genderGroup.checkedRadioButtonId) {
            R.id.genderMale -> "MALE"
            R.id.genderFemale -> "FEMALE"
            else -> "ANY"
        }
        val interests = interestsInput.text.toString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        FilterStore.config = FilterConfig(
            preferredGender = gender,
            strict = strictCheck.isChecked,
            allowCallback = callbackCheck.isChecked,
            interests = interests
        )
        app.signaling.sendFilterUpdate()
        app.callManager.startSearching()
        startActivity(Intent(this, CallActivity::class.java))
    }

    // ---------- CallListener (socket status shown here while on home screen) ----------

    override fun onSocketStatus(status: String) {
        statusText.text = status
    }

    override fun onOnlineCount(count: Int) {
        onlineText.text = "$count online"
    }

    override fun onStateChanged(state: CallState, extra: String) {
        if (state != CallState.IDLE) {
            startActivity(Intent(this, CallActivity::class.java))
        }
    }
}