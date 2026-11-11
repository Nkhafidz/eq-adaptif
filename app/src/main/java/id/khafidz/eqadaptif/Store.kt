package id.khafidz.eqadaptif

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import kotlin.math.roundToInt

/** 10 band EQ: frekuensi tengah, label, dan batas koreksi per band. */
object Bands {
    const val N = 10
    val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val LABELS = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    /** Band paling ujung lebih "dijaga": sub-bass sangat bervariasi, MP3 sering terpotong di atas 16 kHz. */
    val EDGE_LIMIT = floatArrayOf(0.5f, 0.8f, 1f, 1f, 1f, 1f, 1f, 1f, 0.85f, 0.5f)

    /** Bobot kenop Bass dan Treble terhadap tiap band (seperti tone control). */
    val BASS_W = floatArrayOf(1f, 1f, 0.75f, 0.4f, 0.12f, 0f, 0f, 0f, 0f, 0f)
    val TREBLE_W = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.1f, 0.35f, 0.7f, 1f, 1f)

    /** Batas atas tiap band untuk filterbank DynamicsProcessing. */
    fun upperEdge(i: Int): Float = if (i == N - 1) 20000f else FREQS[i] * 1.41421356f
}

object Presets {
    val BUILT_IN: LinkedHashMap<String, FloatArray> = linkedMapOf(
        "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass Boost" to floatArrayOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f),
        "Bass Extreme" to floatArrayOf(10f, 9f, 7f, 4f, 1f, 0f, -1f, 0f, 1f, 2f),
        "Treble" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f),
        "Vokal" to floatArrayOf(-3f, -2f, -1f, 1f, 4f, 5f, 4f, 2f, 0f, -1f),
        "Akustik" to floatArrayOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 3f, 2f),
        "Rock" to floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f),
        "Pop" to floatArrayOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f),
        "Dangdut" to floatArrayOf(5f, 5f, 3f, 0f, -1f, 1f, 3f, 3f, 2f, 1f),
        "Jazz" to floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
        "Klasik" to floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Elektronik" to floatArrayOf(6f, 5f, 2f, 0f, -2f, 1f, 0f, 2f, 4f, 5f),
        "Hip Hop" to floatArrayOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, 0f, 2f, 3f),
        "Loudness" to floatArrayOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -3f, 5f, 2f),
        "Podcast" to floatArrayOf(-6f, -4f, -1f, 2f, 3f, 4f, 3f, 1f, -1f, -3f),
        "Malam (pelan)" to floatArrayOf(4f, 3f, 1f, 0f, 0f, 0f, 1f, 2f, 1f, 0f)
    )

    fun all(): LinkedHashMap<String, FloatArray> {
        val m = LinkedHashMap(BUILT_IN)
        m.putAll(Store.customPresets)
        return m
    }

    fun isCustom(name: String?): Boolean = name != null && Store.customPresets.containsKey(name)
}

/** Jenis keluaran suara, untuk preset otomatis per perangkat. */
object Outputs {
    const val SPEAKER = "speaker"
    const val WIRED = "wired"
    const val BT = "bt"
    val ALL = listOf(WIRED, BT, SPEAKER)
    fun label(t: String?): String = when (t) {
        WIRED -> "🎧 Headset kabel / USB"
        BT -> "🎧 Bluetooth (TWS, headset, speaker BT)"
        SPEAKER -> "🔈 Speaker HP"
        else -> "Tidak diketahui"
    }
}

/** Semua pengaturan pengguna, disimpan di SharedPreferences. */
object Store {
    private var sp: SharedPreferences? = null

    // EQ
    var gains = FloatArray(Bands.N)
    var preamp = 0f
    var bass = 0f
    var treble = 0f
    var presetName: String? = "Flat"
    val customPresets = LinkedHashMap<String, FloatArray>()

    // Efek
    var balance = 0          // -100 (kiri) .. 100 (kanan)
    var virtualizer = 0      // 0..1000
    var reverb = 0           // 0 = mati, 1..6 PresetReverb
    var limiterOn = true
    var limiterThreshold = -1f

    // AI Auto-seimbang
    var adaptiveOn = false
    var strength = 60
    var limit = 6
    var target: FloatArray? = null
    var targetName: String? = null

    // Perangkat
    var autoDevice = false
    val devicePresets = HashMap<String, String>()

