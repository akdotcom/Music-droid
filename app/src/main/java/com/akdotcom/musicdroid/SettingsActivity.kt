package com.akdotcom.musicdroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest

class SettingsActivity : AppCompatActivity() {

    private lateinit var mp3UrlEditText: EditText
    private lateinit var generatedUriTextView: TextView
    private lateinit var copyUriButton: Button

    private lateinit var uriEditText: EditText
    private lateinit var triggerButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var authButton: Button
    private lateinit var sha1TextView: TextView
    private lateinit var backButton: Button
    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        mp3UrlEditText = findViewById(R.id.mp3UrlEditText)
        generatedUriTextView = findViewById(R.id.generatedUriTextView)
        copyUriButton = findViewById(R.id.copyUriButton)

        uriEditText = findViewById(R.id.uriEditText)
        triggerButton = findViewById(R.id.triggerButton)
        reconnectButton = findViewById(R.id.reconnectButton)
        authButton = findViewById(R.id.authButton)
        sha1TextView = findViewById(R.id.sha1TextView)
        backButton = findViewById(R.id.backButton)
        statusTextView = findViewById(R.id.settingsStatusTextView)

        val sha1 = getCertificateSHA1Fingerprint()
        sha1TextView.text = sha1 ?: "Could not retrieve SHA1"

        mp3UrlEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val url = s.toString().trim()
                val encodedUrl = Uri.encode(url)
                val generatedUri = "musicdroid://play?url=$encodedUrl"
                generatedUriTextView.text = generatedUri
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        copyUriButton.setOnClickListener {
            val textToCopy = generatedUriTextView.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("musicdroid URI", textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "URI copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        triggerButton.setOnClickListener {
            val uriStr = uriEditText.text.toString()
            if (uriStr.isNotEmpty()) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    data = Uri.parse(uriStr)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        }

        reconnectButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("action", "reconnect")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }

        authButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("action", "auth")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }

        backButton.setOnClickListener {
            finish()
        }
    }

    private fun getCertificateSHA1Fingerprint(): String? {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures != null && signatures.isNotEmpty()) {
                val md = MessageDigest.getInstance("SHA-1")
                val publicKey = signatures[0].toByteArray()
                val fingerprint = md.digest(publicKey)
                return fingerprint.joinToString(":") { String.format("%02X", it) }.uppercase()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
