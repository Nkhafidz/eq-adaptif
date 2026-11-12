package id.khafidz.eqadaptif

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

/** Layar EQ Adaptif: AI penyeimbang suara di atas EQ pilihan pengguna. */
class MainActivity : Activity() {

    companion object {
        const val ACTION_AUTOSTART = "id.khafidz.eqadaptif.AUTOSTART"
    }

    private val cBg = 0xFF0E0F13.toInt()
    private val cPanel = 0xFF16181F.toInt()
    private val cPanel2 = 0xFF1D2029.toInt()
    private val cLine = 0xFF2A2E3A.toInt()
    private val cText = 0xFFE9ECF3.toInt()
    private val cMuted = 0xFF8A91A3.toInt()
    private val cViolet = 0xFFB794F6.toInt()
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var swRun: Switch
    private lateinit var tvState: TextView
    private lateinit var tvError: TextView
    private lateinit var spectrum: SpectrumView
    private lateinit var swAi: Switch
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tvBass: TextView
    private lateinit var tvMid: TextView
    private lateinit var tvTreble: TextView
    private lateinit var tvStrength: TextView
    private lateinit var sbStrength: SeekBar
    private lateinit var tvLimit: TextView
    private lateinit var sbLimit: SeekBar
    private lateinit var tvTarget: TextView
    private lateinit var tvComp: TextView
    private lateinit var sbComp: SeekBar
    private lateinit var btnLock: Button

    private var suppress = false
    private var startAfterPermission = false
    private val listener: () -> Unit = { refresh() }
    private val specListener: () -> Unit = { spectrum.invalidate() }

