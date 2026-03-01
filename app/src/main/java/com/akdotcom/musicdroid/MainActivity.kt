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
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.PlayerState
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private val CLIENT_ID = "c17ce31e345e4449a860aa76fae70f14"
    private val REDIRECT_SCHEME = "com.akdotcom.musicdroid"
    private val REDIRECT_URI = "$REDIRECT_SCHEME://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var exoPlayer: ExoPlayer? = null
    private var pendingUri: String? = null

    private var lastRequestedSpotifyUri: String? = null
    private var lastRequestedExoPlayerUri: String? = null

    private var isConnecting = false
    private var lastAuthAttemptTime: Long = 0
    private val AUTH_COOLDOWN_MS = 10000 // 10 seconds cooldown to prevent loops

    private lateinit var statusTextView: TextView
    private lateinit var artworkImageView: ImageView
    private lateinit var trackTitleTextView: TextView
    private lateinit var artistTextView: TextView
    private lateinit var albumTextView: TextView

    private lateinit var settingsButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var nextButton: ImageButton

    private lateinit var authLauncher: ActivityResultLauncher<Intent>

    private val connectionHandler = Handler(Looper.getMainLooper())
    private val connectionTimeoutRunnable = Runnable {
        if (isConnecting) {
            isConnecting = false
            Log.e("MainActivity", "Connection timeout reached!")
            statusTextView.text = "Status: Connection Timeout - Spotify app not responding"
            Toast.makeText(this, "Connection timeout. Is Spotify app open and logged in?", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideSystemUI()

        statusTextView = findViewById(R.id.statusTextView)
        artworkImageView = findViewById(R.id.artworkImageView)
        trackTitleTextView = findViewById(R.id.trackTitleTextView)
        artistTextView = findViewById(R.id.artistTextView)
        albumTextView = findViewById(R.id.albumTextView)

        settingsButton = findViewById(R.id.settingsButton)
        prevButton = findViewById(R.id.prevButton)
        playPauseButton = findViewById(R.id.playPauseButton)
        nextButton = findViewById(R.id.nextButton)

        initializeExoPlayer()

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
                }
                else -> {
                    Log.d("MainActivity", "Auth flow cancelled or other: ${response.type}")
                    statusTextView.text = "Status: Auth ${response.type ?: "Unknown"}"
                }
            }
        }

        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        prevButton.setOnClickListener {
            spotifyAppRemote?.playerApi?.skipPrevious()
        }

        playPauseButton.setOnClickListener {
            spotifyAppRemote?.let { remote ->
                remote.playerApi.playerState.setResultCallback { playerState ->
                    if (playerState.isPaused) {
                        remote.playerApi.resume()
                    } else {
                        remote.playerApi.pause()
                    }
                }
            }

            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else if (player.playbackState != Player.STATE_IDLE) {
                    player.play()
                }
            }
        }

        nextButton.setOnClickListener {
            spotifyAppRemote?.playerApi?.skipNext()
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
        releaseExoPlayer()
        lastRequestedSpotifyUri = null
        lastRequestedExoPlayerUri = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun initializeExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    updateMetadataUI(mediaMetadata)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseButton(isPlaying)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> Log.d("MainActivity", "ExoPlayer ready")
                        Player.STATE_ENDED -> {
                            Log.d("MainActivity", "ExoPlayer ended")
                            lastRequestedExoPlayerUri = null
                        }
                        Player.STATE_BUFFERING -> Log.d("MainActivity", "ExoPlayer buffering")
                        Player.STATE_IDLE -> {
                            Log.d("MainActivity", "ExoPlayer idle")
                            lastRequestedExoPlayerUri = null
                        }
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("MainActivity", "ExoPlayer error: ${error.message}", error)
                    Toast.makeText(this@MainActivity, "Playback error: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun releaseExoPlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun updateMetadataUI(metadata: MediaMetadata) {
        val title = metadata.title ?: metadata.displayTitle
        val artist = metadata.artist ?: metadata.albumArtist
        val album = metadata.albumTitle

        trackTitleTextView.text = title ?: "Unknown Track"
        artistTextView.text = artist ?: "Unknown Artist"
        albumTextView.text = album ?: ""

        if (metadata.artworkUri != null) {
            artworkImageView.load(metadata.artworkUri)
        } else if (metadata.artworkData != null) {
            artworkImageView.load(metadata.artworkData)
        } else {
            artworkImageView.setImageResource(android.R.drawable.ic_menu_report_image)
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) {
            playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun isSpotifyInstalled(): Boolean {
        val packages = listOf("com.spotify.music", "com.spotify.lite")
        var found = false
        for (packageName in packages) {
            try {
                packageManager.getPackageInfo(packageName, 0)
                Log.d("MainActivity", "Spotify package found: $packageName")
                found = true
            } catch (e: PackageManager.NameNotFoundException) {
                // Not found
            }
        }
        return found
    }

    private fun connectToSpotify() {
        if (!isSpotifyInstalled()) {
            statusTextView.text = "Status: Spotify not installed"
            return
        }

        if (isConnecting) return

        isConnecting = true
        Log.d("MainActivity", "Connecting to Spotify...")
        statusTextView.text = "Status: Connecting..."

        connectionHandler.removeCallbacks(connectionTimeoutRunnable)
        connectionHandler.postDelayed(connectionTimeoutRunnable, 15000)

        val connectionParams = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, connectionParams, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                isConnecting = false
                connectionHandler.removeCallbacks(connectionTimeoutRunnable)
                spotifyAppRemote = appRemote
                Log.d("MainActivity", "Connected to Spotify App Remote")
                statusTextView.text = "Status: Connected"

                subscribeToPlayerState()

                pendingUri?.let {
                    launchSpotify(it)
                    pendingUri = null
                }
            }

            override fun onFailure(throwable: Throwable) {
                isConnecting = false
                connectionHandler.removeCallbacks(connectionTimeoutRunnable)
                Log.e("MainActivity", "Failed to connect to Spotify App Remote: ${throwable.message}")

                val errorMessage = throwable.message ?: "Unknown error"
                statusTextView.text = "Status: Connection Failed: $errorMessage"

                if (errorMessage.contains("Explicit user authorization is required", ignoreCase = true)) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAuthAttemptTime > AUTH_COOLDOWN_MS) {
                        lastAuthAttemptTime = currentTime
                        triggerAuthFlow()
                    }
                }
            }
        })
    }

    private fun subscribeToPlayerState() {
        spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            updateSpotifyUI(playerState)
        }
    }

    private fun updateSpotifyUI(playerState: PlayerState) {
        val track = playerState.track
        if (track != null) {
            trackTitleTextView.text = track.name
            artistTextView.text = track.artist.name
            albumTextView.text = track.album.name

            spotifyAppRemote?.imagesApi?.getImage(track.imageUri)?.setResultCallback { bitmap ->
                artworkImageView.setImageBitmap(bitmap)
            }

            updatePlayPauseButton(!playerState.isPaused)
        }
    }

    private fun triggerAuthFlow() {
        val request = AuthorizationRequest.Builder(CLIENT_ID, AuthorizationResponse.Type.TOKEN, REDIRECT_URI)
            .setScopes(arrayOf("streaming", "app-remote-control", "user-read-playback-state", "user-modify-playback-state"))
            .build()
        val intent = AuthorizationClient.createLoginActivityIntent(this, request)
        try {
            authLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Could not launch auth intent", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.getStringExtra("action")
        if (action == "reconnect") {
            isConnecting = false
            connectToSpotify()
            return
        } else if (action == "auth") {
            triggerAuthFlow()
            return
        }

        val uri = intent.data
        if (uri != null && uri.scheme == "musicdroid") {
            processMusicDroidUri(uri.toString())
            return
        }

        if (uri != null && uri.scheme == REDIRECT_SCHEME && uri.host == "callback") {
            connectToSpotify()
            return
        }

        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {
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
        } else if (uri != null) {
            // Probably from SettingsActivity manual trigger
            launchSpotify(uri.toString())
        }
    }

    private fun processNdefMessages(messages: Array<NdefMessage>) {
        for (message in messages) {
            for (record in message.records) {
                val payload = record.toUri()
                if (payload != null) {
                    launchSpotify(payload.toString())
                    return
                }
            }
        }
    }

    private fun launchSpotify(uriString: String) {
        if (uriString.startsWith("musicdroid:")) {
            processMusicDroidUri(uriString)
            return
        }

        val spotifyUri = convertToSpotifyUri(uriString)

        // Check if this is already playing
        if (spotifyUri == lastRequestedSpotifyUri) {
            Log.d("MainActivity", "Ignore duplicate Spotify URI: $spotifyUri")
            return
        }

        lastRequestedSpotifyUri = spotifyUri
        lastRequestedExoPlayerUri = null

        exoPlayer?.stop()

        val remote = spotifyAppRemote
        if (remote != null && remote.isConnected) {
            remote.playerApi.play(spotifyUri)
        } else {
            pendingUri = spotifyUri
            connectToSpotify()
        }
    }

    private fun processMusicDroidUri(uriString: String) {
        val uri = Uri.parse(uriString)
        val mp3Url = uri.getQueryParameter("url") ?: uri.path?.substringAfter("/") ?: uri.host

        if (mp3Url != null && (mp3Url.startsWith("http://") || mp3Url.startsWith("https://"))) {
            if (mp3Url == lastRequestedExoPlayerUri) {
                Log.d("MainActivity", "Ignore duplicate musicdroid URI: $mp3Url")
                return
            }

            lastRequestedExoPlayerUri = mp3Url
            lastRequestedSpotifyUri = null

            if (mp3Url.lowercase().contains(".pls")) {
                playPls(mp3Url)
            } else {
                playMp3(mp3Url)
            }
        }
    }

    private fun playPls(url: String) {
        statusTextView.text = "Status: Parsing PLS..."
        lifecycleScope.launch {
            val streamUrl = withContext(Dispatchers.IO) {
                parsePls(url)
            }
            if (streamUrl != null) {
                playMp3(streamUrl)
            } else {
                statusTextView.text = "Status: Failed to parse PLS"
                Toast.makeText(this@MainActivity, "Failed to parse PLS playlist", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun parsePls(url: String): String? {
        return try {
            val content = URL(url).readText()
            Log.d("MainActivity", "PLS Content: $content")
            PlsParser.parse(content)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching PLS", e)
            null
        }
    }

    private fun playMp3(url: String) {
        spotifyAppRemote?.playerApi?.pause()

        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
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
        getCertificateSHA1Fingerprint()?.let { Log.i("MainActivity", "Your SHA1 Fingerprint: $it") }
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
