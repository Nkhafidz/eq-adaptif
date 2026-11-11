package id.khafidz.eqadaptif

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {

    // Warna
    private val cBg = 0xFF0E0F13.toInt()
    private val cPanel = 0xFF16181F.toInt()
    private val cPanel2 = 0xFF1D2029.toInt()
    private val cLine = 0xFF2A2E3A.toInt()
    private val cText = 0xFFE9ECF3.toInt()
    private val cMuted = 0xFF8A91A3.toInt()
    private val cAccent = 0xFFFFB547.toInt()
    private val cTeal = 0xFF4FD1C5.toInt()
    private val cViolet = 0xFFB794F6.toInt()

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var swRun: Switch
    private lateinit var tvSessions: TextView
    private lateinit var tvError: TextView
    private lateinit var swAdaptive: Switch
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tvBass: TextView
    private lateinit var tvMid: TextView
    private lateinit var tvTreble: TextView
    private lateinit var tvTitle: TextView
    private lateinit var sbStrength: SeekBar
    private lateinit var tvStrength: TextView
    private lateinit var sbLimit: SeekBar
    private lateinit var tvLimit: TextView
    private lateinit var tvTarget: TextView
    private lateinit var btnLock: Button
    private lateinit var swLegacy: Switch
    private lateinit var sbPre: SeekBar
    private lateinit var tvPre: TextView
    private val gainBars = arrayOfNulls<SeekBar>(10)
    private val gainVals = arrayOfNulls<TextView>(10)
    private val corrVals = arrayOfNulls<TextView>(10)
    private val presetButtons = LinkedHashMap<String, Button>()
    private lateinit var presetRow: LinearLayout

    private var suppress = false
    private var startAfterPermission = false
    private val listener: () -> Unit = { refresh() }

    // ---------------- Lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        setContentView(build())
        handleIntent(intent)
        refreshAll()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(listener)
        refreshAll()
    }

    override fun onPause() {
        AppState.removeListener(listener)
        super.onPause()
    }

    /** Dibuka dari menu "Equalizer" di aplikasi musik: ikut membawa nomor sesi audionya. */
    private fun handleIntent(i: Intent?) {
        if (i == null || i.action != AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return
        val sid = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: callingPackage ?: "?"
        if (sid > 0) Sessions.onOpen(sid, pkg)
    }

    // ---------------- Izin & service ----------------

    private fun startEq() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            startAfterPermission = true
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        EqService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (startAfterPermission) {
            startAfterPermission = false
            EqService.start(this) // tetap jalan; tanpa izin rekam audio hanya Auto-seimbang yang mati
        }
    }

    // ---------------- Membangun tampilan ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(fill: Int, stroke: Int, radius: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun tv(text: String, size: Float = 14f, color: Int = cText, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(parent: LinearLayout, accent: Boolean = false): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(if (accent) 0xFF1A1626.toInt() else cPanel, if (accent) 0xFF3A2F55.toInt() else cLine, 14)
        }
        parent.addView(c, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(14) })
        return c
    }

    private fun btn(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            setTextColor(cText)
            background = rounded(cPanel2, cLine, 9)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { onClick() }
        }

    private fun sw(text: String, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(cText)
            thumbTintList = ColorStateList.valueOf(cAccent)
            setOnCheckedChangeListener { _, checked -> if (!suppress) onChange(checked) }
        }

    private fun seek(max: Int, color: Int, onChange: (Int) -> Unit): SeekBar =
        SeekBar(this).apply {
            this.max = max
            progressTintList = ColorStateList.valueOf(color)
            thumbTintList = ColorStateList.valueOf(color)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && !suppress) onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

    private fun gap(parent: LinearLayout, h: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(match, dp(h)))
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(cBg)
            addView(root)
        }

        // Judul
        root.addView(tv("EQ Adaptif", 26f, cText, true))
        root.addView(tv("Equalizer untuk YouTube Music, Spotify & aplikasi musik lain", 13f, cMuted))
        gap(root, 14)

        // --- Kartu status ---
        val st = card(root)
        swRun = sw("Equalizer aktif") { on -> if (on) startEq() else EqService.stop(this) }
        st.addView(swRun)
        gap(st, 6)
        tvSessions = tv("", 13f, cMuted)
        st.addView(tvSessions)
        tvError = tv("", 12.5f, 0xFFFF6B6B.toInt())
        st.addView(tvError)

        // --- Kartu Auto-seimbang ---
        val ai = card(root, accent = true)
        ai.addView(tv("✦ Auto-seimbang", 17f, cText, true))
        ai.addView(tv("Menjaga karakter suara tetap sama walau lagu berganti, tanpa mengubah preset Anda.", 12.5f, cMuted))
        gap(ai, 8)
        swAdaptive = sw("Auto-seimbang") { on ->
            Store.adaptiveOn = on; Store.save()
            toast(if (on) "Auto-seimbang menyala" else "Auto-seimbang mati")
            refresh()
        }
        swAdaptive.thumbTintList = ColorStateList.valueOf(cViolet)
        ai.addView(swAdaptive)
        gap(ai, 8)
        tvTitle = tv("", 12.5f, cMuted)
        ai.addView(tvTitle)
        tvStatus = tv("", 13.5f, cText).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF0F0D16.toInt(), 0xFF2B2540.toInt(), 10)
        }
        ai.addView(tvStatus, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(cViolet)
        }
        ai.addView(progress, LinearLayout.LayoutParams(match, dp(10)))

        val meters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun meter(label: String): TextView {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                background = rounded(0xFF0F0D16.toInt(), 0xFF2B2540.toInt(), 10)
            }
            box.addView(tv(label, 11f, cMuted).apply { gravity = Gravity.CENTER })
            val v = tv("–", 18f, cText, true).apply { gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE }
            box.addView(v)
            meters.addView(box, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginEnd = dp(6) })
            return v
        }
        tvBass = meter("BASS"); tvMid = meter("MID"); tvTreble = meter("TREBLE")
        ai.addView(meters, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })

        gap(ai, 10)
        tvStrength = tv("", 13f, cMuted)
        ai.addView(tvStrength)
        sbStrength = seek(100, cViolet) { p -> Store.strength = p; Store.save(); refreshLabels() }
        ai.addView(sbStrength)
        ai.addView(tv("Rendah = karakter asli lagu lebih terasa. Tinggi = semua lagu makin mirip.", 11.5f, cMuted))
        gap(ai, 8)
        tvLimit = tv("", 13f, cMuted)
        ai.addView(tvLimit)
        sbLimit = seek(8, cViolet) { p -> Store.limit = p + 2; Store.save(); refreshLabels() }
        ai.addView(sbLimit)
        gap(ai, 8)
        tvTarget = tv("", 13f, cMuted)
        ai.addView(tvTarget)
        val tRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnLock = btn("🔒 Kunci karakter lagu ini") {
            val ok = EqService.instance?.lockTarget() ?: false
            toast(if (ok) "Karakter suara dikunci" else "Tunggu analisis lagu minimal 60%")
            refreshLabels()
        }
        tRow.addView(btnLock)
        tRow.addView(btn("Pakai Netral") {
            Store.target = null; Store.targetName = null; Store.save()
            toast("Target kembali ke Netral"); refreshLabels()
        }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
        ai.addView(tRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })

        // --- Kartu preset & EQ manual ---
        val eq = card(root)
        eq.addView(tv("PRESET (SELERA ANDA)", 11.5f, cMuted))
        presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        eq.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(presetRow)
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
        renderPresets()
        val actRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actRow.addView(btn("Simpan sebagai \"Saya\"") {
            Store.custom = Store.gains.copyOf(); Store.presetName = "Saya"; Store.save()
            renderPresets(); refreshAll(); toast("Preset \"Saya\" disimpan")
        })
        actRow.addView(btn("Reset") { setPreset("Flat") }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
        eq.addView(actRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8); bottomMargin = dp(8) })

        fun row(label: String, color: Int, onChange: (Int) -> Unit): Triple<SeekBar, TextView, TextView> {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(tv(label, 12f, cMuted).apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(dp(40), wrap))
            val sb = seek(48, color, onChange)
            r.addView(sb, LinearLayout.LayoutParams(0, wrap, 1f))
            val v = tv("0", 12f, color).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.END }
            r.addView(v, LinearLayout.LayoutParams(dp(44), wrap))
            val c = tv("", 11f, cViolet).apply { typeface = Typeface.MONOSPACE; gravity = Gravity.END }
            r.addView(c, LinearLayout.LayoutParams(dp(44), wrap))
            eq.addView(r, LinearLayout.LayoutParams(match, dp(40)))
            return Triple(sb, v, c)
        }
        val pre = row("Pre", cTeal) { p ->
            Store.preamp = p / 2f - 12f; Store.save(); refreshLabels()
        }
        sbPre = pre.first; tvPre = pre.second
        for (i in 0 until 10) {
            val t = row(Bands.LABELS[i], cAccent) { p ->
                Store.gains[i] = p / 2f - 12f
                Store.presetName = null
                Store.save()
                highlightPreset(); refreshLabels()
            }
            gainBars[i] = t.first; gainVals[i] = t.second; corrVals[i] = t.third
        }
        eq.addView(tv("Kolom ungu = koreksi otomatis yang sedang ditambahkan oleh Auto-seimbang.", 11.5f, cMuted))

        // --- Kartu lanjutan ---
        val adv = card(root)
        adv.addView(tv("Pengaturan lanjutan", 15f, cText, true))
        gap(adv, 6)
        swLegacy = sw("Mode legacy (global)") { on ->
            Store.legacy = on; Store.save()
            toast(if (on) "Mode legacy menyala" else "Mode legacy mati")
        }
        adv.addView(swLegacy)
        adv.addView(tv("Nyalakan hanya jika aplikasi musik Anda tidak terdeteksi. Di sebagian HP mode ini tidak berfungsi.", 11.5f, cMuted))
        gap(adv, 12)
        adv.addView(btn("Pengaturan baterai (agar tidak dimatikan sistem)") {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        })

        // --- Cara pakai ---
        val help = card(root)
        help.addView(tv("Cara pakai", 15f, cText, true))
        help.addView(tv(
            "1. Nyalakan \"Equalizer aktif\" di atas, izinkan perekaman audio & notifikasi.\n" +
            "2. Buka YouTube Music lalu putar lagu. Jika belum terdeteksi, hentikan lalu putar ulang, atau tutup paksa YouTube Music dan buka lagi.\n" +
            "3. Pilih preset favorit Anda.\n" +
            "4. Putar lagu yang suaranya sudah pas, tunggu analisis penuh (±10 detik), tekan \"Kunci karakter lagu ini\".\n" +
            "5. Nyalakan Auto-seimbang. Lagu berikutnya disesuaikan otomatis.\n\n" +
            "Izin \"rekam audio\" hanya dipakai untuk membaca spektrum musik yang sedang diputar, bukan merekam suara Anda.",
            12.5f, cMuted
        ).apply { setLineSpacing(0f, 1.25f) })

        return scroll
    }

    // ---------------- Preset ----------------

    private fun allPresets(): LinkedHashMap<String, FloatArray> {
        val m = LinkedHashMap(Presets.ALL)
        Store.custom?.let { m["Saya"] = it }
        return m
    }

    private fun renderPresets() {
        presetRow.removeAllViews()
        presetButtons.clear()
        for (name in allPresets().keys) {
            val b = btn(name) { setPreset(name) }
            presetRow.addView(b, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = dp(6) })
            presetButtons[name] = b
        }
        highlightPreset()
    }

    private fun highlightPreset() {
        for ((name, b) in presetButtons) {
            val active = name == Store.presetName
            b.background = if (active) rounded(cAccent, cAccent, 9) else rounded(cPanel2, cLine, 9)
            b.setTextColor(if (active) 0xFF1A1205.toInt() else cText)
        }
    }

    private fun setPreset(name: String) {
        val g = allPresets()[name] ?: return
        Store.gains = g.copyOf()
        val peak = (g.maxOrNull() ?: 0f).coerceAtLeast(0f)
        Store.preamp = -((peak * 0.6f * 2).roundToInt() / 2f) // cegah suara pecah
        Store.presetName = name
        Store.save()
        refreshAll()
    }

    // ---------------- Refresh ----------------

    private fun sgn(x: Float): String = (if (x > 0) "+" else "") + String.format("%.1f", x)

    private fun refreshAll() {
        suppress = true
        swAdaptive.isChecked = Store.adaptiveOn
        swLegacy.isChecked = Store.legacy
        sbStrength.progress = Store.strength
        sbLimit.progress = Store.limit - 2
        sbPre.progress = ((Store.preamp + 12f) * 2).roundToInt()
        for (i in 0 until 10) gainBars[i]?.progress = ((Store.gains[i] + 12f) * 2).roundToInt()
        suppress = false
        highlightPreset()
        refreshLabels()
        refresh()
    }

    private fun refreshLabels() {
        tvStrength.text = "Kekuatan koreksi: ${Store.strength}%"
        tvLimit.text = "Batas koreksi per band: ±${Store.limit} dB"
        tvTarget.text = "Target: " + if (Store.target != null) "dikunci dari \"${Store.targetName ?: "lagu referensi"}\"" else "Netral (bawaan)"
        tvPre.text = sgn(Store.preamp)
        for (i in 0 until 10) gainVals[i]?.text = sgn(Store.gains[i])
    }

    private fun avg(a: FloatArray, i: Int, j: Int): Float {
        var s = 0f
        for (k in i..j) s += a[k]
        return s / (j - i + 1)
    }

    private fun refresh() {
        val svc = EqService.instance
        val running = svc != null
        suppress = true
        swRun.isChecked = running
        suppress = false

        tvSessions.text = when {
            !running -> "Mati. Nyalakan untuk mulai."
            AppState.sessions.isEmpty() -> "Menunggu aplikasi musik… Putar lagu di YouTube Music. Jika tidak muncul, hentikan lalu putar ulang lagunya."
            else -> "Terhubung ke: " + AppState.sessions.distinct().joinToString(", ")
        }
        val err = listOfNotNull(AppState.dpError, if (running) AppState.vizError else null)
        tvError.text = err.joinToString("\n")
        tvError.visibility = if (err.isEmpty()) View.GONE else View.VISIBLE

        tvTitle.text = AppState.title?.let { "♪ $it" } ?: ""
        tvTitle.visibility = if (AppState.title == null) View.GONE else View.VISIBLE

        val frames = AppState.frames
        val prog = (frames * 100 / Adaptive.FULL).coerceAtMost(100)
        progress.progress = if (running) prog else 0
        btnLock.isEnabled = running && frames >= Adaptive.FULL * 6 / 10
        btnLock.alpha = if (btnLock.isEnabled) 1f else 0.45f

        val C = AppState.corr
        val active = running && Store.adaptiveOn && frames >= Adaptive.READY
        val b = avg(C, 0, 2); val m = avg(C, 3, 6); val t = avg(C, 7, 9)
        tvBass.text = if (active) sgn(b) else "–"
        tvMid.text = if (active) sgn(m) else "–"
        tvTreble.text = if (active) sgn(t) else "–"
        for (i in 0 until 10) corrVals[i]?.text = if (active && abs(C[i]) >= 0.3f) sgn(C[i]) else ""

        tvStatus.text = when {
            !running -> "Nyalakan Equalizer terlebih dahulu."
            frames == 0 -> "Putar musik untuk mulai menganalisis."
            frames < Adaptive.READY -> "Menganalisis karakter lagu… $prog%"
            !Store.adaptiveOn -> {
                val sh = svc!!.adaptive.shape()
                val tg = Store.target ?: Adaptive.DEFAULT_TARGET
                val db = avg(sh, 0, 2) - avg(tg, 0, 2)
                val dt = avg(sh, 7, 9) - avg(tg, 7, 9)
                "Lagu ini dibanding target: bass ${sgn(db)} dB, treble ${sgn(dt)} dB. Nyalakan Auto-seimbang untuk menyesuaikan."
            }
            else -> {
                val parts = mutableListOf<String>()
                if (abs(b) >= 0.7f) parts.add((if (b < 0) "bass dikurangi " else "bass ditambah ") + String.format("%.1f", abs(b)) + " dB")
                if (abs(m) >= 0.7f) parts.add((if (m < 0) "mid dikurangi " else "mid ditambah ") + String.format("%.1f", abs(m)) + " dB")
                if (abs(t) >= 0.7f) parts.add((if (t < 0) "treble dikurangi " else "treble ditambah ") + String.format("%.1f", abs(t)) + " dB")
                "Aktif. " + (if (parts.isEmpty()) "Lagu ini sudah dekat dengan target." else parts.joinToString(", ") + ".") +
                    (if (prog < 100) " (masih menyesuaikan…)" else "")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