    var legacy = true

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("eq", Context.MODE_PRIVATE)
        sp = p
        gains = arr(p.getString("gains", null)) ?: FloatArray(Bands.N)
        preamp = p.getFloat("preamp", 0f)
        bass = p.getFloat("bass", 0f)
        treble = p.getFloat("treble", 0f)
        presetName = p.getString("preset", "Flat")
        customPresets.clear()
        try {
            val o = JSONObject(p.getString("customPresets", "{}") ?: "{}")
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                arr(o.getString(k))?.let { customPresets[k] = it }
            }
        } catch (_: Exception) {}
        // migrasi dari versi lama (preset "Saya")
        arr(p.getString("custom", null))?.let { if (!customPresets.containsKey("Saya")) customPresets["Saya"] = it }

        balance = p.getInt("balance", 0)
        virtualizer = p.getInt("virtualizer", 0)
        reverb = p.getInt("reverb", 0)
        limiterOn = p.getBoolean("limiterOn", true)
        limiterThreshold = p.getFloat("limiterThreshold", -1f)

        adaptiveOn = p.getBoolean("adaptiveOn", false)
        strength = p.getInt("strength", 60)
        limit = p.getInt("limit", 6)
        target = arr(p.getString("target", null))
        targetName = p.getString("targetName", null)

        autoDevice = p.getBoolean("autoDevice", false)
        devicePresets.clear()
        try {
            val o = JSONObject(p.getString("devicePresets", "{}") ?: "{}")
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                devicePresets[k] = o.getString(k)
            }
        } catch (_: Exception) {}
        legacy = p.getBoolean("legacy", true)
    }

    fun save() {
        val cp = JSONObject()
        for ((k, v) in customPresets) cp.put(k, str(v))
        val dp = JSONObject()
        for ((k, v) in devicePresets) dp.put(k, v)
        sp?.edit()
            ?.putString("gains", str(gains))
            ?.putFloat("preamp", preamp)
            ?.putFloat("bass", bass)
            ?.putFloat("treble", treble)
            ?.putString("preset", presetName)
            ?.putString("customPresets", cp.toString())
            ?.remove("custom")
            ?.putInt("balance", balance)
            ?.putInt("virtualizer", virtualizer)
            ?.putInt("reverb", reverb)
            ?.putBoolean("limiterOn", limiterOn)
            ?.putFloat("limiterThreshold", limiterThreshold)
            ?.putBoolean("adaptiveOn", adaptiveOn)
            ?.putInt("strength", strength)
            ?.putInt("limit", limit)
            ?.putString("target", str(target))
            ?.putString("targetName", targetName)
            ?.putBoolean("autoDevice", autoDevice)
            ?.putString("devicePresets", dp.toString())
            ?.putBoolean("legacy", legacy)
            ?.apply()
    }

    /** Pasang preset: gain + preamp otomatis supaya tidak pecah. */
    fun applyPreset(name: String): Boolean {
        val g = Presets.all()[name] ?: return false
        gains = g.copyOf()
        val peak = (g.maxOrNull() ?: 0f).coerceAtLeast(0f)
        preamp = -((peak * 0.6f * 2).roundToInt() / 2f)
        presetName = name
        save()
        return true
    }

    /** Preset berikutnya / sebelumnya (untuk widget & notifikasi). */
    fun cyclePreset(step: Int): String {
        val names = Presets.all().keys.toList()
        val i = names.indexOf(presetName)
        val n = names[((if (i < 0) 0 else i + step) % names.size + names.size) % names.size]
        applyPreset(n)
        return n
    }

    /** Gain akhir per band dari preset + kenop Bass/Treble (tanpa koreksi AI). */
    fun userCurve(): FloatArray =
        FloatArray(Bands.N) { gains[it] + bass * Bands.BASS_W[it] + treble * Bands.TREBLE_W[it] }

    private fun arr(s: String?): FloatArray? {
        if (s.isNullOrBlank()) return null
        return try {
            val a = s.split(",").map { it.trim().toFloat() }.toFloatArray()
            if (a.size == Bands.N) a else null
        } catch (e: Exception) {
            null
        }
    }

    private fun str(a: FloatArray?): String = a?.joinToString(",") ?: ""
}

/** Status yang dibagikan dari service ke layar. Semua diakses di main thread. */
object AppState {
    var sessions: List<String> = emptyList()
    var corr = FloatArray(Bands.N)
    var frames = 0
    var title: String? = null
    var dpError: String? = null
    var vizError: String? = null
    var output: String = Outputs.SPEAKER

    /** Spektrum tampilan: SPEC_BINS titik log 20 Hz–20 kHz, nilai 0..1. */
    const val SPEC_BINS = 48
    val spectrum = FloatArray(SPEC_BINS)

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { listeners.toList().forEach { it() } }

    var spectrumListener: (() -> Unit)? = null
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
        "com.maxmpz.audioplayer" -> "Poweramp"
        null -> "Aplikasi tidak dikenal"
        else -> pkg
    }
}
