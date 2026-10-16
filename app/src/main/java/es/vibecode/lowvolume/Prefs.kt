package es.vibecode.lowvolume

import android.content.Context
import android.content.SharedPreferences

/** Persistent settings shared by the activity, the service and the boot receiver. */
object Prefs {
    const val KEY_ATTENUATION = "attenuation_db"
    const val KEY_ENABLED = "enabled"
    const val KEY_PER_APP = "per_app_mode"
    const val KEY_STATUS = "status"
    const val KEY_VOLUME_KEYS = "volume_keys"

    const val MAX_ATTENUATION_DB = 50f
    const val STEP_DB = 2.5f

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Attenuation in dB, as a positive number (12 means -12 dB). */
    fun attenuation(ctx: Context) = get(ctx).getFloat(KEY_ATTENUATION, 10f)

    fun setAttenuation(ctx: Context, db: Float) =
        get(ctx).edit().putFloat(KEY_ATTENUATION, VolumeLogic.clampAttenuation(db)).apply()

    fun enabled(ctx: Context) = get(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, v: Boolean) = get(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    fun perApp(ctx: Context) = get(ctx).getBoolean(KEY_PER_APP, false)
    fun setPerApp(ctx: Context, v: Boolean) = get(ctx).edit().putBoolean(KEY_PER_APP, v).apply()

    /** Whether the hardware volume keys extend below the system minimum. */
    fun volumeKeys(ctx: Context) = get(ctx).getBoolean(KEY_VOLUME_KEYS, true)
    fun setVolumeKeys(ctx: Context, v: Boolean) = get(ctx).edit().putBoolean(KEY_VOLUME_KEYS, v).apply()

    fun status(ctx: Context) = get(ctx).getString(KEY_STATUS, "") ?: ""
    fun setStatus(ctx: Context, s: String) = get(ctx).edit().putString(KEY_STATUS, s).apply()
}
