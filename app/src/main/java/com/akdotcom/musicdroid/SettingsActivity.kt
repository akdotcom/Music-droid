package com.akdotcom.musicdroid

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest

class SettingsActivity : AppCompatActivity() {

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

        uriEditText = findViewById(R.id.uriEditText)
        triggerButton = findViewById(R.id.triggerButton)
        reconnectButton = findViewById(R.id.reconnectButton)
        authButton = findViewById(R.id.authButton)
        sha1TextView = findViewById(R.id.sha1TextView)
        backButton = findViewById(R.id.backButton)
        statusTextView = findViewById(R.id.settingsStatusTextView)

        val sha1 = getCertificateSHA1Fingerprint()
        sha1TextView.text = sha1 ?: "Could not retrieve SHA1"

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
