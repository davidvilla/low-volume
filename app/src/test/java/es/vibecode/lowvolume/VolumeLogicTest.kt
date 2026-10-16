package es.vibecode.lowvolume

import es.vibecode.lowvolume.VolumeLogic.FLOOR_INDEX
import es.vibecode.lowvolume.VolumeLogic.attenuationAfterVolumeChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class VolumeLogicTest {

    @Test
    fun downFromFloorAddsAttenuation() {
        assertEquals(12.5f, attenuationAfterVolumeChange(FLOOR_INDEX, 0, 10f))
    }

    @Test
    fun downFromFloorWithoutAttenuationStartsAttenuating() {
        assertEquals(Prefs.STEP_DB, attenuationAfterVolumeChange(FLOOR_INDEX, 0, 0f))
    }

    @Test
    fun downFromFloorAtMaximumIsLeftAloneSoItMutes() {
        assertNull(attenuationAfterVolumeChange(FLOOR_INDEX, 0, Prefs.MAX_ATTENUATION_DB))
    }

    @Test
    fun downNearMaximumIsClamped() {
        assertEquals(
            Prefs.MAX_ATTENUATION_DB,
            attenuationAfterVolumeChange(FLOOR_INDEX, 0, Prefs.MAX_ATTENUATION_DB - 1f)
        )
    }

    @Test
    fun upFromFloorRemovesAttenuation() {
        assertEquals(7.5f, attenuationAfterVolumeChange(FLOOR_INDEX, 2, 10f))
    }

    @Test
    fun upFromFloorNearZeroIsClamped() {
        assertEquals(0f, attenuationAfterVolumeChange(FLOOR_INDEX, 2, 1f))
    }

    @Test
    fun upFromFloorWithoutAttenuationRaisesSystemVolume() {
        assertNull(attenuationAfterVolumeChange(FLOOR_INDEX, 2, 0f))
    }

    @Test
    fun changesNotStartingAtFloorAreIgnored() {
        assertNull(attenuationAfterVolumeChange(3, 2, 10f))
        assertNull(attenuationAfterVolumeChange(2, 1, 10f))
        assertNull(attenuationAfterVolumeChange(0, 1, 10f))
        assertNull(attenuationAfterVolumeChange(2, 3, 10f))
    }

    @Test
    fun unchangedVolumeIsIgnored() {
        assertNull(attenuationAfterVolumeChange(FLOOR_INDEX, FLOOR_INDEX, 10f))
    }

    @Test
    fun clampKeepsAttenuationInRange() {
        assertEquals(0f, VolumeLogic.clampAttenuation(-3f))
        assertEquals(Prefs.MAX_ATTENUATION_DB, VolumeLogic.clampAttenuation(80f))
        assertEquals(20f, VolumeLogic.clampAttenuation(20f))
    }

    @Test
    fun amplitudeFollowsDecibels() {
        assertEquals(100.0, VolumeLogic.amplitudePercent(0f), 1e-9)
        assertEquals(10.0, VolumeLogic.amplitudePercent(20f), 1e-9)
        assertEquals(1.0, VolumeLogic.amplitudePercent(40f), 1e-9)
    }

    @Test
    fun formatsDecibels() {
        assertEquals("0 dB", VolumeLogic.formatDb(0f, Locale.US))
        assertEquals("-12.5 dB", VolumeLogic.formatDb(12.5f, Locale.US))
        assertEquals("-12,5 dB", VolumeLogic.formatDb(12.5f, Locale.forLanguageTag("es-ES")))
    }
}
