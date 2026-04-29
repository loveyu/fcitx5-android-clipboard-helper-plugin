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
private const val STATUS_COLOR_NEUTRAL = "#757575"
private const val STATUS_COLOR_INFO = "#1565C0"
private const val STATUS_COLOR_SUCCESS = "#2E7D32"
private const val STATUS_COLOR_WARNING = "#E65100"
private const val STATUS_COLOR_ERROR = "#B71C1C"

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

    enum class Status {
        /** Plugin service has not yet been bound by fcitx5-android. */
        IDLE,
        /** fcitx5-android bound our service; waiting for the IPC back-connection. */
        CONNECTING,
        /** IPC connected and transformer successfully registered. */
        CONNECTED,
        /** IPC binding failed (permission denied or service not found). */
        IPC_FAILED,
        /** Was connected, but the IPC service dropped the connection. */
        DISCONNECTED,
    }

    companion object {
        @Volatile var status: Status = Status.IDLE
            private set
        @Volatile var failReason: String? = null
            private set

        internal fun setStatus(s: Status, reason: String? = null) {
            status = s
            failReason = reason
        }
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
            setStatus(Status.CONNECTED)
            Log.d(TAG, "Connected to fcitx5-android, transformer registered")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remoteService = null
            setStatus(Status.DISCONNECTED)
            Log.d(TAG, "Disconnected from fcitx5-android")
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "Bound by fcitx5-android")
        try {
            val bound = bindService(
                Intent("$FCITX_APP_ID.IPC").setPackage(FCITX_APP_ID),
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (bound) {
                setStatus(Status.CONNECTING)
            } else {
                setStatus(Status.IPC_FAILED, "bindService 返回 false，请确认 fcitx5-android 已安装并运行")
                Log.w(TAG, "bindService returned false — IPC service unavailable or permission denied")
            }
        } catch (e: SecurityException) {
            setStatus(Status.IPC_FAILED, "IPC 权限被拒绝，请确认插件与 fcitx5-android 使用相同签名证书")
            Log.e(TAG, "IPC bind denied — signing certificate mismatch? ${e.message}")
        }
        return Messenger(Handler(Looper.getMainLooper())).binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { remoteService?.unregisterClipboardEntryTransformer(transformer) }
        runCatching { unbindService(connection) }
        remoteService = null
        setStatus(Status.IDLE)
        Log.d(TAG, "Unbound, transformer unregistered")
        return false
    }
}

class PluginActivity : Activity() {

    private lateinit var binding: ActivityPluginBinding
    private var statusProbeGeneration = 0
    private var statusProbeConnection: ServiceConnection? = null

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
        cancelStatusProbe()
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
        when (MainService.status) {
            MainService.Status.IDLE -> probeIdleConnectionStatus()
            MainService.Status.CONNECTING -> {
                cancelStatusProbe()
                renderConnectionStatus(
                    getString(R.string.connection_status_connecting),
                    STATUS_COLOR_INFO
                )
            }
            MainService.Status.CONNECTED -> {
                cancelStatusProbe()
                renderConnectionStatus(
                    getString(R.string.connection_status_connected),
                    STATUS_COLOR_SUCCESS
                )
            }
            MainService.Status.IPC_FAILED -> {
                cancelStatusProbe()
                renderConnectionStatus(
                    getString(
                        R.string.connection_status_failed,
                        MainService.failReason ?: getString(R.string.connection_status_unknown_reason)
                    ),
                    STATUS_COLOR_ERROR
                )
            }
            MainService.Status.DISCONNECTED -> {
                cancelStatusProbe()
                renderConnectionStatus(
                    getString(R.string.connection_status_disconnected),
                    STATUS_COLOR_WARNING
                )
            }
        }
    }

    private fun probeIdleConnectionStatus() {
        cancelStatusProbe()
        renderConnectionStatus(getString(R.string.connection_status_checking), STATUS_COLOR_NEUTRAL)

        val generation = ++statusProbeGeneration
        val connection = object : ServiceConnection {
            var bound = false

            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val state = try {
                    val loadedPlugins = IFcitxRemoteService.Stub.asInterface(service).getLoadedPlugins()
                    if (loadedPlugins.containsKey(packageName)) {
                        getString(R.string.connection_status_loaded_waiting) to STATUS_COLOR_NEUTRAL
                    } else {
                        getString(R.string.connection_status_not_loaded) to STATUS_COLOR_WARNING
                    }
                } catch (e: SecurityException) {
                    getString(R.string.connection_status_permission_denied) to STATUS_COLOR_ERROR
                } catch (e: Exception) {
                    getString(
                        R.string.connection_status_failed,
                        e.message ?: e.javaClass.simpleName
                    ) to STATUS_COLOR_ERROR
                }
                finish(state)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                finish(
                    getString(R.string.connection_status_remote_unavailable) to STATUS_COLOR_WARNING
                )
            }

            private fun finish(state: Pair<String, String>) {
                if (statusProbeConnection === this) {
                    statusProbeConnection = null
                }
                if (bound) {
                    runCatching { unbindService(this) }
                    bound = false
                }
                if (isActive && statusProbeGeneration == generation && MainService.status == MainService.Status.IDLE) {
                    renderConnectionStatus(state.first, state.second)
                }
            }
        }

        statusProbeConnection = connection
        try {
            connection.bound = bindService(
                Intent("$FCITX_APP_ID.IPC").setPackage(FCITX_APP_ID),
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!connection.bound) {
                statusProbeConnection = null
                renderConnectionStatus(
                    getString(R.string.connection_status_remote_unavailable),
                    STATUS_COLOR_WARNING
                )
            }
        } catch (e: SecurityException) {
            statusProbeConnection = null
            renderConnectionStatus(
                getString(R.string.connection_status_permission_denied),
                STATUS_COLOR_ERROR
            )
        }
    }

    private fun cancelStatusProbe() {
        statusProbeGeneration++
        statusProbeConnection?.also { runCatching { unbindService(it) } }
        statusProbeConnection = null
    }

    private fun renderConnectionStatus(text: String, color: String) {
        binding.connectionStatusText.text = text
        binding.connectionStatusText.setTextColor(Color.parseColor(color))
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
