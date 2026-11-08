package com.telemedia.phone

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to node-bootstrap/main.js's control server on 127.0.0.1:7001. Blocking: call off the main thread. */
object ControlClient {
    private val base = "http://127.0.0.1:${NodeService.CONTROL_PORT}"

    /** An action's answer: ok, or the error message to show. */
    class Reply(val ok: Boolean, val body: JSONObject, val error: String)

    /** Returns null when Node isn't up yet. */
    fun status(): JSONObject? = try {
        val conn = URL("$base/status").openConnection() as HttpURLConnection
        conn.connectTimeout = 1000
        conn.readTimeout = 1500
        conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    } catch (e: Exception) {
        null
    }

    /** POST a JSON body. Some actions talk to Telegram or Stremio, so allow up to a minute. */
    fun post(path: String, body: String): Reply = try {
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 2000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val ok = conn.responseCode in 200..299
        val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        Reply(ok, json, json.optString("error").ifBlank { "Something went wrong (${conn.responseCode})" })
    } catch (e: Exception) {
        Reply(false, JSONObject(), "Telemedia isn't responding. Tap Restart.")
    }
}
