package org.fcitx.fcitx5.android.plugin.clipboard

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.util.Log
import android.widget.Toast
import org.fcitx.fcitx5.android.plugin.clipboard.databinding.ActivityPluginBinding

private const val TAG = "ClipboardPlugin"

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
    }
}

/**
 * Bindable service required by the fcitx5-android plugin protocol.
 * It also registers a ClipboardManager listener while the service is alive.
 */
class MainService : Service() {

    private lateinit var clipboardManager: ClipboardManager
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        Log.d(TAG, "MainService created, clipboard listener registered")
    }

    override fun onBind(intent: Intent): IBinder =
        Messenger(Handler(Looper.getMainLooper())).binder

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        Log.d(TAG, "MainService destroyed, clipboard listener removed")
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
            // Silently ignore failures (e.g. clipboard access denied in background)
            Log.d(TAG, "Clipboard read skipped: ${e.javaClass.simpleName}")
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
}
