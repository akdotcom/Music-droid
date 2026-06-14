package com.akdotcom.musicdroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button

    private lateinit var mp3UrlEditText: EditText
    private lateinit var generatedUriTextView: TextView
    private lateinit var copyUriButton: Button

    private lateinit var uriEditText: EditText
    private lateinit var triggerButton: Button
    private lateinit var backButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        loginButton = findViewById(R.id.loginButton)
        logoutButton = findViewById(R.id.logoutButton)
        mp3UrlEditText = findViewById(R.id.mp3UrlEditText)
        generatedUriTextView = findViewById(R.id.generatedUriTextView)
        copyUriButton = findViewById(R.id.copyUriButton)
        uriEditText = findViewById(R.id.uriEditText)
        triggerButton = findViewById(R.id.triggerButton)
        backButton = findViewById(R.id.backButton)

        loginButton.setOnClickListener { sendAction("login") }
        logoutButton.setOnClickListener { sendAction("logout") }

        mp3UrlEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                generatedUriTextView.text = UriUtils.smartConvert(s.toString().trim())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        copyUriButton.setOnClickListener {
            val textToCopy = generatedUriTextView.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("musicdroid URI", textToCopy))
            Toast.makeText(this, "URI copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        triggerButton.setOnClickListener {
            val uriStr = uriEditText.text.toString()
            if (uriStr.isNotEmpty()) {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    data = Uri.parse(uriStr)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
        }

        backButton.setOnClickListener { finish() }
    }

    private fun sendAction(action: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("action", action)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }
}