    // ---------------- Lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (EqService.instance == null) Store.recoverDvc(this)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        setContentView(build())
        if (intent?.action == ACTION_AUTOSTART && EqService.instance == null) startService()
        refreshAll()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == ACTION_AUTOSTART && EqService.instance == null) startService()
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(listener)
        AppState.spectrumListener = specListener
        refreshAll()
    }

    override fun onPause() {
        AppState.removeListener(listener)
        if (AppState.spectrumListener === specListener) AppState.spectrumListener = null
        super.onPause()
    }

    // ---------------- Izin & service ----------------

    private fun startService() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
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
            EqService.start(this)
        }
    }

    // ---------------- Tampilan ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(fill: Int, stroke: Int, radius: Int = 10): GradientDrawable =
        GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke) }

    private fun tv(text: String, size: Float = 14f, color: Int = cText, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
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

    private fun btn(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f; setTextColor(cText)
        background = rounded(cPanel2, cLine, 9)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(14), dp(9), dp(14), dp(9))
        setOnClickListener { onClick() }
    }

    private fun sw(text: String, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
        this.text = text; textSize = 15f; setTextColor(cText)
        thumbTintList = ColorStateList.valueOf(cViolet)
        setOnCheckedChangeListener { _, checked -> if (!suppress) onChange(checked) }
    }

    private fun seek(max: Int, onChange: (Int) -> Unit): SeekBar = SeekBar(this).apply {
        this.max = max
        progressTintList = ColorStateList.valueOf(cViolet)
        thumbTintList = ColorStateList.valueOf(cViolet)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser && !suppress) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun gap(parent: LinearLayout, h: Int) = parent.addView(View(this), LinearLayout.LayoutParams(match, dp(h)))

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(cBg); addView(root) }

        root.addView(tv("EQ Adaptif", 26f, cText, true))
        root.addView(tv("AI penyeimbang suara untuk Poweramp Equalizer & EQ lain", 13f, cMuted))
        gap(root, 14)

        // Status
        val st = card(root)
        swRun = sw("Aktif") { on -> if (on) startService() else EqService.stop(this) }
        st.addView(swRun)
        gap(st, 4)
        tvState = tv("", 13f, cMuted)
        st.addView(tvState)
        tvError = tv("", 12.5f, 0xFFFF6B6B.toInt())
        st.addView(tvError)

        // Spektrum + koreksi
        val sp = card(root)
        spectrum = SpectrumView(this)
        sp.addView(spectrum, LinearLayout.LayoutParams(match, dp(170)))
        sp.addView(tv("Hijau = musik yang sedang diputar. Ungu = koreksi AI saat ini (dB).", 11.5f, cMuted))

        // AI
        val ai = card(root, accent = true)
        ai.addView(tv("✦ AI Auto-seimbang", 17f, cText, true))
        ai.addView(tv("Mendengarkan tiap lagu lalu menyesuaikan bass, mid, dan treble supaya karakter suara tetap sama walau lagu berganti.", 12.5f, cMuted))
        gap(ai, 8)
        swAi = sw("AI Auto-seimbang") { on ->
            Store.aiOn = on; Store.save()
            EqWidget.updateAll(this)
            toast(if (on) "AI menyala" else "AI dijeda")
            refresh()
        }
        ai.addView(swAi)
        gap(ai, 6)
        tvStatus = tv("", 13.5f, cText).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF0F0D16.toInt(), 0xFF2B2540.toInt(), 10)
        }
        ai.addView(tvStatus, LinearLayout.LayoutParams(match, wrap))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(cViolet)
        }
        ai.addView(progress, LinearLayout.LayoutParams(match, dp(10)))
        val meters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun meter(label: String): TextView {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
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
        tvStrength = tv("", 13f, cMuted); ai.addView(tvStrength)
        sbStrength = seek(100) { p -> Store.strength = p; Store.save(); refreshLabels() }
        ai.addView(sbStrength)
        ai.addView(tv("Rendah = karakter asli lagu lebih terasa. Tinggi = semua lagu makin mirip.", 11.5f, cMuted))
        gap(ai, 8)
        tvLimit = tv("", 13f, cMuted); ai.addView(tvLimit)
        sbLimit = seek(8) { p -> Store.limit = p + 2; Store.save(); refreshLabels() }
        ai.addView(sbLimit)
        gap(ai, 8)
        tvComp = tv("", 13f, cMuted); ai.addView(tvComp)
        sbComp = seek(18) { p -> Store.comp = p / 2f; Store.save(); refreshLabels() }
        ai.addView(sbComp)
        ai.addView(tv("Menambah volume hanya saat AI sedang mengoreksi, untuk mengimbangi volume yang turun di sebagian HP. Atur sampai volumenya terasa sama dengan saat AI tidak mengoreksi.", 11.5f, cMuted))
        gap(ai, 8)
        tvTarget = tv("", 13f, cMuted); ai.addView(tvTarget)
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

        // Cara pakai
        val help = card(root)
        help.addView(tv("Cara pakai", 15f, cText, true))
        help.addView(tv(
            "1. Atur suara di EQ pilihan Anda (mis. Poweramp Equalizer): preset, DVC, bass, dan lainnya.\n" +
            "2. Nyalakan \"Aktif\" di sini, izinkan rekam audio & notifikasi.\n" +
            "3. Putar lagu yang suaranya sudah pas, tunggu progres penuh (±10 detik), tekan \"Kunci karakter lagu ini\".\n" +
            "4. Lagu berikutnya otomatis disesuaikan: yang terlalu nge-bass dikurangi, yang kurang treble ditambah.\n\n" +
            "Izin \"rekam audio\" hanya dipakai untuk membaca spektrum musik yang sedang diputar, bukan merekam suara Anda.",
            12.5f, cMuted
        ).apply { setLineSpacing(0f, 1.25f) })
        gap(help, 10)
        help.addView(btn("Pengaturan baterai (agar tidak dimatikan sistem)") {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        })

        return scroll
    }

    // ---------------- Refresh ----------------

    private fun sgn(x: Float): String = (if (x > 0) "+" else "") + String.format("%.1f", x)

    private fun avg(a: FloatArray, i: Int, j: Int): Float {
        var s = 0f
        for (k in i..j) s += a[k]
        return s / (j - i + 1)
    }

    private fun refreshAll() {
        suppress = true
        swAi.isChecked = Store.aiOn
        sbStrength.progress = Store.strength
        sbLimit.progress = Store.limit - 2
        sbComp.progress = (Store.comp * 2).roundToInt()
        suppress = false
        refreshLabels()
        refresh()
    }

    private fun refreshLabels() {
        tvStrength.text = "Kekuatan koreksi: ${Store.strength}%"
        tvLimit.text = "Batas koreksi per band: ±${Store.limit} dB"
        tvComp.text = "Penguat volume saat koreksi: +${String.format("%.1f", Store.comp)} dB"
        tvTarget.text = "Target: " + if (Store.target != null) "dikunci dari ${Store.targetName ?: "lagu referensi"}" else "Netral (bawaan)"
    }

    private fun refresh() {
        val svc = EqService.instance
        val running = svc != null
        suppress = true
        swRun.isChecked = running
        swAi.isChecked = Store.aiOn
        suppress = false

        tvState.text = when {
            !running -> "Mati. Nyalakan untuk mulai."
            AppState.bands > 0 -> "Berjalan · koreksi AI di atas EQ Anda (${AppState.bands} band) · " +
                if (AppState.fxActive) "sedang mengoreksi" else "tidak mengoreksi (suara asli)"
            else -> "Berjalan"
        }
        val err = listOfNotNull(if (running) AppState.error else null, if (running) AppState.vizError else null)
        tvError.text = err.joinToString("\n")
        tvError.visibility = if (err.isEmpty()) View.GONE else View.VISIBLE

        val frames = AppState.frames
        val prog = (frames * 100 / Adaptive.FULL).coerceAtMost(100)
        progress.progress = if (running) prog else 0
        btnLock.isEnabled = running && frames >= Adaptive.FULL * 6 / 10
        btnLock.alpha = if (btnLock.isEnabled) 1f else 0.45f

        val c = AppState.corr
        val active = running && Store.aiOn && frames >= Adaptive.READY
        val b = avg(c, 0, 2); val m = avg(c, 3, 6); val t = avg(c, 7, 9)
        tvBass.text = if (active) sgn(b) else "–"
        tvMid.text = if (active) sgn(m) else "–"
        tvTreble.text = if (active) sgn(t) else "–"
        spectrum.correction = if (active) c.copyOf() else FloatArray(10)
        if (!running) AppState.spectrum.fill(0f)
        spectrum.invalidate()

        tvStatus.text = when {
            !running -> "Nyalakan EQ Adaptif terlebih dahulu."
            frames == 0 -> "Putar musik untuk mulai menganalisis."
            frames < Adaptive.READY -> "Menganalisis karakter lagu… $prog%"
            !Store.aiOn -> {
                val sh = svc!!.adaptive.shape()
                val tg = Store.target ?: Adaptive.DEFAULT_TARGET
                "AI dijeda. Lagu ini dibanding target: bass ${sgn(avg(sh, 0, 2) - avg(tg, 0, 2))} dB, treble ${sgn(avg(sh, 7, 9) - avg(tg, 7, 9))} dB."
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
