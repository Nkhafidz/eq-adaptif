package id.khafidz.eqadaptif

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Band EQ. Mode 10 band (oktaf) atau 31 band (1/3 oktaf), dipilih pengguna.
 * AI Auto-seimbang selalu bekerja di 10 band (F10) lalu hasilnya disebar ke mode aktif.
 */
object Bands {
    val F10 = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val F31 = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
        800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
    )

    /** Batas koreksi AI per band (10 band). Ujung lebih dijaga. */
    val EDGE_LIMIT = floatArrayOf(0.5f, 0.8f, 1f, 1f, 1f, 1f, 1f, 1f, 0.85f, 0.5f)

    private val BASS_W10 = floatArrayOf(1f, 1f, 0.75f, 0.4f, 0.12f, 0f, 0f, 0f, 0f, 0f)
    private val TREBLE_W10 = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.1f, 0.35f, 0.7f, 1f, 1f)

    val FREQS: FloatArray get() = if (Store.bandMode == 31) F31 else F10
    val N: Int get() = FREQS.size

    fun label(f: Float): String = when {
        f >= 1000f -> { val k = f / 1000f; if (k == k.toInt().toFloat()) "${k.toInt()}k" else String.format("%.1fk", k) }
        f == f.toInt().toFloat() -> f.toInt().toString()
        else -> String.format("%.1f", f)
    }

    /** Batas atas tiap band untuk filterbank DynamicsProcessing (titik tengah geometris). */
    fun edges(freqs: FloatArray): FloatArray = FloatArray(freqs.size) { i ->
        if (i == freqs.size - 1) 20000f else sqrt(freqs[i] * freqs[i + 1])
    }

    private fun interp10(table: FloatArray, f: Float): Float {
        val lf = ln(f.toDouble())
        if (f <= F10[0]) return table[0]
        if (f >= F10[9]) return table[9]
        for (i in 0 until 9) {
            val a = ln(F10[i].toDouble()); val b = ln(F10[i + 1].toDouble())
            if (lf <= b) return (table[i] + (table[i + 1] - table[i]) * ((lf - a) / (b - a))).toFloat()
        }
        return table[9]
    }

    fun bassW(f: Float) = interp10(BASS_W10, f)
    fun trebleW(f: Float) = interp10(TREBLE_W10, f)

    fun freqsFor(size: Int): FloatArray = if (size == 31) F31 else F10

    /** Ubah kurva dari satu set band ke set lain (rata-rata saat memadatkan, interpolasi saat merenggangkan). */
    fun resample(src: FloatArray, dst: FloatArray): FloatArray {
        val sf = freqsFor(src.size)
        if (sf.size == dst.size) return src.copyOf()
        return FloatArray(dst.size) { j ->
            val f = dst[j]
            if (sf.size > dst.size) {
                var s = 0f; var n = 0
                for (i in sf.indices) if (sf[i] >= f / 1.41421356f && sf[i] < f * 1.41421356f) { s += src[i]; n++ }
                if (n > 0) s / n else interpAt(src, sf, f)
            } else interpAt(src, sf, f)
        }
    }

    private fun interpAt(v: FloatArray, fr: FloatArray, f: Float): Float {
        if (f <= fr[0]) return v[0]
        if (f >= fr[fr.size - 1]) return v[v.size - 1]
        val lf = ln(f.toDouble())
        for (i in 0 until fr.size - 1) {
            val a = ln(fr[i].toDouble()); val b = ln(fr[i + 1].toDouble())
            if (lf <= b) return (v[i] + (v[i + 1] - v[i]) * ((lf - a) / (b - a))).toFloat()
        }
        return v[v.size - 1]
    }
}

