package com.akdotcom.musicdroid

import android.util.Log
import com.google.gson.Gson
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val is_active: Boolean
)

data class SpotifyDevicesResponse(
    val devices: List<SpotifyDevice>
)

object SpotifyWebApiHelper {
    private const val TAG = "SpotifyWebApiHelper"
    private val gson = Gson()

    fun getAvailableDevices(accessToken: String): List<SpotifyDevice>? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.spotify.com/v1/me/player/devices")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val response = gson.fromJson(responseText, SpotifyDevicesResponse::class.java)
                response.devices
            } else {
                Log.e(TAG, "getAvailableDevices failed with response code: $responseCode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting available devices", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun transferPlayback(accessToken: String, deviceId: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.spotify.com/v1/me/player")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val body = gson.toJson(mapOf("device_ids" to listOf(deviceId)))
            OutputStreamWriter(connection.outputStream).use { it.write(body) }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_NO_CONTENT || responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_ACCEPTED) {
                true
            } else {
                Log.e(TAG, "transferPlayback failed with response code: $responseCode")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error transferring playback", e)
            false
        } finally {
            connection?.disconnect()
        }
    }
}
