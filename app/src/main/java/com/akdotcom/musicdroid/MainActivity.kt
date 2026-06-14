package com.akdotcom.musicdroid

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.webkit.WebViewAssetLoader
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.exoplayer.ExoPlayer
import java.net.URL

class MainActivity : AppCompatActivity() {

    // Local page (served over a secure https origin) that hosts the Spotify
    // iFrame API. A secure context is required for the embed's DRM playback.
    private val PLAYER_URL = "https://appassets.androidplatform.net/assets/player.html"
    private val SPOTIFY_LOGIN_URL = "https://accounts.spotify.com/login"

    private lateinit var webView: WebView
    private var webViewReady = false
    private var isLoginMode = false
    private var pendingSpotifyUri: String? = null
    private var lastRequestedSpotifyUri: String? = null

    private var exoPlayer: ExoPlayer? = null
    private var lastRequestedExoPlayerUri: String? = null

    private enum class Mode { IDLE, SPOTIFY, STREAM }
    private var mode = Mode.IDLE

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

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

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
        webView = findViewById(R.id.webView)

        settingsButton = findViewById(R.id.settingsButton)
        prevButton = findViewById(R.id.prevButton)
        playPauseButton = findViewById(R.id.playPauseButton)
        nextButton = findViewById(R.id.nextButton)

