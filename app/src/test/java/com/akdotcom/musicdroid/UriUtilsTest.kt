package com.akdotcom.musicdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class UriUtilsTest {

    @Test
    fun testConvertToSpotifyUri() {
        val webUrl = "https://open.spotify.com/album/1HiG0ukRmFPN13EVcf98Jx?si=grxC3IdySoKvAQofqIZLuw"
        val expected = "spotify:album:1HiG0ukRmFPN13EVcf98Jx"
        assertEquals(expected, UriUtils.convertToSpotifyUri(webUrl))
    }

    @Test
    fun testConvertToSpotifyUriWithTrack() {
        val webUrl = "https://open.spotify.com/track/4uLU611mZhU6p9ORelNYmZ"
        val expected = "spotify:track:4uLU611mZhU6p9ORelNYmZ"
        assertEquals(expected, UriUtils.convertToSpotifyUri(webUrl))
    }

    @Test
    fun testSmartConvertSpotify() {
        val webUrl = "https://open.spotify.com/album/1HiG0ukRmFPN13EVcf98Jx"
        val expected = "spotify:album:1HiG0ukRmFPN13EVcf98Jx"
        assertEquals(expected, UriUtils.smartConvert(webUrl))
    }

    @Test
    fun testSmartConvertMp3() {
        val mp3Url = "https://example.com/song.mp3"
        val expected = "musicdroid://play?url=https%3A%2F%2Fexample.com%2Fsong.mp3"
        assertEquals(expected, UriUtils.smartConvert(mp3Url))
    }

    @Test
    fun testSmartConvertAlreadyUri() {
        val spotifyUri = "spotify:album:123"
        assertEquals(spotifyUri, UriUtils.smartConvert(spotifyUri))

        val musicDroidUri = "musicdroid://play?url=abc"
        assertEquals(musicDroidUri, UriUtils.smartConvert(musicDroidUri))
    }
}
