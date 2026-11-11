package id.khafidz.eqadaptif

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.text.Editable
import android.text.TextWatcher
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

    companion object {
        const val ACTION_AUTOSTART = "id.khafidz.eqadaptif.AUTOSTART"
        const val REQ_EXPORT = 41
        const val REQ_IMPORT = 42
        val COMPRESSORS = arrayOf("Mati", "Ringan", "Sedang", "Kuat")
        val REVERBS = arrayOf("Mati", "Ruang kecil", "Ruang sedang", "Ruang besar", "Aula sedang", "Aula besar", "Plate")
    }

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

    // Status
    private lateinit var swRun: Switch
    private lateinit var tvSessions: TextView
    private lateinit var tvOutput: TextView
    private lateinit var tvError: TextView
    // EQ
    private lateinit var curve: EqCurveView
    private lateinit var presetRow: LinearLayout
    private lateinit var btnDelete: Button
    private lateinit var btnB10: Button
    private lateinit var btnB31: Button
    private lateinit var btnComp: Button
    private val presetButtons = LinkedHashMap<String, Button>()
    private lateinit var knobBass: KnobView
    private lateinit var knobTreble: KnobView
    private lateinit var knobPre: KnobView
    // AI
    private lateinit var swAdaptive: Switch
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tvBass: TextView
    private lateinit var tvMid: TextView
    private lateinit var tvTreble: TextView
    private lateinit var sbStrength: SeekBar
    private lateinit var tvStrength: TextView
    private lateinit var sbLimit: SeekBar
    private lateinit var tvLimit: TextView
    private lateinit var tvTarget: TextView
    private lateinit var btnLock: Button
    // Efek
    private lateinit var sbBalance: SeekBar
    private lateinit var tvBalance: TextView
    private lateinit var sbVirt: SeekBar
    private lateinit var tvVirt: TextView
    private lateinit var btnReverb: Button
    private lateinit var swLimiter: Switch
    private lateinit var swHeadroom: Switch
    private lateinit var sbLimTh: SeekBar
    private lateinit var tvLimTh: TextView
    // Perangkat
    private lateinit var swAutoDevice: Switch
    private val deviceButtons = HashMap<String, Button>()
    // Lanjutan
    private lateinit var swGlobal: Switch

    private var suppress = false
    private var startAfterPermission = false
    private val listener: () -> Unit = { refresh() }
    private val specListener: () -> Unit = { curve.invalidate() }

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
        AppState.spectrumListener = specListener
        refreshAll()
    }

    override fun onPause() {
        AppState.removeListener(listener)
        if (AppState.spectrumListener === specListener) AppState.spectrumListener = null
        super.onPause()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == ACTION_AUTOSTART) {
            if (EqService.instance == null) startEq()
            return
        }
        // Dibuka dari menu "Equalizer" aplikasi musik: ikut membawa nomor sesi audionya.
        if (i.action == AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) {
            val sid = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: callingPackage ?: "?"
            if (sid > 0) Sessions.onOpen(sid, pkg)
        }
    }

    /** Simpan + terapkan langsung ke service. */
    private fun commit() {
        Store.save()
        EqService.instance?.forceApply()
        EqWidget.updateAll(this)
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
            EqService.start(this)
        }
    }

    // ---------------- Pembangun tampilan ----------------

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

    private fun card(parent: LinearLayout, title: String? = null, accent: Boolean = false): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(if (accent) 0xFF1A1626.toInt() else cPanel, if (accent) 0xFF3A2F55.toInt() else cLine, 14)
        }
        parent.addView(c, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(14) })
        if (title != null) {
            c.addView(tv(title, 16f, cText, true))
            gap(c, 6)
        }
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

    private fun sw(text: String, thumb: Int = cAccent, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(cText)
            thumbTintList = ColorStateList.valueOf(thumb)
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

    private fun hrow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
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

        root.addView(tv("EQ Adaptif", 26f, cText, true))
        root.addView(tv("Equalizer + AI untuk YouTube Music & semua aplikasi musik", 13f, cMuted))
        gap(root, 14)

        // ===== Status =====
        val st = card(root)
        swRun = sw("Equalizer aktif") { on -> if (on) startEq() else EqService.stop(this) }
        st.addView(swRun)
        gap(st, 6)
        tvSessions = tv("", 13f, cMuted)
        st.addView(tvSessions)
        tvOutput = tv("", 13f, cMuted)
        st.addView(tvOutput)
        tvError = tv("", 12.5f, 0xFFFF6B6B.toInt())
        st.addView(tvError)

        // ===== Equalizer grafis =====
        val eq = card(root, "Equalizer")
        curve = EqCurveView(this)
        curve.onBandChange = { i, v ->
            Store.gains[i] = v
            Store.presetName = null
            EqService.instance?.forceApply()
            highlightPreset()
            updateCurve()
        }
        curve.onBandRelease = { commit() }
        val bRow = hrow()
        bRow.addView(tv("Jumlah band", 13f, cMuted), LinearLayout.LayoutParams(0, wrap, 1f))
        btnB10 = btn("10 band") { setBands(10) }
        btnB31 = btn("31 band") { setBands(31) }
        bRow.addView(btnB10)
        bRow.addView(btnB31, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(6) })
        eq.addView(bRow, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(8) })
        eq.addView(curve, LinearLayout.LayoutParams(match, dp(230)))
        eq.addView(tv("Geser titik ke atas/bawah. Garis ungu putus-putus = koreksi AI.", 11.5f, cMuted))
        gap(eq, 8)
        eq.addView(btn("🔍 Cari preset & headset (6.000+ AutoEq)") { searchDialog(null) },
            LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(8) })
        presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        eq.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(presetRow)
        }, LinearLayout.LayoutParams(match, wrap))
        val pAct = hrow()
        pAct.addView(btn("💾 Simpan preset") { savePresetDialog() })
        btnDelete = btn("🗑 Hapus") { deletePresetDialog() }
        pAct.addView(btnDelete, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
        pAct.addView(btn("Reset") {
            Store.applyPreset("Flat"); Store.bass = 0f; Store.treble = 0f; commit(); refreshAll()
        }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
        eq.addView(pAct, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        val ioRow = hrow()
        ioRow.addView(btn("⬆ Ekspor preset") {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").putExtra(Intent.EXTRA_TITLE, "eq-adaptif-preset.json"),
                    REQ_EXPORT
                )
            } catch (_: Exception) { toast("Pengelola file tidak tersedia") }
        })
        ioRow.addView(btn("⬇ Impor preset") {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                    REQ_IMPORT
                )
            } catch (_: Exception) { toast("Pengelola file tidak tersedia") }
        }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
        eq.addView(ioRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })

        // ===== Nada (kenop) =====
        val tone = card(root, "Nada")
        val knobs = hrow()
        knobBass = KnobView(this, "BASS", -12f, 12f, cAccent)
        knobTreble = KnobView(this, "TREBLE", -12f, 12f, cAccent)
        knobPre = KnobView(this, "PREAMP", -12f, 12f, cTeal)
        knobBass.onChange = { v -> Store.bass = v; EqService.instance?.forceApply(); updateCurve(); commitLater() }
        knobTreble.onChange = { v -> Store.treble = v; EqService.instance?.forceApply(); updateCurve(); commitLater() }
        knobPre.onChange = { v -> Store.preamp = v; EqService.instance?.forceApply(); commitLater() }
        for (k in listOf(knobBass, knobTreble, knobPre)) knobs.addView(k, LinearLayout.LayoutParams(0, wrap, 1f))
        tone.addView(knobs)
        tone.addView(tv("Putar dengan menggeser jari ke atas/bawah. Ketuk dua kali untuk kembali ke 0. Suara kurang keras? Naikkan PREAMP.", 11.5f, cMuted))

        // ===== AI Auto-seimbang =====
        val ai = card(root, null, accent = true)
        ai.addView(tv("✦ AI Auto-seimbang", 17f, cText, true))
        ai.addView(tv("Mendengarkan tiap lagu lalu menyesuaikan bass, mid, dan treble supaya karakter suaranya tetap sama walau lagu berganti. Preset Anda tetap berlaku.", 12.5f, cMuted))
        gap(ai, 8)
        swAdaptive = sw("AI Auto-seimbang", cViolet) { on ->
            Store.adaptiveOn = on; commit()
            toast(if (on) "AI Auto-seimbang menyala" else "AI Auto-seimbang mati")
            refresh()
        }
        ai.addView(swAdaptive)
        gap(ai, 6)
        tvStatus = tv("", 13.5f, cText).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF0F0D16.toInt(), 0xFF2B2540.toInt(), 10)
        }
        ai.addView(tvStatus, LinearLayout.LayoutParams(match, wrap))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(cViolet)
        }
        ai.addView(progress, LinearLayout.LayoutParams(match, dp(10)))
        val meters = hrow()
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
        sbStrength = seek(100, cViolet) { p -> Store.strength = p; commitLater(); refreshLabels() }
        ai.addView(sbStrength)
        ai.addView(tv("Rendah = karakter asli lagu lebih terasa. Tinggi = semua lagu makin mirip.", 11.5f, cMuted))
        gap(ai, 8)
        tvLimit = tv("", 13f, cMuted)
        ai.addView(tvLimit)
        sbLimit = seek(8, cViolet) { p -> Store.limit = p + 2; commitLater(); refreshLabels() }
        ai.addView(sbLimit)
        gap(ai, 8)
        tvTarget = tv("", 13f, cMuted)
        ai.addView(tvTarget)
        val tRow = hrow()
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

        // ===== Efek =====
        val fxc = card(root, "Efek")
        tvBalance = tv("", 13f, cMuted)
        fxc.addView(tvBalance)
        sbBalance = seek(200, cTeal) { p -> Store.balance = p - 100; EqService.instance?.forceApply(); commitLater(); refreshLabels() }
        fxc.addView(sbBalance)
        gap(fxc, 8)
        tvVirt = tv("", 13f, cMuted)
        fxc.addView(tvVirt)
        sbVirt = seek(100, cTeal) { p -> Store.virtualizer = p * 10; EqService.instance?.forceApply(); commitLater(); refreshLabels() }
        fxc.addView(sbVirt)
        fxc.addView(tv("Memperluas panggung suara. Paling terasa dengan headset.", 11.5f, cMuted))
        gap(fxc, 10)
        val rRow = hrow()
        rRow.addView(tv("Reverb", 14f, cText), LinearLayout.LayoutParams(0, wrap, 1f))
        btnReverb = btn("") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Reverb")
                .setSingleChoiceItems(REVERBS, Store.reverb) { dlg, which ->
                    Store.reverb = which; commit(); refreshLabels(); dlg.dismiss()
                }
                .show()
        }
        rRow.addView(btnReverb)
        fxc.addView(rRow)
        gap(fxc, 10)
        val cRow = hrow()
        cRow.addView(tv("Compressor", 14f, cText), LinearLayout.LayoutParams(0, wrap, 1f))
        btnComp = btn("") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Compressor")
                .setSingleChoiceItems(COMPRESSORS, Store.compressor) { dlg, which ->
                    Store.compressor = which; commit(); refreshLabels(); dlg.dismiss()
                }
                .show()
        }
        cRow.addView(btnComp)
        fxc.addView(cRow)
        fxc.addView(tv("Menyamakan volume bagian pelan dan keras, juga antar lagu.", 11.5f, cMuted))
        gap(fxc, 10)
        swHeadroom = sw("Turunkan volume otomatis saat EQ dinaikkan") { on ->
            Store.autoHeadroom = on
            if (!on) Store.preamp = 0f
            commit(); refreshAll()
            toast(if (on) "Volume diturunkan otomatis untuk mencegah distorsi" else "Volume penuh, limiter menjaga agar tidak pecah")
        }
        fxc.addView(swHeadroom)
        fxc.addView(tv("Mati (disarankan) = volume tetap penuh; limiter menahan puncak suara supaya tidak pecah. Nyalakan hanya jika masih terdengar pecah/kresek.", 11.5f, cMuted))
        gap(fxc, 10)
        swLimiter = sw("Limiter (cegah suara pecah)") { on -> Store.limiterOn = on; commit(); refreshLabels() }
        fxc.addView(swLimiter)
        tvLimTh = tv("", 13f, cMuted)
        fxc.addView(tvLimTh)
        sbLimTh = seek(24, cTeal) { p -> Store.limiterThreshold = -p / 2f; EqService.instance?.forceApply(); commitLater(); refreshLabels() }
        fxc.addView(sbLimTh)

        // ===== Perangkat =====
        val dev = card(root, "Preset per perangkat")
        swAutoDevice = sw("Ganti preset otomatis") { on ->
            Store.autoDevice = on; commit()
            toast(if (on) "Preset akan berganti sesuai perangkat" else "Ganti otomatis mati")
        }
        dev.addView(swAutoDevice)
        dev.addView(tv("Contoh: Bass Boost saat pakai TWS, Vokal saat pakai speaker HP.", 11.5f, cMuted))
        for (o in Outputs.ALL) {
            val r = hrow()
            r.addView(tv(Outputs.label(o), 13f, cText), LinearLayout.LayoutParams(0, wrap, 1f))
            val b = btn("") { pickDevicePreset(o) }
            r.addView(b)
            deviceButtons[o] = b
            dev.addView(r, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        }

        // ===== Lanjutan =====
        val adv = card(root, "Pengaturan lanjutan")
        swGlobal = sw("Mode global (disarankan untuk YouTube Music)") { on ->
            Store.legacy = on; commit()
            toast(if (on) "Mode global menyala" else "Mode global mati: EQ dipasang per aplikasi")
        }
        adv.addView(swGlobal)
        adv.addView(tv("Menerapkan EQ ke semua suara HP. Matikan hanya jika suara tidak berubah atau berlipat.", 11.5f, cMuted))
        gap(adv, 10)
        adv.addView(btn("Pengaturan baterai (agar tidak dimatikan sistem)") {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        })
        gap(adv, 8)
        adv.addView(tv(
            "Pintasan: tambahkan widget \"EQ Adaptif\" di layar utama, atau tombol \"EQ Adaptif\" di panel Quick Settings (tarik notifikasi → ikon pensil).",
            11.5f, cMuted
        ))

        gap(adv, 8)
        adv.addView(tv("Data preset headset: AutoEq oleh Jaakko Pasanen (github.com/jaakkopasanen/AutoEq), lisensi MIT.", 10.5f, cMuted))

        // ===== Cara pakai =====
        val help = card(root, "Cara pakai")
        help.addView(tv(
            "1. Nyalakan \"Equalizer aktif\", izinkan rekam audio & notifikasi.\n" +
            "2. Putar musik di YouTube Music. Mode global membuat EQ langsung bekerja.\n" +
            "3. Pilih preset, atau tekan \"Cari\" dan ketik nama headset/TWS Anda untuk koreksi AutoEq. Geser titik di grafik dan atur Bass/Treble sesuai selera.\n" +
            "4. AI: putar lagu yang suaranya sudah pas, tunggu analisis penuh (±10 detik), tekan \"Kunci karakter lagu ini\", lalu nyalakan AI Auto-seimbang.\n\n" +
            "Izin \"rekam audio\" hanya dipakai untuk membaca spektrum musik yang sedang diputar, bukan merekam suara Anda.",
            12.5f, cMuted
        ).apply { setLineSpacing(0f, 1.25f) })

        return scroll
    }

    // Simpan pengaturan sedikit tertunda supaya tidak menulis ke penyimpanan di setiap geseran.
    private val saveRunnable = Runnable { Store.save(); EqWidget.updateAll(this) }
    private fun commitLater() {
        curve.removeCallbacks(saveRunnable)
        curve.postDelayed(saveRunnable, 400)
    }

    // ---------------- Preset ----------------

    private fun renderPresets() {
        presetRow.removeAllViews()
        presetButtons.clear()
        val names = Presets.quick().keys.toMutableList()
        Store.presetName?.let { if (Presets.isAutoEq(it)) names.add(0, it) }
        for (name in names) {
            val b = btn(if (Presets.isCustom(name)) "★ $name" else name) {
                Store.applyPreset(name); commit(); refreshAll()
            }
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
        btnDelete.isEnabled = Presets.isCustom(Store.presetName)
        btnDelete.alpha = if (btnDelete.isEnabled) 1f else 0.4f
    }

    private fun savePresetDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "Nama preset, mis. TWS Malam"
            setText(if (Presets.isCustom(Store.presetName)) Store.presetName else "")
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(match, wrap)) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Simpan preset")
            .setMessage("Menyimpan kurva EQ saat ini (termasuk kenop Bass/Treble).")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || Presets.BUILT_IN.containsKey(name) || Presets.isAutoEq(name)) {
                    toast("Nama kosong atau sama dengan preset bawaan"); return@setPositiveButton
                }
                Store.customPresets[name] = Store.userCurve().map { (it * 2).roundToInt() / 2f }.toFloatArray()
                Store.bass = 0f; Store.treble = 0f
                Store.applyPreset(name)
                commit(); renderPresets(); refreshAll()
                toast("Preset \"$name\" disimpan")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun deletePresetDialog() {
        val name = Store.presetName ?: return
        if (!Presets.isCustom(name)) return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Hapus preset \"$name\"?")
            .setPositiveButton("Hapus") { _, _ ->
                Store.customPresets.remove(name)
                Store.devicePresets.entries.removeAll { it.value == name }
                Store.presetName = null
                commit(); renderPresets(); refreshAll()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun pickDevicePreset(output: String) {
        val names = listOf("— Tidak diatur —", "🔍 Cari headset (AutoEq)…") + Presets.quick().keys.toList()
        val cur = names.indexOf(Store.devicePresets[output] ?: "").coerceAtLeast(0)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(Outputs.label(output))
            .setSingleChoiceItems(names.toTypedArray(), cur) { dlg, which ->
                dlg.dismiss()
                when (which) {
                    0 -> { Store.devicePresets.remove(output); commit(); refreshLabels() }
                    1 -> searchDialog { picked -> Store.devicePresets[output] = picked; commit(); refreshLabels() }
                    else -> { Store.devicePresets[output] = names[which]; commit(); refreshLabels() }
                }
            }
            .show()
    }

    /** Cari di semua preset: bawaan, buatan Anda, dan ±6.000 headset AutoEq. */
    private fun searchDialog(onPick: ((String) -> Unit)?) {
        val all = AutoEq.all()
        val quick = Presets.quick().keys.toList()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val input = EditText(this).apply {
            hint = "Ketik nama headset/preset, mis. Galaxy Buds, KZ, Dangdut"
            setSingleLine()
        }
        val info = tv("", 11.5f, cMuted)
        val list = ListView(this)
        val shown = ArrayList<String>()   // nama preset sebenarnya
        val labels = ArrayList<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.adapter = adapter
        box.addView(input, LinearLayout.LayoutParams(match, wrap))
        box.addView(info)
        box.addView(list, LinearLayout.LayoutParams(match, dp(380)))

        fun filter(q: String) {
            val t = q.trim().lowercase()
            shown.clear(); labels.clear()
            for (n in quick) if (t.isEmpty() || n.lowercase().contains(t)) {
                shown.add(n); labels.add((if (Presets.isCustom(n)) "★ " else "♪ ") + n)
            }
            var count = 0
            if (t.length >= 2) {
                val words = t.split(" ").filter { it.isNotBlank() }
                for (e in all) {
                    val ln = e.name.lowercase()
                    if (words.all { ln.contains(it) }) {
                        count++
                        if (count <= 300) { shown.add(Presets.AUTOEQ_PREFIX + e.name); labels.add("🎧 ${e.name}  ·  ${e.type}") }
                    }
                }
            }
            info.text = when {
                t.length < 2 -> "Ketik minimal 2 huruf untuk mencari di ${all.size} headset."
                count > 300 -> "$count headset cocok, menampilkan 300 pertama. Perjelas pencarian."
                else -> "$count headset cocok."
            }
            adapter.notifyDataSetChanged()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filter(s?.toString() ?: "") }
        })
        filter("")
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (onPick == null) "Cari preset" else "Pilih preset")
            .setView(box)
            .setNegativeButton("Tutup", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            val name = shown.getOrNull(pos) ?: return@setOnItemClickListener
            dlg.dismiss()
            if (onPick != null) {
                onPick(name)
            } else {
                val wasMode = Store.bandMode
                Store.applyPreset(name)
                commit(); refreshAll()
                if (Presets.isAutoEq(name) && wasMode != 31) toast("Preset headset memakai 31 band supaya koreksinya akurat")
                else toast("Preset: ${name.removePrefix(Presets.AUTOEQ_PREFIX)}")
            }
        }
        dlg.show()
    }

    private fun setBands(mode: Int) {
        if (Store.bandMode == mode) return
        Store.changeBandMode(mode)
        commit(); refreshAll()
        toast("$mode band")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(Store.exportJson().toByteArray()) }
                    toast("Preset diekspor")
                }
                REQ_IMPORT -> {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    val n = Store.importJson(text)
                    commit(); refreshAll()
                    toast(if (n > 0) "$n preset diimpor" else "Tidak ada preset yang bisa diimpor dari file ini")
                }
            }
        } catch (e: Exception) {
            toast("Gagal: ${e.message ?: "file tidak valid"}")
        }
    }

    // ---------------- Refresh ----------------

    private fun sgn(x: Float): String = (if (x > 0) "+" else "") + String.format("%.1f", x)

    private fun updateCurve() {
        val user = Store.userCurve()
        val corrOn = Store.adaptiveOn && EqService.instance != null && AppState.frames >= Adaptive.READY
        curve.userGains = Store.gains.copyOf()
        val corr = Bands.resample(AppState.corr, Bands.FREQS)
        curve.totalGains = FloatArray(user.size) { user[it] + if (corrOn) corr[it] else 0f }
        curve.correction = if (corrOn) corr else null
        curve.invalidate()
    }

    private fun refreshAll() {
        suppress = true
        swAdaptive.isChecked = Store.adaptiveOn
        swGlobal.isChecked = Store.legacy
        swAutoDevice.isChecked = Store.autoDevice
        swLimiter.isChecked = Store.limiterOn
        swHeadroom.isChecked = Store.autoHeadroom
        sbStrength.progress = Store.strength
        sbLimit.progress = Store.limit - 2
        sbBalance.progress = Store.balance + 100
        sbVirt.progress = Store.virtualizer / 10
        sbLimTh.progress = (-Store.limiterThreshold * 2).roundToInt()
        knobBass.value = Store.bass
        knobTreble.value = Store.treble
        knobPre.value = Store.preamp
        suppress = false
        renderPresets()
        refreshLabels()
        refresh()
    }

    private fun refreshLabels() {
        tvStrength.text = "Kekuatan koreksi: ${Store.strength}%"
        tvLimit.text = "Batas koreksi per band: ±${Store.limit} dB"
        tvTarget.text = "Target: " + if (Store.target != null) "dikunci dari ${Store.targetName ?: "lagu referensi"}" else "Netral (bawaan)"
        tvBalance.text = "Balance: " + when {
            Store.balance == 0 -> "tengah"
            Store.balance < 0 -> "kiri ${-Store.balance}%"
            else -> "kanan ${Store.balance}%"
        }
        tvVirt.text = "Stereo luas (Virtualizer): ${Store.virtualizer / 10}%"
        btnReverb.text = REVERBS[Store.reverb.coerceIn(0, REVERBS.size - 1)]
        btnComp.text = COMPRESSORS[Store.compressor.coerceIn(0, COMPRESSORS.size - 1)]
        for ((b, m) in listOf(btnB10 to 10, btnB31 to 31)) {
            val on = Store.bandMode == m
            b.background = if (on) rounded(cAccent, cAccent, 9) else rounded(cPanel2, cLine, 9)
            b.setTextColor(if (on) 0xFF1A1205.toInt() else cText)
        }
        tvLimTh.text = "Ambang limiter: ${sgn(Store.limiterThreshold)} dB" +
            if (!Store.autoHeadroom && !Store.limiterOn) " (tetap aktif selama volume penuh)" else ""
        sbLimTh.isEnabled = Store.limiterOn || !Store.autoHeadroom
        for ((o, b) in deviceButtons) b.text = Store.devicePresets[o]?.removePrefix(Presets.AUTOEQ_PREFIX)?.let { if (it.length > 22) it.take(21) + "…" else it } ?: "Pilih preset"
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
            AppState.sessions.isEmpty() -> "Menunggu aplikasi musik… Nyalakan Mode global di bawah jika YouTube Music tidak terdeteksi."
            else -> "Terhubung ke: " + AppState.sessions.distinct().joinToString(", ")
        }
        tvOutput.text = "Keluaran: " + Outputs.label(AppState.output) +
            (if (Store.autoDevice) Store.devicePresets[AppState.output]?.let { " → $it" } ?: "" else "")
        val err = listOfNotNull(AppState.dpError, if (running) AppState.vizError else null)
        tvError.text = err.joinToString("\n")
        tvError.visibility = if (err.isEmpty()) View.GONE else View.VISIBLE

        val frames = AppState.frames
        val prog = (frames * 100 / Adaptive.FULL).coerceAtMost(100)
        progress.progress = if (running) prog else 0
        btnLock.isEnabled = running && frames >= Adaptive.FULL * 6 / 10
        btnLock.alpha = if (btnLock.isEnabled) 1f else 0.45f

        val c = AppState.corr
        val active = running && Store.adaptiveOn && frames >= Adaptive.READY && AppState.sessions.isNotEmpty()
        val b = avg(c, 0, 2); val m = avg(c, 3, 6); val t = avg(c, 7, 9)
        tvBass.text = if (active) sgn(b) else "–"
        tvMid.text = if (active) sgn(m) else "–"
        tvTreble.text = if (active) sgn(t) else "–"

        tvStatus.text = when {
            !running -> "Nyalakan Equalizer terlebih dahulu."
            frames == 0 -> "Putar musik untuk mulai menganalisis."
            AppState.sessions.isEmpty() && frames >= Adaptive.READY ->
                "Musik terdeteksi, tapi EQ belum terhubung ke aplikasi musik. Nyalakan Mode global di Pengaturan lanjutan."
            frames < Adaptive.READY -> "Menganalisis karakter lagu… $prog%"
            !Store.adaptiveOn -> {
                val sh = svc!!.adaptive.shape()
                val tg = Store.target ?: Adaptive.DEFAULT_TARGET
                val db = avg(sh, 0, 2) - avg(tg, 0, 2)
                val dt = avg(sh, 7, 9) - avg(tg, 7, 9)
                "Lagu ini dibanding target: bass ${sgn(db)} dB, treble ${sgn(dt)} dB. Nyalakan AI untuk menyesuaikan otomatis."
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
        if (!running) AppState.spectrum.fill(0f)
        // Preset bisa berubah dari widget/notifikasi/perangkat
        if (presetButtons.isNotEmpty()) highlightPreset()
        updateCurve()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
