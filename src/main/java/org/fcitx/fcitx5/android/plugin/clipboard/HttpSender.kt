package org.fcitx.fcitx5.android.plugin.clipboard

import android.util.Base64
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private const val TAG = "ClipboardHttpSender"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000

/**
 * Sends clipboard data as HTTP POST with a coalescing queue:
 * only one in-flight request at a time; if a newer payload arrives while
 * one is in-flight, the newest value replaces any previously queued item.
 */
object HttpSender {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "clipboard-http-sender").also { it.isDaemon = true }
    }

    /** Holds the next payload to send (null = nothing queued). */
    private val pending = AtomicReference<String?>(null)

    /** True while the executor is actively running a send loop. */
    private val sending = AtomicBoolean(false)

    /** Enqueue a payload. If a send loop is not running, start one. */
    fun enqueue(payload: String) {
        pending.set(payload)
        if (sending.compareAndSet(false, true)) {
            executor.submit { sendLoop() }
        }
    }

    private fun sendLoop() {
        try {
            while (true) {
                val msg = pending.getAndSet(null) ?: break
                doSend(msg)
            }
        } finally {
            sending.set(false)
            // Guard against a race: payload may have arrived between the while-check and set(false).
            if (pending.get() != null && sending.compareAndSet(false, true)) {
                executor.submit { sendLoop() }
            }
        }
    }

    private fun doSend(payload: String) {
        val appContext = AppContextHolder.get() ?: run {
            Log.e(TAG, "No application context available, skipping send")
            return
        }
        val url = PreferenceStore.getUrl(appContext)
        if (url.isBlank()) {
            Log.w(TAG, "POST URL not configured, skipping send")
            return
        }
        val ignoreCert = PreferenceStore.getIgnoreCert(appContext)

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true

            if (ignoreCert && connection is HttpsURLConnection) {
                connection.sslSocketFactory = trustAllSslContext().socketFactory
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }

            val code = connection.responseCode
            connection.disconnect()

            if (code in 200..299) {
                Log.i(TAG, "POST success, HTTP $code")
            } else {
                Log.e(TAG, "POST failed: HTTP $code, url=$url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST failed: ${e.javaClass.simpleName}: ${e.message}, url=$url")
        }
    }

    fun buildPayload(data: String): String {
        val encoded = Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return """{"base64":"$encoded"}"""
    }

    private fun trustAllSslContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").also { ctx ->
            ctx.init(null, arrayOf(trustAll), null)
        }
    }
}
