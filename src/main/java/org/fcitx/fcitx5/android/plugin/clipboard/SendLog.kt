package org.fcitx.fcitx5.android.plugin.clipboard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Simple in-memory + persistent log for send results (no clipboard content stored). */
object SendLog {

    private const val PREFS_NAME = "clipboard_send_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 20
    private const val SEPARATOR = "|||"

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    data class Entry(val timestamp: Long, val success: Boolean, val detail: String)

    private val queue = ArrayDeque<Entry>(MAX_ENTRIES)

    fun record(context: Context, success: Boolean, detail: String) {
        val entry = Entry(System.currentTimeMillis(), success, detail)
        synchronized(queue) {
            if (queue.size >= MAX_ENTRIES) queue.pollFirst()
            queue.addLast(entry)
            persist(context)
        }
    }

    fun entries(context: Context): List<Entry> {
        synchronized(queue) {
            if (queue.isEmpty()) load(context)
            return queue.toList().reversed()
        }
    }

    fun format(entry: Entry): String {
        val time = dateFormat.format(Date(entry.timestamp))
        val status = if (entry.success) "✓" else "✗"
        return "$time $status ${entry.detail}"
    }

    private fun persist(context: Context) {
        val text = queue.joinToString(SEPARATOR) { "${it.timestamp},${it.success},${it.detail}" }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, text).apply()
    }

    private fun load(context: Context) {
        val text = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return
        queue.clear()
        text.split(SEPARATOR).forEach { line ->
            val parts = line.split(",", limit = 3)
            if (parts.size == 3) {
                val ts = parts[0].toLongOrNull() ?: return@forEach
                val ok = parts[1].toBooleanStrictOrNull() ?: return@forEach
                queue.addLast(Entry(ts, ok, parts[2]))
            }
        }
    }
}
