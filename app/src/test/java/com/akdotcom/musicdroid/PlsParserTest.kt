package com.akdotcom.musicdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class PlsParserTest {

    @Test
    fun testParsePlsContent() {
        val content = """
            [playlist]
            NumberOfEntries=1
            File1=http://kcrw.streamguys1.com/kcrw_192k_mp3_on_air_sm
            Title1=KCRW
            Length1=-1
            Version=2
        """.trimIndent()

        val streamUrl = PlsParser.parse(content)
        assertEquals("http://kcrw.streamguys1.com/kcrw_192k_mp3_on_air_sm", streamUrl)
    }

    @Test
    fun testParsePlsContentWithOtherFields() {
        val content = """
            [playlist]
            File1=https://example.com/stream.mp3
            Title1=Test Stream
            Length1=-1
            NumberOfEntries=1
            Version=2
        """.trimIndent()

        val streamUrl = PlsParser.parse(content)
        assertEquals("https://example.com/stream.mp3", streamUrl)
    }

    @Test
    fun testParsePlsContentInvalid() {
        val content = "not a pls file"
        val streamUrl = PlsParser.parse(content)
        assertEquals(null, streamUrl)
    }
}
