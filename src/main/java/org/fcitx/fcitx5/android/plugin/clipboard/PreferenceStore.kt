package org.fcitx.fcitx5.android.plugin.clipboard

import android.content.Context
import android.content.SharedPreferences

object PreferenceStore {

    private const val PREFS_NAME = "clipboard_plugin_prefs"
    private const val KEY_URL = "post_url"
    private const val KEY_IGNORE_CERT = "ignore_cert"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getUrl(context: Context): String =
        prefs(context).getString(KEY_URL, "") ?: ""

    fun getIgnoreCert(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IGNORE_CERT, false)

    fun save(context: Context, url: String, ignoreCert: Boolean) {
        prefs(context).edit()
            .putString(KEY_URL, url.trim())
            .putBoolean(KEY_IGNORE_CERT, ignoreCert)
            .apply()
    }
}
