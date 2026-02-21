package com.akdotcom.musicdroid

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse

class MainActivity : AppCompatActivity() {

    private val CLIENT_ID = "c17ce31e345e4449a860aa76fae70f14"
    private val REDIRECT_URI = "com.akdotcom.musicdroid://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var pendingUri: String? = null

    private lateinit var uriEditText: EditText
    private lateinit var triggerButton: Button
    private lateinit var statusTextView: TextView

    private lateinit var authLauncher: ActivityResultLauncher<Intent>

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

        authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val response = AuthorizationClient.getResponse(result.resultCode, result.data)
            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    Log.d("MainActivity", "Auth success, connecting to Spotify")
                    connectToSpotify()
                }
                AuthorizationResponse.Type.ERROR -> {
                    Log.e("MainActivity", "Auth error: ${response.error}")
                    statusTextView.text = "Status: Auth Error: ${response.error}"
                    Toast.makeText(this, "Auth failed: ${response.error}", Toast.LENGTH_LONG).show()
                }
                else -> {
                    Log.d("MainActivity", "Auth flow cancelled or other")
                    statusTextView.text = "Status: Auth Cancelled"
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connectToSpotify()
    }

    override fun onStop() {
        super.onStop()
        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
            spotifyAppRemote = null
        }
    }

    private fun connectToSpotify() {
        if (CLIENT_ID == "YOUR_CLIENT_ID") {
            val errorMsg = "CLIENT_ID is not set! Please set your Spotify Client ID in MainActivity.kt"
            Log.e("MainActivity", errorMsg)
            statusTextView.text = "Status: Error - Client ID not set"
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            return
        }

        val connectionParams = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                spotifyAppRemote = appRemote
                Log.d("MainActivity", "Connected to Spotify App Remote")
                statusTextView.text = "Status: Connected to Spotify"
                pendingUri?.let {
                    launchSpotify(it)
                    pendingUri = null
                }
            }

            override fun onFailure(throwable: Throwable) {
                Log.e("MainActivity", "Failed to connect to Spotify App Remote: ${throwable.message}", throwable)
                statusTextView.text = "Status: Connection Failed: ${throwable.message}"

                val errorMessage = throwable.message ?: ""
                if (errorMessage.contains("Explicit user authorization is required", ignoreCase = true)) {
                    Log.d("MainActivity", "Explicit user authorization required, triggering auth flow")
                    triggerAuthFlow()
                } else {
                    val userFriendlyError = when {
                        errorMessage.contains("Could not find Spotify app", ignoreCase = true) ->
                            "Spotify app not found or not installed."
                        errorMessage.contains("UserNotAuthorizedException", ignoreCase = true) ->
                            "User not authorized. Check Client ID, Redirect URI, and SHA1 in Spotify Dashboard."
                        else -> "Connection failed: $errorMessage"
                    }
                    Toast.makeText(this@MainActivity, userFriendlyError, Toast.LENGTH_LONG).show()
                    pendingUri = null // Clear pending URI on failure
                }
            }
        })
    }

    private fun triggerAuthFlow() {
        val builder = AuthorizationRequest.Builder(CLIENT_ID, AuthorizationResponse.Type.TOKEN, REDIRECT_URI)
        builder.setScopes(arrayOf("streaming", "app-remote-control"))
        val request = builder.build()
        val intent = AuthorizationClient.createLoginActivityIntent(this, request)
        authLauncher.launch(intent)
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
        val spotifyUri = convertToSpotifyUri(uriString)
        val remote = spotifyAppRemote
        if (remote != null && remote.isConnected) {
            remote.playerApi.play(spotifyUri)
                .setResultCallback {
                    Log.d("MainActivity", "Successfully started playing: $spotifyUri")
                }
                .setErrorCallback { throwable ->
                    Log.e("MainActivity", "Error playing URI: ${throwable.message}", throwable)
                    Toast.makeText(this, "Error playing: ${throwable.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            pendingUri = spotifyUri
            Toast.makeText(this, "Connecting to Spotify...", Toast.LENGTH_SHORT).show()
            connectToSpotify()
        }
    }

    private fun convertToSpotifyUri(uriString: String): String {
        if (uriString.startsWith("https://open.spotify.com/")) {
            try {
                val uri = Uri.parse(uriString)
                val segments = uri.pathSegments
                if (segments.size >= 2) {
                    val type = segments[0]
                    val id = segments[1]
                    return "spotify:$type:$id"
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to parse Spotify web URL: $uriString", e)
            }
        }
        return uriString
    }
}
