package es.vibecode.lowvolume

import java.util.Locale
import kotlin.math.pow

/** Pure functions with the app's volume rules, kept free of Android APIs. */
object VolumeLogic {
    /** Lowest audible system volume index; the attenuation extends the range below it. */
    const val FLOOR_INDEX = 1

    /**
     * New attenuation after the system volume moved from [prev] to [value], or null if
     * the change must be left alone. Stepping down from [FLOOR_INDEX] adds attenuation
     * instead of muting (until the maximum), and stepping up from it removes attenuation
     * before the system volume is raised.
     */
    fun attenuationAfterVolumeChange(prev: Int, value: Int, attenuation: Float): Float? {
        if (prev != FLOOR_INDEX || value == prev) return null
        val next = when {
            value < prev && attenuation < Prefs.MAX_ATTENUATION_DB -> attenuation + Prefs.STEP_DB
            value > prev && attenuation > 0f -> attenuation - Prefs.STEP_DB
            else -> return null
        }
        return clampAttenuation(next)
    }

    fun clampAttenuation(db: Float) = db.coerceIn(0f, Prefs.MAX_ATTENUATION_DB)

    /** Remaining amplitude, in percent, for an attenuation in dB: 10^(-dB/20). */
    fun amplitudePercent(attenuation: Float) = 10.0.pow(-attenuation / 20.0) * 100

    fun formatDb(attenuation: Float, locale: Locale = Locale.getDefault()): String =
        if (attenuation == 0f) "0 dB" else String.format(locale, "-%.1f dB", attenuation)
}
