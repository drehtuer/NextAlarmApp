package de.drehtuer.nextalarm.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmSettingsTest {
    private fun model(
        settings: Settings = Settings.DEFAULT,
        defaultName: String? = "Bright Morning",
        volume: AlarmVolume? = AlarmVolume(5, 7),
        rejection: String? = null,
    ) = buildAlarmSettings(settings, defaultName, volume, rejection)

    @Test
    fun `the alarm volume is low at or below 2 steps`() {
        assertTrue(isAlarmVolumeLow(AlarmVolume(0, 7)))
        assertTrue(isAlarmVolumeLow(AlarmVolume(1, 7)))
        assertTrue(isAlarmVolumeLow(AlarmVolume(2, 7)))
        assertFalse(isAlarmVolumeLow(AlarmVolume(3, 7)))
    }

    @Test
    fun `the banner gives the steps, and only offers what can make the alarm louder`() {
        val banner = assertNotNull(lowVolumeBanner(AlarmVolume(1, 7)))
        assertEquals("Your phone's alarm volume is low (1 of 7).", banner.title)
        assertEquals("Alarms may be too quiet to wake you. Turn it up in the phone's sound settings, or set a maximum volume below.", banner.body)
    }

    @Test
    fun `there is no banner at a normal volume, or if the volume is not known`() {
        assertNull(lowVolumeBanner(AlarmVolume(3, 7)))
        assertNull(model(volume = AlarmVolume(3, 7)).lowVolume)
        assertNull(model(volume = null).lowVolume)
    }

    @Test
    fun `the model has the banner at a low volume`() {
        assertEquals(lowVolumeBanner(AlarmVolume(2, 7)), model(volume = AlarmVolume(2, 7)).lowVolume)
    }

    @Test
    fun `each rejection names the file and says what to do`() {
        assertEquals(
            "“Notes.pdf” isn't a sound file. Choose an MP3, WAV, OGG or M4A file.",
            soundRejectionText(SoundRejection.UNSUPPORTED_TYPE, "Notes.pdf"),
        )
        assertEquals(
            "“morning.mp3” can't be opened. It may have been moved or deleted. Choose it again.",
            soundRejectionText(SoundRejection.UNREADABLE, "morning.mp3"),
        )
        assertEquals(
            "“silence.m4a” has no sound NextAlarm can play. Choose another file.",
            soundRejectionText(SoundRejection.NOT_PLAYABLE, "silence.m4a"),
        )
    }

    @Test
    fun `the sound row names the phone's default, or the file`() {
        assertEquals("Phone default (Bright Morning)", soundName(SoundRef.SystemDefault, "Bright Morning"))
        assertEquals("Phone default", soundName(SoundRef.SystemDefault, null))
        assertEquals("morning.mp3", soundName(SoundRef.File.forCopy("0".repeat(36), "morning.mp3"), "Bright Morning"))
    }

    @Test
    fun `a phone sound is named by its title like a file`() {
        assertEquals("Oxygen", soundName(SoundRef.File.forCopy("0".repeat(36), "Oxygen"), "Bright Morning"))
    }

    @Test
    fun `the phone's sound picker is called by its wording`() {
        assertEquals("Phone sounds", PHONE_SOUNDS_BUTTON)
        assertEquals("Alarm sound", PHONE_SOUNDS_TITLE)
    }

    @Test
    fun `the phone's sound picker gives a sound, its default, or nothing`() {
        val default = "content://settings/system/alarm_alert"
        val oxygen = "content://media/internal/audio/media/37"
        assertEquals(PhoneSoundPick.Cancelled, phoneSoundPick(null, default))
        assertEquals(PhoneSoundPick.PhoneDefault, phoneSoundPick(default, default))
        assertEquals(PhoneSoundPick.Sound(oxygen), phoneSoundPick(oxygen, default))
        assertEquals(oxygen, (phoneSoundPick(oxygen, default) as PhoneSoundPick.Sound).address)
    }

    @Test
    fun `only a set file can be given up`() {
        assertFalse(model().usesFile)
        assertTrue(model(Settings.DEFAULT.copy(sound = SoundRef.File("a"))).usesFile)
    }

    @Test
    fun `a rejection is passed on`() {
        assertNull(model().soundRejection)
        assertEquals("No.", model(rejection = "No.").soundRejection)
    }

    @Test
    fun `the volume ramp is None or a duration`() {
        assertEquals("None", model().volumeRamp.label)
        assertEquals("30 s", model(Settings.DEFAULT.copy(volumeRampSeconds = 30)).volumeRamp.label)
        assertEquals("2 min", model(Settings.DEFAULT.copy(volumeRampSeconds = 120)).volumeRamp.label)
        assertEquals("1 min 30 s", model(Settings.DEFAULT.copy(volumeRampSeconds = 90)).volumeRamp.label)
    }

    @Test
    fun `the ramp options start with None and carry their values`() {
        val options = model().volumeRamp.options
        assertEquals(listOf("None", "15 s", "30 s", "1 min", "2 min", "5 min"), options.map { it.label })
        assertEquals(listOf(0, 15, 30, 60, 120, 300), options.map { it.value })
    }

    @Test
    fun `the maximum volume is Not set or a percentage`() {
        assertEquals("Not set", model().maxVolume.label)
        assertEquals("70%", model(Settings.DEFAULT.copy(maxVolume = 0.7f)).maxVolume.label)
    }

    @Test
    fun `no maximum volume option can silence an alarm`() {
        val options = model().maxVolume.options
        assertEquals(null, options.first().value)
        assertEquals("Not set", options.first().label)
        assertEquals("100%", options[1].label)
        assertEquals(1f, options[1].value)
        assertEquals("90%", options[2].label)
        assertEquals("10%", options.last().label)
        assertTrue(options.mapNotNull { it.value }.all { it > 0f && it <= 1f })
    }

    @Test
    fun `an option is told apart from the rest by its label`() {
        val set = model(Settings.DEFAULT.copy(maxVolume = 0.3f)).maxVolume
        assertEquals(1, set.options.count { it.label == set.label })
    }

    @Test
    fun `ringing and snoozing show their minutes, with the defaults among the options`() {
        val model = model()
        assertEquals("3 min", model.ringFor.label)
        assertEquals("10 min", model.snoozeLength.label)
        assertEquals(listOf(1, 2, 3, 5, 10, 15), model.ringFor.options.map { it.value })
        assertEquals(listOf(1, 5, 10, 15, 20, 30), model.snoozeLength.options.map { it.value })
    }

    @Test
    fun `the switches follow the settings`() {
        assertFalse(model().vibrate)
        assertFalse(model().speakTitle)
        val on = model(Settings.DEFAULT.copy(vibrate = true, speakReason = true))
        assertTrue(on.vibrate)
        assertTrue(on.speakTitle)
    }

    @Test
    fun `news is off, or the kind of feed and where it comes from`() {
        assertEquals("Off", newsLabel(null))
        assertEquals("RSS · tagesschau.de", newsLabel(NewsSource(NewsType.RSS, "https://www.tagesschau.de/feed.rss")))
        assertEquals("Podcast · example.org", newsLabel(NewsSource(NewsType.PODCAST, "https://example.org/feed.xml")))
        assertEquals("Off", model().news)
    }

    @Test
    fun `an address without a host shows as it is`() {
        assertEquals("RSS · not a url", newsLabel(NewsSource(NewsType.RSS, "not a url")))
        assertEquals("RSS · feed.xml", newsLabel(NewsSource(NewsType.RSS, "feed.xml")))
    }
}
