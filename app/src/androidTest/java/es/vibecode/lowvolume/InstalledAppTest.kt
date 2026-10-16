package es.vibecode.lowvolume

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Process
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs inside the installed app on a real device. Each test saves the user's
 * settings and system volume and restores them afterwards.
 */
@RunWith(AndroidJUnit4::class)
class InstalledAppTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val stream = AudioManager.STREAM_MUSIC

    private lateinit var savedPrefs: Map<String, *>
    private var savedVolume = 0
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun saveState() {
        savedPrefs = Prefs.get(context).all
        savedVolume = audio.getStreamVolume(stream)
        shell("input keyevent ${KeyEvent.KEYCODE_WAKEUP}")
        Prefs.setPerApp(context, false)
        Prefs.setVolumeKeys(context, true)
    }

    @After
    fun restoreState() {
        scenario?.close()
        VolumeService.stop(context)
        waitUntil("service stopped") { Prefs.status(context).isEmpty() }
        audio.setStreamVolume(stream, savedVolume, 0)
        val editor = Prefs.get(context).edit().clear()
        savedPrefs.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.commit()
        if (Prefs.enabled(context)) VolumeService.start(context)
    }

    @Test
    fun globalEffectIsAttachedToOutputMix() {
        startService(attenuation = 10f)
        assertEquals(context.getString(R.string.status_global_ok), Prefs.status(context))
        // AudioFlinger lists our process as a client of a Dynamics Processing effect.
        val dump = shell("dumpsys media.audio_flinger")
        assertTrue("Dynamics Processing effect not found", "Dynamics Processing" in dump)
        assertTrue(
            "This app is not a client of the effect",
            Regex("""\b${Process.myPid()}\s+${Int.MAX_VALUE}\s+yes""").containsMatchIn(dump)
        )
    }

    @Test
    fun notificationActionsChangeAttenuation() {
        startService(attenuation = 10f)
        sendToService(VolumeService.ACTION_QUIETER)
        waitForAttenuation(10f + Prefs.STEP_DB)
        sendToService(VolumeService.ACTION_LOUDER)
        sendToService(VolumeService.ACTION_LOUDER)
        waitForAttenuation(10f - Prefs.STEP_DB)
    }

    @Test
    fun stopActionDisablesTheService() {
        startService(attenuation = 10f)
        sendToService(VolumeService.ACTION_STOP)
        waitUntil("service stopped") { !Prefs.enabled(context) && Prefs.status(context).isEmpty() }
    }

    @Test
    fun volumeDownAtFloorAddsAttenuation() {
        startService(attenuation = 10f, volume = VolumeLogic.FLOOR_INDEX)
        pressKey(KeyEvent.KEYCODE_VOLUME_DOWN)
        waitForAttenuation(10f + Prefs.STEP_DB)
        waitForVolume(VolumeLogic.FLOOR_INDEX)
    }

    @Test
    fun volumeUpAtFloorRemovesAttenuation() {
        startService(attenuation = 10f, volume = VolumeLogic.FLOOR_INDEX)
        pressKey(KeyEvent.KEYCODE_VOLUME_UP)
        waitForAttenuation(10f - Prefs.STEP_DB)
        waitForVolume(VolumeLogic.FLOOR_INDEX)
    }

    @Test
    fun volumeUpWithoutAttenuationRaisesSystemVolume() {
        startService(attenuation = 0f, volume = VolumeLogic.FLOOR_INDEX)
        pressKey(KeyEvent.KEYCODE_VOLUME_UP)
        waitForVolume(VolumeLogic.FLOOR_INDEX + 1)
        assertEquals(0f, Prefs.attenuation(context))
    }

    @Test
    fun volumeDownAboveFloorOnlyLowersSystemVolume() {
        startService(attenuation = 0f, volume = VolumeLogic.FLOOR_INDEX + 2)
        pressKey(KeyEvent.KEYCODE_VOLUME_DOWN)
        waitForVolume(VolumeLogic.FLOOR_INDEX + 1)
        assertEquals(0f, Prefs.attenuation(context))
    }

    @Test
    fun screenShowsAndChangesAttenuation() {
        startService(attenuation = 10f)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertEquals("-10.0 dB", it.findViewById<TextView>(R.id.db).text.toString().replace(',', '.'))
                it.findViewById<Button>(R.id.fine_down).performClick()
            }
            waitForAttenuation(10.5f)
            scenario.onActivity {
                assertEquals("-10.5 dB", it.findViewById<TextView>(R.id.db).text.toString().replace(',', '.'))
            }
        }
    }

    private fun startService(attenuation: Float, volume: Int? = null) {
        // Set the volume before the service listens to it, so it is not taken as a key press.
        volume?.let { audio.setStreamVolume(stream, it, 0) }
        Prefs.setAttenuation(context, attenuation)
        VolumeService.start(context)
        waitUntil("service running") { Prefs.enabled(context) && Prefs.status(context).isNotEmpty() }
    }

    private fun sendToService(action: String) {
        context.startForegroundService(Intent(context, VolumeService::class.java).setAction(action))
        Thread.sleep(300)
    }

    /** Presses a hardware volume key with the app's screen open, so it targets media volume. */
    private fun pressKey(keyCode: Int) {
        if (scenario == null) {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            instrumentation.waitForIdleSync()
        }
        // Some devices use the first key press only to show the volume panel.
        audio.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        Thread.sleep(500)
        shell("input keyevent $keyCode")
    }

    private fun waitForAttenuation(expected: Float) =
        waitUntil("attenuation $expected, got ${Prefs.attenuation(context)}") {
            Prefs.attenuation(context) == expected
        }

    private fun waitForVolume(expected: Int) =
        waitUntil("volume $expected, got ${audio.getStreamVolume(stream)}") {
            audio.getStreamVolume(stream) == expected
        }

    private fun waitUntil(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for $what")
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText()
        }
}
