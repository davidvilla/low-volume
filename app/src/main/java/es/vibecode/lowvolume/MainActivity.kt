package es.vibecode.lowvolume

import android.Manifest
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    // The slider works in half-dB steps: progress 0..100 -> 0..50 dB of attenuation.
    private val stepsPerDb = 2

    private lateinit var audioManager: AudioManager
    private lateinit var enabledSwitch: Switch
    private lateinit var perAppSwitch: Switch
    private lateinit var volumeKeysSwitch: Switch
    private lateinit var slider: SeekBar
    private lateinit var dbText: TextView
    private lateinit var percentText: TextView
    private lateinit var statusText: TextView
    private lateinit var systemVolumeText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audioManager = getSystemService(AudioManager::class.java)
        // The volume keys adjust media volume (and so our attenuation) while this screen is open.
        volumeControlStream = AudioManager.STREAM_MUSIC

        enabledSwitch = findViewById(R.id.enabled)
        perAppSwitch = findViewById(R.id.per_app)
        volumeKeysSwitch = findViewById(R.id.volume_keys)
        slider = findViewById(R.id.slider)
        dbText = findViewById(R.id.db)
        percentText = findViewById(R.id.percent)
        statusText = findViewById(R.id.status)
        systemVolumeText = findViewById(R.id.system_volume)

        slider.max = (Prefs.MAX_ATTENUATION_DB * stepsPerDb).toInt()
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                Prefs.setAttenuation(this@MainActivity, progress.toFloat() / stepsPerDb)
                if (Prefs.enabled(this@MainActivity)) VolumeService.start(this@MainActivity)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == Prefs.enabled(this)) return@setOnCheckedChangeListener
            if (checked) {
                VolumeService.start(this)
            } else {
                Prefs.setEnabled(this, false)
                VolumeService.stop(this)
            }
        }
        perAppSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == Prefs.perApp(this)) return@setOnCheckedChangeListener
            Prefs.setPerApp(this, checked)
            if (Prefs.enabled(this)) VolumeService.start(this)
        }

        volumeKeysSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == Prefs.volumeKeys(this)) return@setOnCheckedChangeListener
            Prefs.setVolumeKeys(this, checked)
            if (Prefs.enabled(this)) VolumeService.start(this)
        }

        findViewById<Button>(R.id.fine_down).setOnClickListener { nudge(+0.5f) }
        findViewById<Button>(R.id.fine_up).setOnClickListener { nudge(-0.5f) }
        findViewById<Button>(R.id.sys_down).setOnClickListener { adjustSystem(AudioManager.ADJUST_LOWER) }
        findViewById<Button>(R.id.sys_up).setOnClickListener { adjustSystem(AudioManager.ADJUST_RAISE) }

        // The service may have been killed (e.g. by an app update) while still enabled.
        if (Prefs.enabled(this)) VolumeService.start(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this)
        refresh()
    }

    override fun onPause() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) = refresh()

    private fun nudge(deltaDb: Float) {
        Prefs.setAttenuation(this, Prefs.attenuation(this) + deltaDb)
        if (Prefs.enabled(this)) VolumeService.start(this)
    }

    private fun adjustSystem(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        refresh()
    }

    private fun refresh() {
        val att = Prefs.attenuation(this)
        enabledSwitch.isChecked = Prefs.enabled(this)
        perAppSwitch.isChecked = Prefs.perApp(this)
        volumeKeysSwitch.isChecked = Prefs.volumeKeys(this)
        slider.progress = (att * stepsPerDb).toInt()
        dbText.text = VolumeLogic.formatDb(att)
        // Perceived loudness roughly halves every 10 dB.
        val amplitude = VolumeLogic.amplitudePercent(att)
        percentText.text = getString(
            R.string.amplitude, String.format(Locale.getDefault(), "%.1f", amplitude)
        )
        statusText.text = if (Prefs.enabled(this)) Prefs.status(this) else getString(R.string.status_off)
        systemVolumeText.text = getString(
            R.string.system_volume,
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        )
    }
}