        setupWebView()
        initializeExoPlayer()
        updateMode(Mode.IDLE)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // The custom transport controls drive the local ExoPlayer (stream mode).
        // In Spotify mode the embed shows its own controls.
        prevButton.setOnClickListener {
            exoPlayer?.takeIf { it.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS) }?.seekToPrevious()
        }
        playPauseButton.setOnClickListener {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else if (player.playbackState != Player.STATE_IDLE) {
                    player.play()
                }
            }
        }
        nextButton.setOnClickListener {
            exoPlayer?.takeIf { it.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) }?.seekToNext()
        }

        // Pressing back while logged-in-via-web returns to the player page.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLoginMode) {
                    showPlayerPage()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val nfcIntent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        pendingIntent = PendingIntent.getActivity(this, 0, nfcIntent, flags)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Serve player.html from a secure virtual https origin so the Spotify
        // embed (which uses EME/DRM) runs in a secure context.
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false // allow programmatic autoplay
            // The default WebView UA carries a "wv" token that some Spotify
            // surfaces reject; presenting as plain mobile Chrome avoids that.
            userAgentString = userAgentString.replace("; wv", "")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // The embed/login pages are third-party relative to our app origin.
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                if (url == PLAYER_URL) {
                    webViewReady = true
                    pendingSpotifyUri?.let {
                        pendingSpotifyUri = null
                        playInWebView(it)
                    }
                }
            }
        }

        webView.addJavascriptInterface(MusicDroidBridge(), "MusicDroidBridge")
        webView.loadUrl(PLAYER_URL)
    }

    /** Receives JSON events posted from player.html. */
    private inner class MusicDroidBridge {
        @JavascriptInterface
        fun onEvent(json: String) {
            Log.d("MainActivity", "WebView event: $json")
            runOnUiThread {
                if (mode == Mode.SPOTIFY) statusTextView.text = "Status: Spotify"
            }
        }
    }

    override fun onStart() {
        super.onStart()
        initializeExoPlayer()
        webView.onResume()
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let { nfc -> pendingIntent?.let { nfc.enableForegroundDispatch(this, it, null, null) } }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        // Pause web playback and tear down the local player when backgrounded.
        webView.onPause()
        webView.evaluateJavascript("window.MusicDroid && window.MusicDroid.pause();", null)
        releaseExoPlayer()
        lastRequestedSpotifyUri = null
        lastRequestedExoPlayerUri = null
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // region ExoPlayer (musicdroid:// HTTP/PLS streams)

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
                        Player.STATE_ENDED, Player.STATE_IDLE -> lastRequestedExoPlayerUri = null
                    }
                    if (mode == Mode.STREAM) refreshStreamIdleState()
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
        trackTitleTextView.text = metadata.title ?: metadata.displayTitle ?: "Unknown Track"
        artistTextView.text = metadata.artist ?: metadata.albumArtist ?: "Unknown Artist"
        albumTextView.text = metadata.albumTitle ?: ""

        when {
            metadata.artworkUri != null -> artworkImageView.load(metadata.artworkUri)
            metadata.artworkData != null -> artworkImageView.load(metadata.artworkData)
            else -> artworkImageView.setImageResource(android.R.drawable.ic_menu_report_image)
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        playPauseButton.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun refreshStreamIdleState() {
        val idle = exoPlayer?.let {
            it.playbackState == Player.STATE_IDLE || it.playbackState == Player.STATE_ENDED || it.mediaItemCount == 0
        } ?: true
        if (idle) updateMode(Mode.IDLE)
    }

    // endregion

    // region UI mode

    private fun updateMode(newMode: Mode) {
        mode = newMode
        val spotify = newMode == Mode.SPOTIFY
        val stream = newMode == Mode.STREAM

        webView.visibility = if (spotify || isLoginMode) View.VISIBLE else View.GONE
        idleTextView.visibility = if (newMode == Mode.IDLE && !isLoginMode) View.VISIBLE else View.GONE
        artworkImageView.visibility = if (stream) View.VISIBLE else View.GONE
        metadataContainer.visibility = if (stream) View.VISIBLE else View.GONE
        controlsContainer.visibility = if (stream) View.VISIBLE else View.GONE
    }

    // endregion

    // region Intent handling

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.getStringExtra("action")) {
            "login" -> { loginToSpotify(); return }
            "logout" -> { logoutOfSpotify(); return }
        }

        val uri = intent.data
        if (uri != null && uri.scheme == "musicdroid") {
            processMusicDroidUri(uri.toString())
            return
        }

        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action
        ) {
            val rawMessages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            }
            if (rawMessages != null) {
                processNdefMessages(Array(rawMessages.size) { i -> rawMessages[i] as NdefMessage })
            }
        } else if (uri != null) {
            // Manual trigger from SettingsActivity.
            dispatchUri(uri.toString())
        }
    }

    private fun processNdefMessages(messages: Array<NdefMessage>) {
        for (message in messages) {
            for (record in message.records) {
                val payload = record.toUri()
                if (payload != null) {
                    dispatchUri(payload.toString())
                    return
                }
            }
        }
    }

    /** Routes a tag/deep-link URI to either the Spotify embed or the stream player. */
    private fun dispatchUri(uriString: String) {
        if (uriString.startsWith("musicdroid:")) {
            processMusicDroidUri(uriString)
            return
        }
        val spotifyUri = UriUtils.convertToSpotifyUri(uriString)
        loadSpotifyUri(spotifyUri)
    }

    private fun loadSpotifyUri(spotifyUri: String) {
        if (spotifyUri == lastRequestedSpotifyUri && mode == Mode.SPOTIFY) {
            Log.d("MainActivity", "Ignore duplicate Spotify URI: $spotifyUri")
            return
        }
        lastRequestedSpotifyUri = spotifyUri
        lastRequestedExoPlayerUri = null
        exoPlayer?.stop()
        statusTextView.text = "Status: Loading Spotify..."
        updateMode(Mode.SPOTIFY)
        playInWebView(spotifyUri)
    }

    private fun playInWebView(spotifyUri: String) {
        if (!webViewReady || isLoginMode) {
            pendingSpotifyUri = spotifyUri
            if (isLoginMode) showPlayerPage()
            return
        }
        val escaped = spotifyUri.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("window.MusicDroid.load('$escaped');", null)
    }

    private fun processMusicDroidUri(uriString: String) {
        Log.d("MainActivity", "Processing musicdroid URI: $uriString")
        val uri = Uri.parse(uriString)
        val mp3Url = uri.getQueryParameter("url") ?: uri.path?.substringAfter("/") ?: uri.host

        if (mp3Url != null && (mp3Url.startsWith("http://") || mp3Url.startsWith("https://"))) {
            if (mp3Url == lastRequestedExoPlayerUri && mode == Mode.STREAM) {
                Log.d("MainActivity", "Ignore duplicate musicdroid URI: $mp3Url")
                return
            }
            lastRequestedExoPlayerUri = mp3Url
            lastRequestedSpotifyUri = null
            // Stop any Spotify playback in the embed before switching to a stream.
            webView.evaluateJavascript("window.MusicDroid && window.MusicDroid.pause();", null)
            updateMode(Mode.STREAM)
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
            val result = withContext(Dispatchers.IO) { fetchAndParsePls(url) }
            if (result.isSuccess) {
                result.getOrNull()?.let { playMp3(it) }
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                statusTextView.text = "Status: $error"
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun fetchAndParsePls(url: String): Result<String> {
        return try {
            val content = URL(url).readText()
            val streamUrl = PlsParser.parse(content)
            if (streamUrl != null) Result.success(streamUrl)
            else Result.failure(Exception("Failed to parse PLS content"))
        } catch (e: Exception) {
            Log.e("MainActivity", "Error fetching PLS", e)
            Result.failure(Exception("Failed to fetch PLS: ${e.message}"))
        }
    }

    private fun playMp3(url: String) {
        Log.d("MainActivity", "Playing MP3/Stream: $url")
        statusTextView.text = "Status: Playing Stream..."
        initializeExoPlayer()
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.play()
        }
    }

    // endregion

    // region Spotify web login

    private fun loginToSpotify() {
        isLoginMode = true
        lastRequestedSpotifyUri = null
        updateMode(mode)
        statusTextView.text = "Status: Log in to Spotify, then press back"
        webView.loadUrl(SPOTIFY_LOGIN_URL)
    }

    private fun logoutOfSpotify() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            showPlayerPage()
            Toast.makeText(this, "Logged out of Spotify", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPlayerPage() {
        isLoginMode = false
        webViewReady = false
        webView.loadUrl(PLAYER_URL)
        updateMode(Mode.IDLE)
    }

    // endregion
}
