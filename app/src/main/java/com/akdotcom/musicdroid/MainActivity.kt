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
import android.content.pm.PackageManager
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
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private val CLIENT_ID = "c17ce31e345e4449a860aa76fae70f14"
    private val REDIRECT_SCHEME = "com.akdotcom.musicdroid"
    private val REDIRECT_URI = "$REDIRECT_SCHEME://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var pendingUri: String? = null

    private var isConnecting = false
    private var lastAuthAttemptTime: Long = 0
    private val AUTH_COOLDOWN_MS = 10000 // 10 seconds cooldown to prevent loops

    private lateinit var uriEditText: EditText
    private lateinit var triggerButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var sha1TextView: TextView

    private lateinit var authLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        uriEditText = findViewById(R.id.uriEditText)
        triggerButton = findViewById(R.id.triggerButton)
        statusTextView = findViewById(R.id.statusTextView)
        sha1TextView = findViewById(R.id.sha1TextView)

        val sha1 = getCertificateSHA1Fingerprint()
        sha1TextView.text = sha1 ?: "Could not retrieve SHA1"

        authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val response = AuthorizationClient.getResponse(result.resultCode, result.data)
            Log.d("MainActivity", "ActivityResult: resultCode=${result.resultCode}")
            Log.d("MainActivity", "Auth response: type=${response.type}, error=${response.error}, state=${response.state}")

            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    Log.d("MainActivity", "Auth success via app, connecting to Spotify")
                    statusTextView.text = "Status: Auth Success, connecting..."
                    connectToSpotify()
                }
                AuthorizationResponse.Type.ERROR -> {
                    Log.e("MainActivity", "Auth error: ${response.error}")
                    statusTextView.text = "Status: Auth Error: ${response.error}"
                    Toast.makeText(this, "Auth failed: ${response.error}", Toast.LENGTH_LONG).show()
                    // Browser fallback disabled per request to debug app-based flow
                    /*
                    if (response.error == "AUTHENTICATION_SERVICE_UNAVAILABLE") {
                        Log.d("MainActivity", "Auth service unavailable (Spotify app issue), falling back to browser")
                        statusTextView.text = "Status: Auth Service Unavailable, trying browser..."
                        triggerBrowserAuthFlow()
                    }
                    */
                }
                else -> {
                    Log.d("MainActivity", "Auth flow cancelled or other: ${response.type}")
                    statusTextView.text = "Status: Auth ${response.type ?: "Unknown"}"
                }
            }
        }

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

        // Log SHA1 for debugging purposes
        logSHA1Fingerprint()
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
        if (isConnecting) {
            Log.d("MainActivity", "Connection attempt already in progress, skipping")
            return
        }

        if (CLIENT_ID == "YOUR_CLIENT_ID") {
            val errorMsg = "CLIENT_ID is not set! Please set your Spotify Client ID in MainActivity.kt"
            Log.e("MainActivity", errorMsg)
            statusTextView.text = "Status: Error - Client ID not set"
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            return
        }

        isConnecting = true
        Log.d("MainActivity", "Connecting to Spotify...")

        val connectionParams = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                isConnecting = false
                spotifyAppRemote = appRemote
                Log.d("MainActivity", "Connected to Spotify App Remote")
                statusTextView.text = "Status: Connected to Spotify"
                pendingUri?.let {
                    launchSpotify(it)
                    pendingUri = null
                }
            }

            override fun onFailure(throwable: Throwable) {
                isConnecting = false
                Log.e("MainActivity", "Failed to connect to Spotify App Remote: ${throwable.message}", throwable)
                Log.e("MainActivity", "Throwable type: ${throwable.javaClass.name}")

                val errorMessage = throwable.message ?: "Unknown error"
                statusTextView.text = "Status: Connection Failed: $errorMessage"

                if (errorMessage.contains("Explicit user authorization is required", ignoreCase = true)) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAuthAttemptTime > AUTH_COOLDOWN_MS) {
                        Log.d("MainActivity", "Explicit user authorization required, triggering auth flow")
                        lastAuthAttemptTime = currentTime
                        triggerAuthFlow()
                    } else {
                        Log.w("MainActivity", "Auth flow recently attempted, avoiding loop. Check SHA1 and Redirect URI.")
                        val sha1 = getCertificateSHA1Fingerprint()
                        statusTextView.text = "Status: Auth required. Verify SHA1 in Dashboard: $sha1"
                        Toast.makeText(this@MainActivity, "Auth loop prevented. Please check your Spotify Dashboard settings.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val userFriendlyError = when {
                        errorMessage.contains("Could not find Spotify app", ignoreCase = true) ->
                            "Spotify app not found or not installed."
                        errorMessage.contains("UserNotAuthorizedException", ignoreCase = true) ||
                        errorMessage.contains("not authorized", ignoreCase = true) ->
                            "User not authorized. Please ensure your SHA1 fingerprint and Redirect URI are registered in the Spotify Developer Dashboard."
                        else -> "Connection failed: $errorMessage"
                    }
                    Log.e("MainActivity", "Connection failed with user-friendly error: $userFriendlyError")
                    Toast.makeText(this@MainActivity, userFriendlyError, Toast.LENGTH_LONG).show()
                    pendingUri = null // Clear pending URI on failure
                }
            }
        })
    }

    private fun getAuthRequest(): AuthorizationRequest {
        return AuthorizationRequest.Builder(CLIENT_ID, AuthorizationResponse.Type.TOKEN, REDIRECT_URI)
            .setScopes(arrayOf("streaming", "app-remote-control", "user-read-playback-state", "user-modify-playback-state"))
            .build()
    }

    private fun triggerAuthFlow() {
        val request = getAuthRequest()

        Log.d("MainActivity", "Triggering app-based auth flow")
        val intent = AuthorizationClient.createLoginActivityIntent(this, request)

        // Log if the intent can be resolved
        val componentName = intent.resolveActivity(packageManager)
        Log.d("MainActivity", "Auth intent resolved to: $componentName")

        try {
            authLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not launch auth intent", e)
            statusTextView.text = "Status: Error - Could not launch auth intent: ${e.message}"
            // triggerBrowserAuthFlow() // Browser fallback disabled per request
        }
    }

    private fun triggerBrowserAuthFlow() {
        val request = getAuthRequest()
        AuthorizationClient.openLoginInBrowser(this, request)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        // Handle Spotify Auth response from browser
        val uri = intent.data
        if (uri != null && uri.scheme == REDIRECT_SCHEME && uri.host == "callback") {
            Log.d("MainActivity", "Received auth callback intent: $uri")
            val response = AuthorizationResponse.fromUri(uri)
            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    Log.d("MainActivity", "Browser auth success, token received")
                    statusTextView.text = "Status: Browser Auth Success, connecting..."
                    connectToSpotify()
                }
                AuthorizationResponse.Type.ERROR -> {
                    Log.e("MainActivity", "Browser auth error: ${response.error}")
                    statusTextView.text = "Status: Browser Auth Error: ${response.error}"
                    Toast.makeText(this, "Browser auth failed: ${response.error}", Toast.LENGTH_LONG).show()
                }
                else -> {
                    Log.d("MainActivity", "Browser auth cancelled or other: ${response.type}")
                    statusTextView.text = "Status: Browser Auth ${response.type}"
                }
            }
            return
        }

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

    private fun logSHA1Fingerprint() {
        val sha1 = getCertificateSHA1Fingerprint()
        if (sha1 != null) {
            Log.i("MainActivity", "Your SHA1 Fingerprint: $sha1")
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
            Log.e("MainActivity", "Error getting SHA1 fingerprint", e)
        }
        return null
    }
}
