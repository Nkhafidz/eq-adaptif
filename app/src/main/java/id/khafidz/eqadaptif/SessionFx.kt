package id.khafidz.eqadaptif

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import kotlin.math.abs
import kotlin.math.log10

/** Parameter suara yang dipasang ke setiap sesi. */
data class FxParams(
    val gains: FloatArray,
    val preamp: Float,
    val balance: Int,
    val limiterOn: Boolean,
    val limiterThreshold: Float,
    val virtualizer: Int,
    val reverb: Int
)

/**
 * Semua efek untuk satu sesi audio:
 *  - DynamicsProcessing: EQ 10 band, preamp + balance per kanal, limiter
 *  - Virtualizer (stereo luas / 3D), opsional
 *  - PresetReverb, opsional
 */
class SessionFx(val sid: Int) {
    private val dp: DynamicsProcessing
    private var virt: Virtualizer? = null
    private var rev: PresetReverb? = null
    private var last: FxParams? = null
    var virtSupported = true
        private set

    init {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,          // stereo
            true, Bands.N,
            false, 0,
            false, 0,
            true
        ).build()
        dp = DynamicsProcessing(Int.MAX_VALUE, sid, cfg)
        dp.setEnabled(true)
    }

    fun apply(p: FxParams, force: Boolean = false) {
        val l = last
        // EQ
        if (force || l == null || p.gains.indices.any { abs(p.gains[it] - l.gains[it]) > 0.05f }) {
            for (i in 0 until Bands.N) {
                try {
                    dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, Bands.upperEdge(i), p.gains[i]))
                } catch (_: Exception) {}
            }
        }
        // Preamp + balance
        if (force || l == null || abs(p.preamp - l.preamp) > 0.05f || p.balance != l.balance) {
            val b = p.balance / 100f
            val left = if (b > 0) 20f * log10((1f - b).coerceAtLeast(0.01f)) else 0f
            val right = if (b < 0) 20f * log10((1f + b).coerceAtLeast(0.01f)) else 0f
            try {
                dp.setInputGainbyChannel(0, p.preamp + left)
                dp.setInputGainbyChannel(1, p.preamp + right)
            } catch (_: Exception) {
                try { dp.setInputGainAllChannelsTo(p.preamp) } catch (_: Exception) {}
            }
        }
        // Limiter
        if (force || l == null || p.limiterOn != l.limiterOn || abs(p.limiterThreshold - l.limiterThreshold) > 0.05f) {
            try {
                dp.setLimiterAllChannelsTo(
                    DynamicsProcessing.Limiter(true, p.limiterOn, 0, 1f, 60f, 10f, p.limiterThreshold, 0f)
                )
            } catch (_: Exception) {}
        }
        // Virtualizer
        if (force || l == null || p.virtualizer != l.virtualizer) applyVirtualizer(p.virtualizer)
        // Reverb
        if (force || l == null || p.reverb != l.reverb) applyReverb(p.reverb)
        last = p
    }

    private fun applyVirtualizer(strength: Int) {
        try {
            if (strength <= 0) {
                virt?.setEnabled(false)
                return
            }
            val v = virt ?: Virtualizer(0, sid).also { virt = it }
            virtSupported = v.strengthSupported
            v.setStrength(strength.coerceIn(0, 1000).toShort())
            v.setEnabled(true)
        } catch (_: Exception) {
            virtSupported = false
        }
    }

    private fun applyReverb(preset: Int) {
        try {
            if (preset <= 0) {
                rev?.setEnabled(false)
                return
            }
            val r = rev ?: PresetReverb(0, sid).also { rev = it }
            r.preset = preset.coerceIn(1, 6).toShort()
            r.setEnabled(true)
        } catch (_: Exception) {}
    }

    fun release() {
        try { dp.setEnabled(false); dp.release() } catch (_: Exception) {}
        try { virt?.release() } catch (_: Exception) {}
        try { rev?.release() } catch (_: Exception) {}
    }
}