object Presets {
    /** Preset bawaan (10 band). */
    val BUILT_IN: LinkedHashMap<String, FloatArray> = linkedMapOf(
        "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass Boost" to floatArrayOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f),
        "Bass Ringan" to floatArrayOf(4f, 3.5f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Deep Bass" to floatArrayOf(10f, 8f, 4f, 1f, -1f, -1f, 0f, 0f, 0f, 0f),
        "Bass Extreme" to floatArrayOf(10f, 9f, 7f, 4f, 1f, 0f, -1f, 0f, 1f, 2f),
        "Treble" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f),
        "Treble Ringan" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 3.5f, 4f),
        "Jernih (Clarity)" to floatArrayOf(-1f, -1f, -1f, -0.5f, 0f, 1.5f, 3f, 3.5f, 3f, 2f),
        "V-Shape" to floatArrayOf(6f, 5f, 3f, 0f, -2f, -2f, 0f, 3f, 5f, 6f),
        "Vokal" to floatArrayOf(-3f, -2f, -1f, 1f, 4f, 5f, 4f, 2f, 0f, -1f),
        "Vokal Pria" to floatArrayOf(-1f, 0f, 2f, 3f, 3f, 2f, 1f, 1f, 0f, -1f),
        "Vokal Wanita" to floatArrayOf(-2f, -2f, -1f, 0f, 1f, 3f, 4f, 3f, 1f, 0f),
        "Akustik" to floatArrayOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 3f, 2f),
        "Gitar & Piano" to floatArrayOf(1f, 1f, 2f, 2f, 1f, 0f, 1f, 2f, 3f, 2f),
        "Pop" to floatArrayOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f),
        "K-Pop" to floatArrayOf(4f, 3f, 1f, 0f, -1f, 1f, 3f, 4f, 4f, 3f),
        "Rock" to floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f),
        "Metal" to floatArrayOf(6f, 5f, 2f, -1f, -3f, -1f, 2f, 4f, 5f, 4f),
        "Dangdut" to floatArrayOf(5f, 5f, 3f, 0f, -1f, 1f, 3f, 3f, 2f, 1f),
        "Koplo" to floatArrayOf(7f, 6f, 4f, 1f, -1f, 0f, 2f, 3f, 3f, 2f),
        "Campursari" to floatArrayOf(3f, 3f, 2f, 1f, 1f, 2f, 3f, 2f, 1f, 0f),
        "Keroncong" to floatArrayOf(1f, 1f, 1f, 2f, 2f, 2f, 3f, 2f, 1f, 0f),
        "Jazz" to floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
        "Klasik" to floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "R&B" to floatArrayOf(6f, 5f, 3f, 1f, -1f, -1f, 1f, 1f, 2f, 3f),
        "Hip Hop" to floatArrayOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, 0f, 2f, 3f),
        "Reggae" to floatArrayOf(4f, 4f, 2f, 0f, -1f, 1f, 2f, 2f, 1f, 0f),
        "Elektronik" to floatArrayOf(6f, 5f, 2f, 0f, -2f, 1f, 0f, 2f, 4f, 5f),
        "Lo-fi" to floatArrayOf(3f, 3f, 2f, 1f, 0f, 0f, -1f, -2f, -3f, -4f),
        "Live / Konser" to floatArrayOf(-2f, 0f, 2f, 3f, 3f, 3f, 2f, 1f, 1f, 1f),
        "Film" to floatArrayOf(5f, 4f, 2f, 0f, 0f, 1f, 3f, 2f, 1f, 0f),
        "Gaming" to floatArrayOf(4f, 3f, 0f, -1f, 0f, 2f, 4f, 5f, 4f, 2f),
        "Loudness" to floatArrayOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -3f, 5f, 2f),
        "Podcast" to floatArrayOf(-6f, -4f, -1f, 2f, 3f, 4f, 3f, 1f, -1f, -3f),
        "Speaker HP" to floatArrayOf(-6f, -4f, 0f, 3f, 3f, 2f, 2f, 1f, 0f, -1f),
        "Malam (pelan)" to floatArrayOf(4f, 3f, 1f, 0f, 0f, 0f, 1f, 2f, 1f, 0f)
    )

    const val AUTOEQ_PREFIX = "🎧 "

    /** Preset untuk baris chip & siklus: bawaan + buatan pengguna. */
    fun quick(): LinkedHashMap<String, FloatArray> {
        val m = LinkedHashMap(BUILT_IN)
        m.putAll(Store.customPresets)
        return m
    }

    fun isCustom(name: String?): Boolean = name != null && Store.customPresets.containsKey(name)
    fun isAutoEq(name: String?): Boolean = name != null && name.startsWith(AUTOEQ_PREFIX)

    fun lookup(name: String): FloatArray? =
        BUILT_IN[name] ?: Store.customPresets[name] ?: if (isAutoEq(name)) AutoEq.get(name.removePrefix(AUTOEQ_PREFIX)) else null
}

/** Database AutoEq (github.com/jaakkopasanen/AutoEq, lisensi MIT): koreksi untuk ±6.000 headset, 31 band. */
object AutoEq {
    class Entry(val name: String, val type: String, val gains: FloatArray)

    private var ctx: Context? = null
    private var entries: List<Entry>? = null
    private var byName: HashMap<String, Entry>? = null

    fun init(c: Context) { if (ctx == null) ctx = c.applicationContext }

