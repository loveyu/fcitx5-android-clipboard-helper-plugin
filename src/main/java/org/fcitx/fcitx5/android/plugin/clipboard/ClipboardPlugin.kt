package org.fcitx.fcitx5.android.plugin.clipboard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
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
import org.fcitx.fcitx5.android.common.ipc.IClipboardEntryTransformer
import org.fcitx.fcitx5.android.plugin.clipboard.databinding.ActivityPluginBinding

private const val TAG = "ClipboardPlugin"

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
 * Bound service required by the fcitx5-android plugin protocol.
 * When fcitx5-android binds this service, we connect back to its IFcitxRemoteService
 * and register an IClipboardEntryTransformer. fcitx5-android (as the IME) has full
 * clipboard access and will invoke transform() on every clipboard change.
 */
class MainService : android.app.Service() {

    private var fcitxConnection: FcitxRemoteConnection? = null

    private val clipboardTransformer = object : IClipboardEntryTransformer.Stub() {
        override fun getPriority(): Int = 0
        override fun getDescription(): String = "clipboard-helper"
        override fun transform(text: String): String {
            handleClipboardText(text)
            return text
        }
    }

    override fun onBind(intent: Intent): IBinder {
        AppContextHolder.init(this)
        fcitxConnection = bindFcitxRemoteService { remote ->
            remote.registerClipboardEntryTransformer(clipboardTransformer)
            Log.d(TAG, "Registered clipboard transformer with fcitx5-android")
        }
        return Messenger(Handler(Looper.getMainLooper())).binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching {
            fcitxConnection?.remoteService?.unregisterClipboardEntryTransformer(clipboardTransformer)
        }
        fcitxConnection?.let { unbindService(it) }
        fcitxConnection = null
        Log.d(TAG, "Unregistered clipboard transformer")
        return false
    }

    private fun handleClipboardText(text: String) {
        if (text.isBlank()) return
        val url = PreferenceStore.getUrl(this)
        if (url.isBlank()) {
            Log.d(TAG, "POST URL not configured, skipping")
            return
        }
        PreferenceStore.saveLastClipboard(this, text)
        HttpSender.enqueue(HttpSender.buildPayload(text))
        Log.d(TAG, "Clipboard text forwarded (${text.length} chars)")
    }
}

class PluginActivity : Activity() {

    private lateinit var binding: ActivityPluginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.init(this)
        binding = ActivityPluginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val savedUrl = PreferenceStore.getUrl(this)
        binding.currentUrlText.text = savedUrl.ifEmpty { getString(R.string.url_not_set) }
        binding.urlInput.setText(savedUrl)
        binding.ignoreCertCheckbox.isChecked = PreferenceStore.getIgnoreCert(this)
        updateSslLayoutVisibility(binding.urlInput.text?.toString() ?: "")

        val lastClip = PreferenceStore.getLastClipboard(this)
        binding.clipboardPreview.text = lastClip.ifEmpty { getString(R.string.clipboard_empty) }

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
            binding.currentUrlText.text = url
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

