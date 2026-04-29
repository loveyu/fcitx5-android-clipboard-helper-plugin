package org.fcitx.fcitx5.android.plugin.clipboard

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
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
import org.fcitx.fcitx5.android.common.ipc.IFcitxRemoteService
import org.fcitx.fcitx5.android.plugin.clipboard.databinding.ActivityPluginBinding

private const val TAG = "ClipboardPlugin"

/** Main application ID of fcitx5-android — injected per build type via BuildConfig. */
private val FCITX_APP_ID get() = BuildConfig.FCITX_APP_ID

object AppContextHolder {
    private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }
    fun get(): Context? = appContext
}

class PluginApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
    }
}

/**
 * Plugin service required by the fcitx5-android plugin protocol.
 *
 * When fcitx5-android binds this service, we in turn bind back to fcitx5-android's
 * [IFcitxRemoteService] and register an [IClipboardEntryTransformer].  Every time
 * fcitx5-android processes a clipboard entry it calls [IClipboardEntryTransformer.transform];
 * we intercept the text, forward it via [HttpSender], and return it unchanged.
 *
 * Note: this requires both apps to share the same signing certificate
 * (both debug builds use the standard Android debug keystore, which satisfies
 * the `protectionLevel="signature"` on the IPC permission).
 */
class MainService : android.app.Service() {

    companion object {
        /** True while the IFcitxRemoteService connection is active. */
        @Volatile
        var isConnectedToFcitx: Boolean = false
            private set

        internal fun setConnected(v: Boolean) { isConnectedToFcitx = v }
    }

    private var remoteService: IFcitxRemoteService? = null

    private val transformer = object : IClipboardEntryTransformer.Stub() {
        override fun getPriority(): Int = 0
        override fun getDescription(): String = "ClipboardHelperPlugin"
        override fun transform(clipboardText: String): String {
            if (!PreferenceStore.getEnabled(this@MainService)) return clipboardText
            Log.d(TAG, "Received clipboard text (${clipboardText.length} chars)")
            PreferenceStore.saveLastClipboard(this@MainService, clipboardText)
            HttpSender.enqueue(HttpSender.buildPayload(clipboardText))
            return clipboardText
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remoteService = IFcitxRemoteService.Stub.asInterface(service)
            remoteService?.registerClipboardEntryTransformer(transformer)
            setConnected(true)
            Log.d(TAG, "Connected to fcitx5-android, transformer registered")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remoteService = null
            setConnected(false)
            Log.d(TAG, "Disconnected from fcitx5-android")
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "Bound by fcitx5-android")
        val bound = try {
            bindService(
                Intent("$FCITX_APP_ID.IPC").setPackage(FCITX_APP_ID),
                connection,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "IPC bind denied — signing certificate mismatch? ${e.message}")
            false
        }
        if (!bound) Log.w(TAG, "bindService returned false — IPC service unavailable or permission denied")
        return Messenger(Handler(Looper.getMainLooper())).binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { remoteService?.unregisterClipboardEntryTransformer(transformer) }
        runCatching { unbindService(connection) }
        remoteService = null
        setConnected(false)
        Log.d(TAG, "Unbound, transformer unregistered")
        return false
    }
}

class PluginActivity : Activity() {

    private lateinit var binding: ActivityPluginBinding

    /** Guards background logcat threads from updating a paused/destroyed activity. */
    @Volatile
    private var isActive = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshClipboardPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPluginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val savedUrl = PreferenceStore.getUrl(this)
        binding.currentUrlText.text = savedUrl.ifEmpty { getString(R.string.url_not_set) }
        binding.urlInput.setText(savedUrl)
        binding.ignoreCertCheckbox.isChecked = PreferenceStore.getIgnoreCert(this)
        updateSslLayoutVisibility(binding.urlInput.text?.toString() ?: "")

        binding.enableSwitch.isChecked = PreferenceStore.getEnabled(this)
        binding.enableSwitch.setOnCheckedChangeListener { _, checked ->
            PreferenceStore.setEnabled(this, checked)
        }

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

        binding.refreshLogButton.setOnClickListener { refreshDynamicState() }
    }

    override fun onResume() {
        super.onResume()
        isActive = true
        PreferenceStore.addOnChangeListener(this, prefsListener)
        refreshDynamicState()
    }

    override fun onPause() {
        super.onPause()
        isActive = false
        PreferenceStore.removeOnChangeListener(this, prefsListener)
    }

    private fun refreshDynamicState() {
        refreshClipboardPreview()
        refreshConnectionStatus()
        refreshLogcat()
    }

    private fun refreshClipboardPreview() {
        val lastClip = PreferenceStore.getLastClipboard(this)
        binding.clipboardPreview.text = lastClip.ifEmpty { getString(R.string.clipboard_empty) }
    }

    private fun refreshConnectionStatus() {
        val connected = MainService.isConnectedToFcitx
        binding.connectionStatusText.text = if (connected)
            getString(R.string.connection_status_connected)
        else
            getString(R.string.connection_status_disconnected)
        binding.connectionStatusText.setTextColor(
            if (connected) Color.parseColor("#2E7D32") else Color.parseColor("#B71C1C")
        )
    }

    private fun refreshLogcat() {
        Thread {
            val output = readLogcat()
            if (isActive) {
                runOnUiThread {
                    binding.logOutput.text = output.ifEmpty { getString(R.string.log_empty) }
                }
            }
        }.start()
    }

    private fun readLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "3", "ClipboardPlugin:V", "ClipboardHttpSender:V", "*:S")
            )
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.destroy()
            output
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
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

