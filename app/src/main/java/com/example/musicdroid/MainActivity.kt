package com.example.musicdroid

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var uriEditText: EditText
    private lateinit var triggerButton: Button
    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        uriEditText = findViewById(R.id.uriEditText)
        triggerButton = findViewById(R.id.triggerButton)
        statusTextView = findViewById(R.id.statusTextView)

        triggerButton.setOnClickListener {
            val uriString = uriEditText.text.toString()
            if (uriString.isNotEmpty()) {
                launchSpotify(uriString)
            } else {
                Toast.makeText(this, "Please enter a URI", Toast.LENGTH_SHORT).show()
            }
        }

        // Check if the activity was launched by an NFC tag
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == action) {
            val rawMessages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            }

            if (rawMessages != null) {
                val messages = Array(rawMessages.size) { i -> rawMessages[i] as NdefMessage }
                processNdefMessages(messages)
            }
        }
    }

    private fun processNdefMessages(messages: Array<NdefMessage>) {
        for (message in messages) {
            for (record in message.records) {
                val payload = record.toUri()
                if (payload != null) {
                    val uriString = payload.toString()
                    statusTextView.text = "NFC Tag Detected: $uriString"
                    launchSpotify(uriString)
                    return
                }
            }
        }
    }

    private fun launchSpotify(uriString: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No app found to handle this URI", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error launching URI: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
