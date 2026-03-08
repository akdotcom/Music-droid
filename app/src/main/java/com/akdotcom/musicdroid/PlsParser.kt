package com.akdotcom.musicdroid

import android.util.Log

object PlsParser {
    fun parse(content: String): String? {
        return try {
            val lines = content.lines()
            var streamUrl: String? = null
            for (line in lines) {
                val trimmedLine = line.trim()
                if (trimmedLine.startsWith("File1", ignoreCase = true)) {
                    val afterKey = trimmedLine.substring(5).trim()
                    if (afterKey.startsWith("=")) {
                        streamUrl = afterKey.substring(1).trim()
                        break
                    }
                }
            }
            streamUrl
        } catch (e: Exception) {
            Log.e("PlsParser", "Error parsing PLS content", e)
            null
        }
    }
}
