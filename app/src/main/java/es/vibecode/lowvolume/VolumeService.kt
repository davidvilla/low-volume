package es.vibecode.lowvolume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Foreground service that keeps a DynamicsProcessing effect with negative input gain
 * attached to the audio output. In global mode it uses session 0 (the output mix);
 * in per-app mode it attaches to the sessions that players announce via
 * ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.
 */
class VolumeService : Service() {

    companion object {
        private const val TAG = "LowVolume"
        const val ACTION_APPLY = "es.vibecode.lowvolume.APPLY"
        const val ACTION_STOP = "es.vibecode.lowvolume.STOP"
        const val ACTION_QUIETER = "es.vibecode.lowvolume.QUIETER"
        const val ACTION_LOUDER = "es.vibecode.lowvolume.LOUDER"
        private const val CHANNEL_ID = "low_volume"
        private const val NOTIFICATION_ID = 1
        private const val GLOBAL_SESSION = 0
        private const val PRIORITY = Int.MAX_VALUE
        // Hidden AudioManager constants for the volume changed broadcast.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, VolumeService::class.java).setAction(ACTION_APPLY))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, VolumeService::class.java))
        }
    }

    private val effects = mutableMapOf<Int, DynamicsProcessing>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var sessionReceiverRegistered = false

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (session <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attach(session)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> release(session)
            }
            updateStatus()
        }
    }

    /** Makes the volume keys continue below the system minimum (see VolumeLogic). */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            val prev = intent.getIntExtra(EXTRA_PREV_VALUE, -1)
            val value = intent.getIntExtra(EXTRA_VALUE, -1)
            val newAtt = VolumeLogic.attenuationAfterVolumeChange(
                prev, value, Prefs.attenuation(context)
            ) ?: return
            // Our own reset produces another broadcast with prev != FLOOR_INDEX, so no loop.
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, VolumeLogic.FLOOR_INDEX, 0)
            Prefs.setAttenuation(context, newAtt)
            applyEffects()
        }
    }
    private var volumeReceiverRegistered = false

    // When the output route changes (headphones, Bluetooth...) some devices drop
    // global effects, so the global one is recreated.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = scheduleReattach()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = scheduleReattach()
    }
    private val reattach = Runnable {
        if (!Prefs.perApp(this)) {
            release(GLOBAL_SESSION)
            attach(GLOBAL_SESSION)
            updateStatus()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_QUIETER -> Prefs.setAttenuation(this, Prefs.attenuation(this) + Prefs.STEP_DB)
            ACTION_LOUDER -> Prefs.setAttenuation(this, Prefs.attenuation(this) - Prefs.STEP_DB)
        }
        Prefs.setEnabled(this, true)
        goForeground()
        applyEffects()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        setSessionReceiver(false)
        setVolumeReceiver(false)
        effects.keys.toList().forEach(::release)
        Prefs.setStatus(this, "")
        super.onDestroy()
    }

    private fun applyEffects() {
        setVolumeReceiver(Prefs.volumeKeys(this))
        if (Prefs.perApp(this)) {
            release(GLOBAL_SESSION)
            setSessionReceiver(true)
        } else {
            setSessionReceiver(false)
            effects.keys.filter { it != GLOBAL_SESSION }.forEach(::release)
            if (GLOBAL_SESSION !in effects) attach(GLOBAL_SESSION)
        }
        val gain = -Prefs.attenuation(this)
        effects.values.forEach { applyGain(it, gain) }
        updateStatus()
    }

    private fun attach(session: Int) {
        if (session in effects) return
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,            // channels
                false, 0,     // pre-EQ
                false, 0,     // multiband compressor
                false, 0,     // post-EQ
                false         // limiter
            ).build()
            val dp = DynamicsProcessing(PRIORITY, session, config)
            dp.setControlStatusListener { effect, hasControl ->
                // Another app took the effect and gave it back: re-apply our settings.
                if (hasControl) applyGain(effect as DynamicsProcessing, -Prefs.attenuation(this))
            }
            applyGain(dp, -Prefs.attenuation(this))
            effects[session] = dp
        } catch (e: Exception) {
            Log.w(TAG, "Cannot attach effect to session $session", e)
            if (session == GLOBAL_SESSION) {
                Prefs.setStatus(this, getString(R.string.status_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun applyGain(dp: DynamicsProcessing, gainDb: Float) {
        try {
            dp.setInputGainAllChannelsTo(gainDb)
            dp.enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot set gain", e)
        }
    }

    private fun release(session: Int) {
        effects.remove(session)?.let {
            try { it.enabled = false } catch (_: Exception) {}
            it.release()
        }
    }

    private fun setSessionReceiver(on: Boolean) {
        if (on == sessionReceiverRegistered) return
        if (on) {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            // Players in other apps send these broadcasts, so the receiver must be exported.
            registerReceiver(sessionReceiver, filter, RECEIVER_EXPORTED)
        } else {
            unregisterReceiver(sessionReceiver)
        }
        sessionReceiverRegistered = on
    }

    private fun setVolumeReceiver(on: Boolean) {
        if (on == volumeReceiverRegistered) return
        if (on) {
            registerReceiver(volumeReceiver, IntentFilter(VOLUME_CHANGED_ACTION), RECEIVER_EXPORTED)
        } else {
            unregisterReceiver(volumeReceiver)
        }
        volumeReceiverRegistered = on
    }

    private fun scheduleReattach() {
        handler.removeCallbacks(reattach)
        handler.postDelayed(reattach, 700)
    }

    private fun updateStatus() {
        val status = when {
            Prefs.perApp(this) -> getString(R.string.status_per_app, effects.size)
            GLOBAL_SESSION in effects -> getString(R.string.status_global_ok)
            else -> Prefs.status(this).ifEmpty { getString(R.string.status_error, "?") }
        }
        Prefs.setStatus(this, status)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, VolumeService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val title = getString(R.string.notification_title, VolumeLogic.formatDb(Prefs.attenuation(this)))
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume)
            .setContentTitle(title)
            .setContentText(Prefs.status(this))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.quieter), serviceIntent(ACTION_QUIETER, 1)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.louder), serviceIntent(ACTION_LOUDER, 2)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), serviceIntent(ACTION_STOP, 3)).build())
            .build()
    }
}
