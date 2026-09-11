package com.dsh.android.data.store

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.dsh.android.data.protocol.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** User-chosen app background. Purely cosmetic — stored unencrypted. */
@Serializable
data class AppBackground(
    /** none | solid | gradient | image */
    val kind: String = "none",
    /** ARGB color for `solid`, start color for `gradient`. */
    val color: Long? = null,
    /** End color for `gradient`. */
    val color2: Long? = null,
    /** Absolute path of the picked image for `image`. */
    val imagePath: String? = null,
) {
    companion object {
        val None = AppBackground()
    }
}

/**
 * Persists the cosmetic background choice. Unlike [ServerConfigStore], this
 * holds no secrets, so plain SharedPreferences is the right trade-off.
 */
class BackgroundStore(context: Context) {

    private val prefs = context.getSharedPreferences("dsh_ui", Context.MODE_PRIVATE)
    private val key = "background"

    private val _current = MutableStateFlow(load())
    val current: StateFlow<AppBackground> = _current.asStateFlow()

    private fun load(): AppBackground {
        val raw = prefs.getString(key, null) ?: return AppBackground.None
        return runCatching { Wire.json.decodeFromString(AppBackground.serializer(), raw) }
            .getOrDefault(AppBackground.None)
    }

    fun set(bg: AppBackground) {
        prefs.edit().putString(key, Wire.json.encodeToString(AppBackground.serializer(), bg)).apply()
        _current.value = bg
    }

    /** Copy a picked image into app-private storage so it survives reboot. */
    fun saveImage(context: Context, uri: Uri): AppBackground {
        val ext = inferExt(context, uri)
        val dir = File(context.filesDir, "backgrounds").apply { mkdirs() }
        val target = File(dir, "bg-${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return _current.value
        val bg = AppBackground(kind = "image", imagePath = target.absolutePath)
        set(bg)
        return bg
    }

    private fun inferExt(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri)
        val fromMime = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return fromMime ?: uri.lastPathSegment?.substringAfterLast('.', "")?.take(6)
            ?.ifBlank { "jpg" } ?: "jpg"
    }
}
