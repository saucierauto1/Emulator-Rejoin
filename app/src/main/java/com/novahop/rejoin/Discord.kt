package com.novahop.rejoin

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Discord webhook: one live status message (posted again and the old one deleted each update)
// plus short event messages.
object Discord {
    private val HOOK = Regex("^https://(ptb\\.|canary\\.)?discord(app)?\\.com/api/webhooks/\\d+/[\\w-]+$")

    fun isWebhook(url: String) = HOOK.matches(url.trim())

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "NovaHopRejoin/1.0")
        return c
    }

    private fun check(c: HttpURLConnection): String {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        if (code !in 200..299) throw RuntimeException("Discord said $code ${body.take(120)}")
        return body
    }

    // a plain text message
    fun send(url: String, text: String) {
        val c = open(url.trim(), "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val json = JSONObject().put("content", text.take(1900)).put("username", "NovaHop Rejoin")
        c.outputStream.use { it.write(json.toString().toByteArray()) }
        check(c)
    }

    // post the status message (with an optional screenshot), then delete the previous one
    fun status(url: String, embed: JSONObject, jpg: ByteArray?) {
        val base = url.trim()
        val payload = JSONObject().put("username", "NovaHop Rejoin").put("embeds", JSONArray().put(embed))
        val boundary = "----nhr" + System.currentTimeMillis()
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray())
        w("--$boundary\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n")
        w(payload.toString())
        w("\r\n")
        if (jpg != null) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"screen.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
            out.write(jpg)
            w("\r\n")
        }
        w("--$boundary--\r\n")
        val c = open("$base?wait=true", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { it.write(out.toByteArray()) }
        val id = JSONObject(check(c)).optString("id")
        val old = Settings.statusMsg
        if (old.isNotEmpty() && old != id) {
            try { check(open("$base/messages/$old", "DELETE")) } catch (e: Exception) { }
        }
        Settings.statusMsg = id
    }

    fun nowIso(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }
}
