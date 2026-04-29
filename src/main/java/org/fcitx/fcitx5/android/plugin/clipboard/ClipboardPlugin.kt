package org.fcitx.fcitx5.android.plugin.clipboard

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import org.fcitx.fcitx5.android.plugin.clipboard.databinding.ActivityPluginBinding

private const val TAG = "ClipboardPlugin"
private const val NOTIFICATION_CHANNEL_ID = "clipboard_service_channel"
private const val FOREGROUND_NOTIFICATION_ID = 1

/** Holds the application context so HttpSender can access preferences without a Context param. */
object AppContextHolder {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context? = appContext
}

class PluginApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}

/**
 * Bindable service required by the fcitx5-android plugin protocol.
 * Runs as a foreground service so it can read clipboard on Android 10+
 * where background clipboard access is restricted.
 */
class MainService : Service() {

    private lateinit var clipboardManager: ClipboardManager
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
        startForegroundCompat()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        Log.d(TAG, "MainService created, clipboard listener registered")
    }

    override fun onBind(intent: Intent): IBinder =
        Messenger(Handler(Looper.getMainLooper())).binder

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        stopForeground(true)
        Log.d(TAG, "MainService destroyed, clipboard listener removed")
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val title = getString(R.string.service_notification_title)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setOngoing(true)
                .build()
        }
    }

    private fun onClipboardChanged() {
        try {
            val clip = clipboardManager.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (text.isBlank()) return

            val url = PreferenceStore.getUrl(this)
            if (url.isBlank()) return

            val payload = HttpSender.buildPayload(text)
            HttpSender.enqueue(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard read failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

class PluginActivity : Activity() {

    private lateinit var binding: ActivityPluginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.init(this)
        binding = ActivityPluginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.urlInput.setText(PreferenceStore.getUrl(this))
        binding.ignoreCertCheckbox.isChecked = PreferenceStore.getIgnoreCert(this)
        updateSslLayoutVisibility(binding.urlInput.text?.toString() ?: "")

        binding.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateSslLayoutVisibility(s?.toString() ?: "")
            }
        })

        binding.saveButton.setOnClickListener {
            val url = binding.urlInput.text?.toString().orEmpty().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, getString(R.string.url_empty_warning), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ignoreCert = binding.ignoreCertCheckbox.isChecked
            PreferenceStore.save(this, url, ignoreCert)
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSslLayoutVisibility(url: String) {
        val isHttps = url.trim().lowercase().startsWith("https://")
        binding.ignoreCertLayout.visibility = if (isHttps) View.VISIBLE else View.GONE
        if (!isHttps) {
            binding.ignoreCertCheckbox.isChecked = false
        }
    }

}