    fun all(): List<Entry> {
        entries?.let { return it }
        val list = ArrayList<Entry>(6100)
        val map = HashMap<String, Entry>(8192)
        try {
            ctx?.assets?.open("autoeq.tsv")?.bufferedReader()?.useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    if (p.size < 3) continue
                    val g = p[2].split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
                    if (g.size != 31) continue
                    val e = Entry(p[0], p[1], g)
                    list.add(e); map[e.name] = e
                }
            }
        } catch (_: Exception) {}
        entries = list
        byName = map
        return list
    }

    fun get(name: String): FloatArray? {
        all()
        return byName?.get(name)?.gains
    }
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
    var bandMode = 10
    var gains = FloatArray(10)
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
    var limiterThreshold = -0.2f
    var compressor = 0       // 0 mati, 1 ringan, 2 sedang, 3 kuat
    /** true = preamp diturunkan otomatis agar tidak pecah (volume lebih kecil). false = volume penuh, limiter menjaga puncak. */
    var autoHeadroom = false
    /** Penguat volume (LoudnessEnhancer), dalam dB 0..12. */
    var loudBoost = 0f
    /** Kompensasi hasil kalibrasi: mengimbangi penurunan level dari mesin efek HP (dB). */
    var dpComp = 0f

    // DVC (Direct Volume Control): volume sistem dipasang tinggi, tombol volume mengatur level di dalam EQ
    var dvcOn = false
    /** true = volume sistem sedang dinaikkan oleh DVC (untuk pemulihan jika aplikasi tertutup tidak wajar). */
    var dvcEngaged = false
    var dvcRestore = -1
    val dvcSteps = HashMap<String, Int>()      // volume pilihan pengguna per keluaran (langkah sistem)

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
    /** Mode Pendamping: EQ sendiri mati, AI hanya menambah koreksi di atas EQ lain (Poweramp Equalizer, dll). */
    var companion = false

    fun init(ctx: Context) {
        AutoEq.init(ctx)
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("eq", Context.MODE_PRIVATE)
        sp = p
        bandMode = if (p.getInt("bandMode", 10) == 31) 31 else 10
        gains = arr(p.getString("gains", null))?.let { Bands.resample(it, Bands.FREQS) } ?: FloatArray(Bands.N)
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
        arr(p.getString("custom", null))?.let { if (!customPresets.containsKey("Saya")) customPresets["Saya"] = it }

        balance = p.getInt("balance", 0)
        virtualizer = p.getInt("virtualizer", 0)
        reverb = p.getInt("reverb", 0)
        limiterOn = p.getBoolean("limiterOn", true)
        limiterThreshold = p.getFloat("limiterThreshold", -0.2f)
        compressor = p.getInt("compressor", 0)
        if (!p.contains("autoHeadroom")) {
            // pembaruan dari versi lama: kembalikan volume penuh
            preamp = 0f
            limiterOn = true
        }
        autoHeadroom = p.getBoolean("autoHeadroom", false)
        loudBoost = p.getFloat("loudBoost", 0f)
        dpComp = p.getFloat("dpComp", 0f)
        dvcOn = p.getBoolean("dvcOn", false)
        dvcEngaged = p.getBoolean("dvcEngaged", false)
        dvcRestore = p.getInt("dvcRestore", -1)
        dvcSteps.clear()
        for (o in Outputs.ALL) if (p.contains("dvc_$o")) dvcSteps[o] = p.getInt("dvc_$o", 0)
        if (!p.contains("limiterV2")) {
            // limiter lebih lunak: hanya menahan puncak yang benar-benar melewati batas
            limiterThreshold = -0.2f
        }

        adaptiveOn = p.getBoolean("adaptiveOn", false)
        strength = p.getInt("strength", 60)
        limit = p.getInt("limit", 6)
        target = arr(p.getString("target", null))?.takeIf { it.size == 10 }
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
        companion = p.getBoolean("companion", false)
    }

    fun save() {
        val cp = JSONObject()
        for ((k, v) in customPresets) cp.put(k, str(v))
        val dp = JSONObject()
        for ((k, v) in devicePresets) dp.put(k, v)
        sp?.edit()
            ?.putInt("bandMode", bandMode)
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
            ?.putInt("compressor", compressor)
            ?.putBoolean("autoHeadroom", autoHeadroom)
            ?.putFloat("loudBoost", loudBoost)
            ?.putFloat("dpComp", dpComp)
            ?.putBoolean("dvcOn", dvcOn)
            ?.putBoolean("dvcEngaged", dvcEngaged)
            ?.putInt("dvcRestore", dvcRestore)
            ?.putBoolean("limiterV2", true)
            ?.putBoolean("adaptiveOn", adaptiveOn)
            ?.putInt("strength", strength)
            ?.putInt("limit", limit)
            ?.putString("target", str(target))
            ?.putString("targetName", targetName)
            ?.putBoolean("autoDevice", autoDevice)
            ?.putString("devicePresets", dp.toString())
            ?.putBoolean("legacy", legacy)
            ?.putBoolean("companion", companion)
            ?.apply()
        val e = sp?.edit() ?: return
        for ((k, v) in dvcSteps) e.putInt("dvc_$k", v)
        e.commit() // segera tertulis: penting untuk pemulihan volume
    }

    /**
     * Jika aplikasi tertutup saat DVC menaikkan volume sistem, kembalikan volume ke posisi pengguna.
     * Dipanggil saat aplikasi atau service dibuka.
     */
    fun recoverDvc(ctx: Context) {
        if (!dvcEngaged) return
        try {
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            if (dvcRestore >= 0) am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, dvcRestore, 0)
        } catch (_: Exception) {}
        dvcEngaged = false
        save()
    }

    /** Ganti jumlah band (10 / 31); kurva saat ini ikut dikonversi. */
    fun changeBandMode(mode: Int) {
        val m = if (mode == 31) 31 else 10
        if (m == bandMode) return
        val old = gains
        bandMode = m
        gains = Bands.resample(old, Bands.FREQS)
        save()
    }

    /** Pasang preset: gain + preamp otomatis supaya tidak pecah. Preset AutoEq otomatis memakai 31 band. */
    fun applyPreset(name: String): Boolean {
        val g = Presets.lookup(name) ?: return false
        if (Presets.isAutoEq(name) && bandMode != 31) bandMode = 31
        gains = Bands.resample(g, Bands.FREQS)
        preamp = if (autoHeadroom) {
            val peak = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f)
            val factor = if (Presets.isAutoEq(name)) 1f else 0.6f
            -((peak * factor * 2).roundToInt() / 2f)
        } else 0f
        presetName = name
        save()
        return true
    }

    /** Preset berikutnya / sebelumnya dari daftar cepat (untuk widget & notifikasi). */
    fun cyclePreset(step: Int): String {
        val names = Presets.quick().keys.toList()
        val i = names.indexOf(presetName)
        val n = names[((if (i < 0) 0 else i + step) % names.size + names.size) % names.size]
        applyPreset(n)
        return n
    }

    /** Gain akhir per band dari preset + kenop Bass/Treble (tanpa koreksi AI). */
    fun userCurve(): FloatArray {
        val f = Bands.FREQS
        return FloatArray(f.size) { gains.getOrElse(it) { 0f } + bass * Bands.bassW(f[it]) + treble * Bands.trebleW(f[it]) }
    }

    // ---- Ekspor / impor ----

    fun exportJson(): String {
        val o = JSONObject()
        o.put("app", "EQ Adaptif")
        o.put("version", 1)
        val ps = JSONObject()
        val src: Map<String, FloatArray> = if (customPresets.isEmpty()) mapOf("Preset saya" to userCurve()) else customPresets
        for ((k, v) in src) {
            val a = JSONArray()
            v.forEach { a.put(it.toDouble()) }
            ps.put(k, a)
        }
        o.put("presets", ps)
        val dp = JSONObject()
        for ((k, v) in devicePresets) dp.put(k, v)
        o.put("devicePresets", dp)
        return o.toString(2)
    }

    /** Mengembalikan jumlah preset yang diimpor. */
    fun importJson(text: String): Int {
        val o = JSONObject(text)
        val ps = o.optJSONObject("presets") ?: return 0
        var n = 0
        val keys = ps.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val a = ps.optJSONArray(k) ?: continue
            val g = FloatArray(a.length()) { a.optDouble(it, 0.0).toFloat().coerceIn(-15f, 15f) }
            if (g.size != 10 && g.size != 31) continue
            if (Presets.BUILT_IN.containsKey(k)) continue
            customPresets[k] = g
            n++
        }
        o.optJSONObject("devicePresets")?.let { dp ->
            val dk = dp.keys()
            while (dk.hasNext()) {
                val k = dk.next()
                val v = dp.optString(k)
                if (k in Outputs.ALL && Presets.lookup(v) != null) devicePresets[k] = v
            }
        }
        save()
        return n
    }

    private fun arr(s: String?): FloatArray? {
        if (s.isNullOrBlank()) return null
        return try {
            val a = s.split(",").map { it.trim().toFloat() }.toFloatArray()
            if (a.size == 10 || a.size == 31) a else null
        } catch (e: Exception) {
            null
        }
    }

    private fun str(a: FloatArray?): String = a?.joinToString(",") ?: ""
}

/** Status yang dibagikan dari service ke layar. Semua diakses di main thread. */
object AppState {
    var sessions: List<String> = emptyList()
    var corr = FloatArray(10)            // koreksi AI di 10 band
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
