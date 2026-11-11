package id.khafidz.eqadaptif

import android.content.Context
import android.content.SharedPreferences

/** 10 band EQ: frekuensi tengah, label, dan batas koreksi per band. */
object Bands {
    val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val LABELS = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    /** Band paling ujung lebih "dijaga": sub-bass sangat bervariasi, MP3 sering terpotong di atas 16 kHz. */
    val EDGE_LIMIT = floatArrayOf(0.5f, 0.8f, 1f, 1f, 1f, 1f, 1f, 1f, 0.85f, 0.5f)

    /** Batas atas tiap band untuk filterbank DynamicsProcessing. */
    fun upperEdge(i: Int): Float = if (i == FREQS.size - 1) 20000f else FREQS[i] * 1.41421356f
}

object Presets {
    val ALL: LinkedHashMap<String, FloatArray> = linkedMapOf(
        "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass Boost" to floatArrayOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f),
        "Treble" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f),
        "Vokal" to floatArrayOf(-3f, -2f, -1f, 1f, 4f, 5f, 4f, 2f, 0f, -1f),
        "Rock" to floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f),
        "Pop" to floatArrayOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f),
        "Jazz" to floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
        "Klasik" to floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Elektronik" to floatArrayOf(6f, 5f, 2f, 0f, -2f, 1f, 0f, 2f, 4f, 5f),
        "Loudness" to floatArrayOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -3f, 5f, 2f),
        "Podcast" to floatArrayOf(-6f, -4f, -1f, 2f, 3f, 4f, 3f, 1f, -1f, -3f)
    )
}

/** Semua pengaturan pengguna, disimpan di SharedPreferences. */
object Store {
    private var sp: SharedPreferences? = null

    var gains = FloatArray(10)
    var preamp = 0f
    var presetName: String? = "Flat"
    var custom: FloatArray? = null
    var adaptiveOn = false
    var strength = 60          // %
    var limit = 6              // dB
    var target: FloatArray? = null
    var targetName: String? = null
    var legacy = false

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("eq", Context.MODE_PRIVATE)
        sp = p
        gains = arr(p.getString("gains", null)) ?: FloatArray(10)
        preamp = p.getFloat("preamp", 0f)
        presetName = p.getString("preset", "Flat")
        custom = arr(p.getString("custom", null))
        adaptiveOn = p.getBoolean("adaptiveOn", false)
        strength = p.getInt("strength", 60)
        limit = p.getInt("limit", 6)
        target = arr(p.getString("target", null))
        targetName = p.getString("targetName", null)
        legacy = p.getBoolean("legacy", false)
    }

    fun save() {
        sp?.edit()
            ?.putString("gains", str(gains))
            ?.putFloat("preamp", preamp)
            ?.putString("preset", presetName)
            ?.putString("custom", str(custom))
            ?.putBoolean("adaptiveOn", adaptiveOn)
            ?.putInt("strength", strength)
            ?.putInt("limit", limit)
            ?.putString("target", str(target))
            ?.putString("targetName", targetName)
            ?.putBoolean("legacy", legacy)
            ?.apply()
    }

    private fun arr(s: String?): FloatArray? {
        if (s.isNullOrBlank()) return null
        return try {
            val a = s.split(",").map { it.trim().toFloat() }.toFloatArray()
            if (a.size == 10) a else null
        } catch (e: Exception) {
            null
        }
    }

    private fun str(a: FloatArray?): String = a?.joinToString(",") ?: ""
}

/** Status yang dibagikan dari service ke layar. Semua diakses di main thread. */
object AppState {
    var sessions: List<String> = emptyList()
    var corr = FloatArray(10)
    var frames = 0
    var title: String? = null
    var dpError: String? = null
    var vizError: String? = null

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { listeners.toList().forEach { it() } }
}

/** Sesi audio yang diumumkan aplikasi musik (YouTube Music, Spotify, dll). */
object Sessions {
    val open = LinkedHashMap<Int, String>()

    fun onOpen(sid: Int, pkg: String) {
        open[sid] = pkg
        EqService.instance?.attach(sid)
    }

    fun onClose(sid: Int) {
        open.remove(sid)
        EqService.instance?.detach(sid)
    }

    fun friendly(pkg: String?): String = when (pkg) {
        "com.google.android.apps.youtube.music" -> "YouTube Music"
        "com.google.android.youtube" -> "YouTube"
        "com.spotify.music" -> "Spotify"
        "com.joox.music", "com.tencent.ibg.joox" -> "JOOX"
        "com.apple.android.music" -> "Apple Music"
        "com.sec.android.app.music" -> "Samsung Music"
        null -> "Aplikasi tidak dikenal"
        else -> pkg
    }
}
