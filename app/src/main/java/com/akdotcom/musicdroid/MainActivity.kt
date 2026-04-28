package com.akdotcom.musicdroid

import android.app.PendingIntent
import android.content.Context
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
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private var clientId: String? = null
    private val REDIRECT_SCHEME = "com.akdotcom.musicdroid"
    private val REDIRECT_URI = "$REDIRECT_SCHEME://callback"
    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var exoPlayer: ExoPlayer? = null
    private var pendingUri: String? = null

    private var lastRequestedSpotifyUri: String? = null
    private var lastRequestedExoPlayerUri: String? = null

    private var currentSpotifyContextUri: String? = null
    private var currentSpotifyTrackUri: String? = null
    private var playerStateReceived = false
    private var playerContextReceived = false

    private var playerStateSubscription: Subscription<PlayerState>? = null
    private var playerContextSubscription: Subscription<PlayerContext>? = null

    private var isConnecting = false
    private var lastAuthAttemptTime: Long = 0
    private val AUTH_COOLDOWN_MS = 10000 // 10 seconds cooldown to prevent loops

    private lateinit var statusTextView: TextView
    private lateinit var idleTextView: TextView
    private lateinit var artworkImageView: ImageView
    private lateinit var trackTitleTextView: TextView
    private lateinit var artistTextView: TextView
    private lateinit var albumTextView: TextView
    private lateinit var metadataContainer: View
    private lateinit var controlsContainer: View

    private lateinit var settingsButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var nextButton: ImageButton

    private lateinit var authLauncher: ActivityResultLauncher<Intent>

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

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
        idleTextView = findViewById(R.id.idleTextView)
        artworkImageView = findViewById(R.id.artworkImageView)
        trackTitleTextView = findViewById(R.id.trackTitleTextView)
        artistTextView = findViewById(R.id.artistTextView)
        albumTextView = findViewById(R.id.albumTextView)
        metadataContainer = findViewById(R.id.metadataContainer)
        controlsContainer = findViewById(R.id.controlsContainer)

        settingsButton = findViewById(R.id.settingsButton)
        prevButton = findViewById(R.id.prevButton)
        playPauseButton = findViewById(R.id.playPauseButton)
        nextButton = findViewById(R.id.nextButton)

        initializeExoPlayer()
        updateIdleState()

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
        // Only handle intent if this is not a configuration change (rotation)
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        // Log SHA1 for debugging purposes
        logSHA1Fingerprint()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
    }

    override fun onStart() {
        super.onStart()
        initializeExoPlayer()
        connectToSpotify()
    }

    override fun onResume() {
        super.onResume()
        val nfc = nfcAdapter
        val pending = pendingIntent
        if (nfc != null && pending != null) {
            nfc.enableForegroundDispatch(this, pending, null, null)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onStop() {
        super.onStop()
        playerStateSubscription?.cancel()
        playerStateSubscription = null
        playerContextSubscription?.cancel()
        playerContextSubscription = null

        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
            spotifyAppRemote = null
        }
        releaseExoPlayer()
        lastRequestedSpotifyUri = null
        lastRequestedExoPlayerUri = null
        currentSpotifyContextUri = null
        currentSpotifyTrackUri = null
        playerStateReceived = false
        playerContextReceived = false
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
        if (exoPlayer != null) return

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
                    updateIdleState()
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
        updateIdleState()
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
        val sharedPreferences = getSharedPreferences("MusicDroidPrefs", Context.MODE_PRIVATE)
        clientId = sharedPreferences.getString("SpotifyClientId", null)

        if (clientId.isNullOrBlank()) {
            statusTextView.text = "Status: Spotify Client ID not set"
            return
        }

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

        val connectionParams = ConnectionParams.Builder(clientId!!)
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

                playerStateReceived = false
                playerContextReceived = false
                subscribeToPlayerState()
                subscribeToPlayerContext()
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
        playerStateSubscription?.cancel()
        playerStateSubscription = spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
            playerStateReceived = true
            updateSpotifyUI(playerState)
            checkPendingUri()
        }
    }

    private fun subscribeToPlayerContext() {
        playerContextSubscription?.cancel()
        playerContextSubscription = spotifyAppRemote?.playerApi?.subscribeToPlayerContext()?.setEventCallback { playerContext ->
            Log.d("MainActivity", "PlayerContext updated: ${playerContext.uri}")
            currentSpotifyContextUri = playerContext.uri
            playerContextReceived = true
            checkPendingUri()
        }
    }

    private fun checkPendingUri() {
        if (playerStateReceived && playerContextReceived) {
            pendingUri?.let {
                Log.d("MainActivity", "Processing pending URI after receiving player state and context: $it")
                launchSpotify(it)
                pendingUri = null
            }
        }
    }

    private fun updateSpotifyUI(playerState: PlayerState) {
        val track = playerState.track
        if (track != null) {
            currentSpotifyTrackUri = track.uri
            trackTitleTextView.text = track.name
            artistTextView.text = track.artist.name
            albumTextView.text = track.album.name

            spotifyAppRemote?.imagesApi?.getImage(track.imageUri)?.setResultCallback { bitmap ->
                artworkImageView.setImageBitmap(bitmap)
            }

            updatePlayPauseButton(!playerState.isPaused)
        } else {
            currentSpotifyTrackUri = null
        }
        updateIdleState()
    }

    private fun triggerAuthFlow() {
        val sharedPreferences = getSharedPreferences("MusicDroidPrefs", Context.MODE_PRIVATE)
        clientId = sharedPreferences.getString("SpotifyClientId", null)

        if (clientId.isNullOrBlank()) {
            statusTextView.text = "Status: Spotify Client ID not set"
            Toast.makeText(this, "Please set Spotify Client ID in Settings", Toast.LENGTH_LONG).show()
            return
        }

        val request = AuthorizationRequest.Builder(clientId!!, AuthorizationResponse.Type.TOKEN, REDIRECT_URI)
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

        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {
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

        val remote = spotifyAppRemote
        if (remote != null && remote.isConnected) {
            // Check if this is already playing (either context or track)
            if (spotifyUri == lastRequestedSpotifyUri ||
                spotifyUri == currentSpotifyContextUri ||
                spotifyUri == currentSpotifyTrackUri) {
                Log.d("MainActivity", "Ignore duplicate Spotify URI: $spotifyUri (Context: $currentSpotifyContextUri, Track: $currentSpotifyTrackUri)")
                return
            }

            lastRequestedSpotifyUri = spotifyUri
            lastRequestedExoPlayerUri = null
            exoPlayer?.stop()
            remote.playerApi.play(spotifyUri)
        } else {
            // If we are already connecting for this URI, ignore
            if (spotifyUri == pendingUri) {
                Log.d("MainActivity", "Already connecting for this URI: $spotifyUri")
                return
            }
            pendingUri = spotifyUri
            lastRequestedExoPlayerUri = null
            exoPlayer?.stop()
            connectToSpotify()
        }
    }

    private fun processMusicDroidUri(uriString: String) {
        Log.d("MainActivity", "Processing musicdroid URI: $uriString")
        val uri = Uri.parse(uriString)
        val mp3Url = uri.getQueryParameter("url") ?: uri.path?.substringAfter("/") ?: uri.host

        Log.d("MainActivity", "Extracted URL: $mp3Url")

        if (mp3Url != null && (mp3Url.startsWith("http://") || mp3Url.startsWith("https://"))) {
            if (mp3Url == lastRequestedExoPlayerUri) {
                Log.d("MainActivity", "Ignore duplicate musicdroid URI: $mp3Url")
                return
            }

            lastRequestedExoPlayerUri = mp3Url
            lastRequestedSpotifyUri = null

            if (mp3Url.lowercase().contains(".pls") || mp3Url.lowercase().contains(".plu")) {
                playPls(mp3Url)
            } else {
                playMp3(mp3Url)
            }
        } else {
            Log.e("MainActivity", "Invalid or missing URL in musicdroid URI")
            statusTextView.text = "Status: Invalid URI"
        }
    }

    private fun playPls(url: String) {
        statusTextView.text = "Status: Parsing PLS..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                fetchAndParsePls(url)
            }
            when {
                result.isSuccess -> {
                    result.getOrNull()?.let { playMp3(it) }
                }
                else -> {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    statusTextView.text = "Status: $error"
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun fetchAndParsePls(url: String): Result<String> {
        return try {
            val content = URL(url).readText()
            Log.d("MainActivity", "PLS Content: $content")
            val streamUrl = PlsParser.parse(content)
            if (streamUrl != null) {
                Result.success(streamUrl)
            } else {
                Result.failure(Exception("Failed to parse PLS content"))
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching PLS", e)
            Result.failure(Exception("Failed to fetch PLS: ${e.message}"))
        }
    }

    private fun playMp3(url: String) {
        Log.d("MainActivity", "Playing MP3/Stream: $url")
        statusTextView.text = "Status: Playing Stream..."
        spotifyAppRemote?.playerApi?.pause()

        initializeExoPlayer()
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
        }
    }

    private fun updateIdleState() {
        val exoIdle = exoPlayer?.let {
            it.playbackState == Player.STATE_IDLE || it.playbackState == Player.STATE_ENDED || it.mediaItemCount == 0
        } ?: true

        val spotifyIdle = currentSpotifyTrackUri == null

        if (exoIdle && spotifyIdle) {
            idleTextView.visibility = View.VISIBLE
            artworkImageView.visibility = View.GONE
            metadataContainer.visibility = View.GONE
            controlsContainer.visibility = View.GONE
        } else {
            idleTextView.visibility = View.GONE
            artworkImageView.visibility = View.VISIBLE
            metadataContainer.visibility = View.VISIBLE
            controlsContainer.visibility = View.VISIBLE
        }
    }

    private fun convertToSpotifyUri(uriString: String): String {
        return UriUtils.convertToSpotifyUri(uriString)
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
