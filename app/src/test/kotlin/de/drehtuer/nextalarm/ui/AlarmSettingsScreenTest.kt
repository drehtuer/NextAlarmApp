package de.drehtuer.nextalarm.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.drehtuer.nextalarm.domain.AlarmVolume
import de.drehtuer.nextalarm.domain.NewsSource
import de.drehtuer.nextalarm.domain.NewsType
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SoundRef
import de.drehtuer.nextalarm.domain.SoundRejection
import de.drehtuer.nextalarm.domain.buildAlarmSettings
import de.drehtuer.nextalarm.domain.soundRejectionText
import de.drehtuer.nextalarm.ui.screens.AlarmSettingsScreen
import de.drehtuer.nextalarm.ui.theme.NextAlarmTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keeps what the screens ask for. */
class RecordingActions : AppActions {
    val calls = mutableListOf<String>()

    /** Kept apart, as these come from moving between the screens, not from what the user asks for. */
    val newsLifecycle = mutableListOf<String>()

    override fun importSound(uri: Uri) {
        calls += "import:$uri"
    }

    override fun useDefaultSound() {
        calls += "default"
    }

    override fun setVolumeRamp(seconds: Int) {
        calls += "ramp:$seconds"
    }

    override fun setMaxVolume(volume: Float?) {
        calls += "max:$volume"
    }

    override fun setVibrate(vibrate: Boolean) {
        calls += "vibrate:$vibrate"
    }

    override fun setRingFor(minutes: Int) {
        calls += "ring:$minutes"
    }

    override fun setSnoozeLength(minutes: Int) {
        calls += "snooze:$minutes"
    }

    override fun setSpeakTitle(speak: Boolean) {
        calls += "speak:$speak"
    }

    override fun openNews() {
        newsLifecycle += "open"
    }

    override fun closeNews() {
        newsLifecycle += "close"
    }

    override fun stopNewsTest() {
        newsLifecycle += "stopTest"
    }

    override fun setNewsAddress(address: String) {
        calls += "address:$address"
    }

    override fun shortenNews() {
        calls += "shorten"
    }

    override fun lengthenNews() {
        calls += "lengthen"
    }

    override fun toggleNewsTest() {
        calls += "toggleTest"
    }

    override fun turnOffNews() {
        calls += "turnOff"
    }

    override fun ringTestAlarm() {
        calls += "test"
    }

    override fun exportLog(uri: Uri) {
        calls += "export:$uri"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class AlarmSettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = RecordingActions()
    private val other = mutableListOf<String>()

    private fun show(
        settings: Settings = Settings.DEFAULT,
        volume: AlarmVolume? = AlarmVolume(5, 7),
        rejection: String? = null,
    ) {
        rule.setContent {
            NextAlarmTheme(dark = false) {
                AlarmSettingsScreen(
                    model = buildAlarmSettings(settings, "Bright Morning", volume, rejection),
                    actions = actions,
                    onBack = { other += "back" },
                    onChooseFile = { other += "file" },
                    onNews = { other += "news" },
                )
            }
        }
    }

    private fun tap(text: String) = rule.onNodeWithText(text).performScrollTo().performClick()

    private fun visible(text: String) = rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()

    @Test
    fun `all three sections are there, with the design's wording and the defaults`() {
        show()
        listOf(
            "These apply to every alarm.",
            "Sound",
            "Alarm sound",
            "Phone default (Bright Morning)",
            "Choose file",
            "Volume ramp",
            "Rises from silent",
            "None",
            "Maximum volume",
            "Sets the phone's alarm volume while ringing. Not set: leaves it as it is",
            "Not set",
            "Vibration",
            "Ringing",
            "Ring for",
            "Then it snoozes on its own",
            "3 min",
            "Snooze length",
            "10 min",
            "After you dismiss",
            "Speak the title",
            "Reads the event title aloud",
            "News",
            "Off",
        ).forEach(::visible)
    }

    @Test
    fun `the top bar goes back`() {
        show()
        rule.onNodeWithText("Alarm settings").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("back"), other)
    }

    @Test
    fun `a low volume shows the banner, which suggests the maximum volume`() {
        show(volume = AlarmVolume(1, 7))
        visible("Your phone's alarm volume is low (1 of 7).")
        visible("Alarms may be too quiet to wake you. Turn it up in the phone's sound settings, or set a maximum volume below.")
    }

    @Test
    fun `a normal volume shows no banner`() {
        show()
        rule.onNodeWithText("Alarms may be too quiet to wake you. Turn it up in the phone's sound settings, or set a maximum volume below.").assertDoesNotExist()
    }

    @Test
    fun `the phone's default has no button to go back to it`() {
        show()
        rule.onNodeWithText("Use the phone's default sound").assertDoesNotExist()
    }

    @Test
    fun `a set file shows its name and can be given up`() {
        show(Settings.DEFAULT.copy(sound = SoundRef.File.forCopy("0".repeat(36), "morning.mp3")))
        visible("morning.mp3")
        tap("Use the phone's default sound")
        assertEquals(listOf("default"), actions.calls)
    }

    @Test
    fun `Choose file opens the picker`() {
        show()
        tap("Choose file")
        assertEquals(listOf("file"), other)
    }

    @Test
    fun `a rejected file says why`() {
        val text = soundRejectionText(SoundRejection.UNSUPPORTED_TYPE, "Notes.pdf")
        show(rejection = text)
        visible(text)
    }

    @Test
    fun `the volume ramp opens a picker with the current value marked`() {
        show(Settings.DEFAULT.copy(volumeRampSeconds = 30))
        tap("Volume ramp")
        rule.onNode(hasText("30 s") and isSelectable()).assertIsSelected()
        rule.onNodeWithText("1 min").performClick()
        assertEquals(listOf("ramp:60"), actions.calls)
        rule.onNodeWithText("1 min").assertDoesNotExist()
    }

    @Test
    fun `a picker can be left without a change`() {
        show()
        tap("Volume ramp")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Cancel").assertDoesNotExist()
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `the maximum volume can be set and unset`() {
        show(Settings.DEFAULT.copy(maxVolume = 0.5f))
        visible("50%")
        tap("Maximum volume")
        rule.onNodeWithText("30%").performClick()
        tap("Maximum volume")
        rule.onNode(hasText("Not set") and isSelectable()).performClick()
        assertEquals(listOf("max:0.3", "max:null"), actions.calls)
    }

    @Test
    fun `ring for and snooze length have their own values`() {
        show()
        tap("Ring for")
        rule.onNodeWithText("5 min").performClick()
        tap("Snooze length")
        rule.onNodeWithText("20 min").performClick()
        assertEquals(listOf("ring:5", "snooze:20"), actions.calls)
    }

    @Test
    fun `the switches show their state and report a change`() {
        show(Settings.DEFAULT.copy(vibrate = true))
        rule.onNodeWithText("Vibration").performScrollTo().assertIsOn().performClick()
        rule.onNodeWithText("Speak the title").performScrollTo().assertIsOff().performClick()
        assertEquals(listOf("vibrate:false", "speak:true"), actions.calls)
    }

    @Test
    fun `the news row shows its value and has its own callback`() {
        show(Settings.DEFAULT.copy(news = NewsSource(NewsType.RSS, "https://www.tagesschau.de/feed.rss")))
        tap("RSS · tagesschau.de")
        assertEquals(listOf("news"), other)
    }

    @Test
    fun `everything fits a scrolling screen`() {
        show(volume = AlarmVolume(1, 7), rejection = "Rejected.")
        visible("News")
        visible("Rejected.")
    }
}
