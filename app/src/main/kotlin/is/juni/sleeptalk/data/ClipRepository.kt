package `is`.juni.sleeptalk.data

import android.content.Context
import java.io.File
import kotlin.math.abs

data class Clip(val file: File) {
    val name: String    get() = file.nameWithoutExtension.removePrefix("sleep_")
    val dateKey: String get() = name.take(8) // yyyyMMdd
    val durationSecs: Int get() {
        val dataBytes = (file.length() - 44).coerceAtLeast(0)
        return (dataBytes / (16000 * 1 * 2)).toInt()
    }
}

object ClipRepository {

    // ── Basic CRUD ────────────────────────────────────────────────────────────

    fun getAll(context: Context): List<Clip> {
        val dir = context.getExternalFilesDir("clips") ?: return emptyList()
        return dir.listFiles { f -> f.extension == "wav" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { Clip(it) }
            ?: emptyList()
    }

    fun delete(clip: Clip) = clip.file.delete()

    fun deleteOlderThan(context: Context, days: Int) {
        if (days <= 0) return
        val cutoff     = System.currentTimeMillis() - days * 24L * 3600 * 1000
        val favourites = getFavourites(context)
        getAll(context)
            .filter { it.file.lastModified() < cutoff && it.file.absolutePath !in favourites }
            .forEach { it.file.delete() }
    }

    // ── Labels (rename) ───────────────────────────────────────────────────────

    fun getLabels(context: Context): Map<String, String> =
        context.getSharedPreferences("labels", Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> if (v is String) k to v else null }
            .toMap()

    fun setLabel(context: Context, clip: Clip, label: String) =
        context.getSharedPreferences("labels", Context.MODE_PRIVATE)
            .edit().putString(clip.file.name, label.trim()).apply()

    // ── Favourites ────────────────────────────────────────────────────────────

    fun getFavourites(context: Context): Set<String> =
        context.getSharedPreferences("favourites", Context.MODE_PRIVATE)
            .getStringSet("paths", emptySet()) ?: emptySet()

    fun toggleFavourite(context: Context, clip: Clip): Boolean {
        val prefs   = context.getSharedPreferences("favourites", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("paths", emptySet())?.toMutableSet() ?: mutableSetOf()
        val path    = clip.file.absolutePath
        val starred = if (path in current) { current.remove(path); false }
                      else                 { current.add(path);    true  }
        prefs.edit().putStringSet("paths", current).apply()
        return starred
    }

    // ── Waveform ──────────────────────────────────────────────────────────────

    /** Returns [bars] normalised amplitude values (0..1) read from the WAV PCM data. */
    fun readWaveform(clip: Clip, bars: Int = 60): FloatArray = runCatching {
        val bytes = clip.file.readBytes()
        if (bytes.size <= 44) return@runCatching FloatArray(bars)
        val totalSamples   = (bytes.size - 44) / 2
        val samplesPerBar  = (totalSamples / bars).coerceAtLeast(1)
        val raw            = FloatArray(bars)
        var globalMax      = 1

        for (b in 0 until bars) {
            var maxAmp = 0
            var i = 44 + b * samplesPerBar * 2
            val end = (i + samplesPerBar * 2).coerceAtMost(bytes.size - 1)
            while (i < end) {
                val s = abs(((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toInt())
                if (s > maxAmp) maxAmp = s
                i += 2
            }
            raw[b] = maxAmp.toFloat()
            if (maxAmp > globalMax) globalMax = maxAmp
        }
        FloatArray(bars) { raw[it] / globalMax }
    }.getOrDefault(FloatArray(bars))
}
