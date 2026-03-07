package com.akdotcom.musicdroid

object UriUtils {
    /**
     * Converts a Spotify web URL to a native spotify: URI.
     * If the input is already a spotify: URI or not a Spotify URL, it returns it as is.
     */
    fun convertToSpotifyUri(uriString: String): String {
        if (uriString.startsWith("https://open.spotify.com/")) {
            val path = uriString.substringAfter("https://open.spotify.com/").substringBefore("?")
            val segments = path.split("/").filter { it.isNotEmpty() }
            if (segments.size >= 2) {
                val type = segments[0] // e.g., "track", "album", "playlist"
                val id = segments[1]
                return "spotify:$type:$id"
            }
        }
        return uriString
    }

    /**
     * Wraps a regular HTTP(S) URL into a musicdroid://play?url=... URI.
     */
    fun generateMusicDroidUri(url: String): String {
        // We use a simple replacement for common characters to avoid dependency on android.net.Uri in tests if possible,
        // but for the real app, we should use Uri.encode.
        // Let's stick to a version that might be testable or just accept we can't unit test it easily without Robolectric.
        // Actually, if I want it to be robust, I should use Uri.encode in the app.
        return "musicdroid://play?url=${simpleEncode(url)}"
    }

    private fun simpleEncode(url: String): String {
        // This is a very basic encoder, just for the sake of avoiding android.net.Uri in pure unit tests if needed.
        // However, it's better to use the real one in the app.
        // Let's use a workaround for tests.
        return try {
            java.net.URLEncoder.encode(url, "UTF-8")
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Smartly converts an input string (URL or URI) to the most appropriate native URI format.
     */
    fun smartConvert(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""

        // If it's already a native URI, return it
        if (trimmed.startsWith("spotify:") || trimmed.startsWith("musicdroid:")) {
            return trimmed
        }

        // Handle Spotify web URLs
        if (trimmed.startsWith("https://open.spotify.com/")) {
            return convertToSpotifyUri(trimmed)
        }

        // Handle other web URLs (MP3, PLS, PLU, etc.)
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return generateMusicDroidUri(trimmed)
        }

        return trimmed
    }
}
