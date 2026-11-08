package de.drehtuer.nextalarm.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.drehtuer.nextalarm.domain.SoundPreviewStatus
import de.drehtuer.nextalarm.domain.withPreview
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.data.SUPPORTED_SOUND_MIME_TYPES
import de.drehtuer.nextalarm.domain.ALARM_CALENDARS_LEAD
import de.drehtuer.nextalarm.domain.CHOOSE_CALENDARS_LEAD
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.DeviceCalendar
import de.drehtuer.nextalarm.domain.MISSING_CALENDARS_HEADER
import de.drehtuer.nextalarm.domain.MISSING_CALENDAR_ROW
import de.drehtuer.nextalarm.domain.HomeDay
import de.drehtuer.nextalarm.domain.HomeHero
import de.drehtuer.nextalarm.domain.BannerText
import de.drehtuer.nextalarm.domain.HomeModel
import de.drehtuer.nextalarm.domain.HomeRow
import de.drehtuer.nextalarm.domain.PhoneSoundPick
import de.drehtuer.nextalarm.domain.SoundRef
import de.drehtuer.nextalarm.domain.buildAlarmSettings
import de.drehtuer.nextalarm.domain.Settings as DomainSettings
import de.drehtuer.nextalarm.ui.screens.CalendarScreen
import de.drehtuer.nextalarm.ui.screens.HomeScreen
import de.drehtuer.nextalarm.ui.screens.SYNC_NOW
import de.drehtuer.nextalarm.ui.screens.OnboardingScreen
import de.drehtuer.nextalarm.ui.screens.PermissionCard
import de.drehtuer.nextalarm.ui.screens.SettingsScreen
import de.drehtuer.nextalarm.ui.screens.SettingsTargets
import de.drehtuer.nextalarm.ui.theme.DarkColors
import de.drehtuer.nextalarm.ui.theme.DarkExtraColors
import de.drehtuer.nextalarm.ui.theme.LightColors
import de.drehtuer.nextalarm.ui.theme.LightExtraColors
import de.drehtuer.nextalarm.ui.theme.LocalExtraColors
import de.drehtuer.nextalarm.ui.theme.NextAlarmTheme
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity

private val ALARMS = DeviceCalendar(CalendarId(1), "NextAlarm", "me@cloud.example", "bitfire.at.davdroid", 0xFF00639B.toInt())
private val PLAIN = DeviceCalendar(CalendarId(2), "Phone", "local", "LOCAL", null)

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class ScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val empty = HomeModel(null, emptyList(), "Nothing is planned until Sunday 20:00.", null, null)

    @Test
    fun `home shows the warning while notifications are off`() {
        home(empty.copy(notificationsOff = BannerText("Notifications are off", "Open NextAlarm to stop it.")))
        rule.onNodeWithText("Notifications are off").assertIsDisplayed()
        rule.onNodeWithText("Open NextAlarm to stop it.").assertIsDisplayed()
    }

    private fun home(model: HomeModel, onDiagnostics: () -> Unit = {}, onSettings: () -> Unit = {}) {
        rule.setContent { NextAlarmTheme(dark = false) { HomeScreen(model, onSettings, onDiagnostics) } }
    }

    @Test
    fun `home shows the next alarm and the days`() {
        home(
            HomeModel(
                hero = HomeHero("Next alarm", "06:30", "Tomorrow, Saturday · in 10 h", "Flight to Hamburg"),
                days = listOf(
                    HomeDay(
                        "Tomorrow · Saturday 7 November",
                        listOf(HomeRow("06:30", "Dentist", null), HomeRow("07:15", null, "Snoozed until 07:15")),
                    ),
                ),
                emptyBody = null,
                failSafeBody = null,
                footer = "From the calendar “NextAlarm” (DAVx⁵).",
            ),
        )
        listOf(
            "Next alarm",
            "Tomorrow, Saturday · in 10 h",
            "Flight to Hamburg",
            "Tomorrow · Saturday 7 November",
            "Dentist",
            "No title",
            "Snoozed until 07:15",
            "From the calendar “NextAlarm” (DAVx⁵).",
        ).forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun `home's sync button asks for a sync`() {
        var asked = 0
        rule.setContent { NextAlarmTheme(dark = false) { HomeScreen(empty, {}, {}, onSyncNow = { asked++ }) } }
        rule.onNodeWithContentDescription(SYNC_NOW).assertIsEnabled().performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `home's sync button is off while a sync is on its way`() {
        var asked = 0
        rule.setContent {
            NextAlarmTheme(dark = false) { HomeScreen(empty, {}, {}, syncPending = true, onSyncNow = { asked++ }) }
        }
        rule.onNodeWithContentDescription(SYNC_NOW).assertIsNotEnabled().performClick()
        assertEquals(0, asked)
    }

    @Test
    fun `home without a title in the hero says so`() {
        home(HomeModel(HomeHero("Next alarm", "06:30", "Today · in 1 h", null), emptyList(), null, null, null))
        rule.onNodeWithText("No title").assertIsDisplayed()
    }

    @Test
    fun `home shows the empty state and opens the settings`() {
        var opened = false
        home(empty) { opened = true }
        rule.onNodeWithText("No alarms in the next 48 hours").assertIsDisplayed()
        rule.onNodeWithText("Nothing is planned until Sunday 20:00.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").performClick()
        assertTrue(opened)
    }

    @Test
    fun `home warns in fail-safe`() {
        var opened = false
        home(empty.copy(failSafeBody = "The alarms below will still ring."), onDiagnostics = { opened = true })
        rule.onNodeWithText("New alarms aren't being added").assertIsDisplayed()
        rule.onNodeWithText("The alarms below will still ring.").assertIsDisplayed()
        rule.onNodeWithText("See what went wrong").performClick()
        assertTrue(opened)
    }

    @Test
    fun `home has no way to diagnostics of its own when nothing is wrong`() {
        home(empty)
        rule.onNodeWithText("See what went wrong").assertDoesNotExist()
    }

    @Test
    fun `the calendar screen groups calendars and reports the ticks`() {
        val toggled = mutableListOf<Pair<CalendarId, Boolean>>()
        var back = false
        rule.setContent {
            NextAlarmTheme {
                CalendarScreen(listOf(ALARMS, PLAIN), setOf(ALARMS.id), { id, ticked -> toggled += id to ticked }, { back = true })
            }
        }
        rule.onNodeWithText("Alarm calendars").assertIsDisplayed()
        rule.onNodeWithText("DAVx⁵ · me@cloud.example").assertIsDisplayed()
        rule.onNodeWithText("On this phone").assertIsDisplayed()
        rule.onNodeWithText("Phone").performClick()
        rule.onNodeWithText("NextAlarm").performClick()
        assertEquals(listOf(PLAIN.id to true, ALARMS.id to false), toggled)
        rule.onNodeWithContentDescription("Back").performClick()
        assertTrue(back)
    }

    private val checkboxRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    @Test
    fun `the calendars are checkboxes, not radio buttons, and any number can be ticked`() {
        val third = PLAIN.copy(id = CalendarId(3), name = "Third")
        rule.setContent {
            NextAlarmTheme { CalendarScreen(listOf(ALARMS, PLAIN, third), setOf(ALARMS.id, PLAIN.id), { _, _ -> }, {}) }
        }
        rule.onNodeWithText("NextAlarm").assert(checkboxRole).assertIsToggleable().assertIsOn()
        rule.onNodeWithText("Phone").assert(checkboxRole).assertIsToggleable().assertIsOn()
        rule.onNodeWithText("Third").assert(checkboxRole).assertIsToggleable().assertIsOff()
        rule.onAllNodes(isSelectable()).assertCountEquals(0)
        rule.onNodeWithText("No calendar chosen.").assertDoesNotExist()
    }

    @Test
    fun `the lead warns that every event in a ticked calendar rings, and that a hidden calendar still does`() {
        rule.setContent { NextAlarmTheme { CalendarScreen(listOf(PLAIN), emptySet(), { _, _ -> }, {}) } }
        rule.onNodeWithText(ALARM_CALENDARS_LEAD).assertIsDisplayed()
    }

    @Test
    fun `a chosen calendar that is gone can still be unticked`() {
        val toggled = mutableListOf<Pair<CalendarId, Boolean>>()
        var selected by mutableStateOf(setOf(ALARMS.id, CalendarId(9)))
        rule.setContent {
            NextAlarmTheme {
                CalendarScreen(listOf(ALARMS), selected, { id, ticked -> toggled += id to ticked }, {})
            }
        }
        rule.onNodeWithText(MISSING_CALENDARS_HEADER).assertIsDisplayed()
        rule.onNodeWithText(MISSING_CALENDAR_ROW).assert(checkboxRole).assertIsOn().performClick()
        selected = setOf(ALARMS.id, CalendarId(8))
        rule.onNodeWithText(MISSING_CALENDAR_ROW).performClick()
        assertEquals(listOf(CalendarId(9) to false, CalendarId(8) to false), toggled)
    }

    @Test
    fun `without missing calendars there is no such group, nor without any list to compare with`() {
        var calendars by mutableStateOf(listOf(ALARMS))
        rule.setContent { NextAlarmTheme { CalendarScreen(calendars, setOf(ALARMS.id), { _, _ -> }, {}) } }
        rule.onNodeWithText(MISSING_CALENDARS_HEADER).assertDoesNotExist()
        calendars = emptyList()
        rule.onNodeWithText("No calendars on this phone").assertIsDisplayed()
        rule.onNodeWithText(MISSING_CALENDARS_HEADER).assertDoesNotExist()
    }

    @Test
    fun `the calendar screen warns when none is chosen`() {
        rule.setContent { NextAlarmTheme { CalendarScreen(listOf(PLAIN), emptySet(), { _, _ -> }, {}) } }
        rule.onNodeWithText("No calendar chosen.").assertIsDisplayed()
    }

    @Test
    fun `the calendar screen explains when the phone has none`() {
        rule.setContent { NextAlarmTheme { CalendarScreen(emptyList(), emptySet(), { _, _ -> }, {}) } }
        rule.onNodeWithText("No calendars on this phone").assertIsDisplayed()
    }

    @Test
    fun `settings show the calendar, the version and the rows`() {
        val calls = mutableListOf<String>()
        rule.setContent {
            NextAlarmTheme {
                SettingsScreen(
                    "NextAlarm · DAVx⁵",
                    "Last check 19:58 · all good",
                    "0.1.0 (build 1)",
                    SettingsTargets(
                        onBack = { calls += "back" },
                        onAlarmCalendar = { calls += "calendar" },
                        onAlarmSettings = { calls += "alarm settings" },
                        onDiagnostics = { calls += "diagnostics" },
                        onRunSetup = { calls += "setup" },
                        onSourceCode = { calls += "source" },
                    ),
                )
            }
        }
        rule.onNodeWithText("NextAlarm · DAVx⁵").performClick()
        rule.onNodeWithText("Alarm settings").performClick()
        rule.onNodeWithText("Sound, snooze, speech and news").assertIsDisplayed()
        rule.onNodeWithText("Diagnostics & history").performClick()
        rule.onNodeWithText("Last check 19:58 · all good").assertIsDisplayed()
        rule.onNodeWithText("Run setup again").performClick()
        rule.onNodeWithText("0.1.0 (build 1)").assertIsDisplayed()
        rule.onNodeWithText("github.com/drehtuer/NextAlarmApp").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("calendar", "alarm settings", "diagnostics", "setup", "source", "back"), calls)
    }

    private fun card(
        title: String,
        granted: Boolean,
        required: Boolean = true,
        note: String? = null,
        onAllow: () -> Unit = {},
        onSettings: () -> Unit = {},
    ) = PermissionCard(title, "Why", granted, note, required, onAllow, onSettings)

    private fun onboarding(
        step: Int,
        permissions: List<PermissionCard> = emptyList(),
        calendars: List<DeviceCalendar> = emptyList(),
        selected: Set<CalendarId> = emptySet(),
        onStep: (Int) -> Unit = {},
        onToggle: (CalendarId, Boolean) -> Unit = { _, _ -> },
        onDone: () -> Unit = {},
    ) {
        rule.setContent {
            NextAlarmTheme(dark = true) {
                OnboardingScreen(step, onStep, permissions, calendars, selected, onToggle, onDone)
            }
        }
    }

    @Test
    fun `the first step explains the app and goes on`() {
        var step = -1
        onboarding(0, onStep = { step = it })
        rule.onNodeWithText("Your calendar sets the alarms").assertIsDisplayed()
        rule.onNodeWithText("Every event is an alarm").assertIsDisplayed()
        rule.onNodeWithText("Regular alarms repeat").assertIsDisplayed()
        rule.onNodeWithText("Change or delete in the calendar").assertIsDisplayed()
        rule.onNodeWithText("Back").assertDoesNotExist()
        rule.onNodeWithText("Get started").performClick()
        assertEquals(1, step)
    }

    @Test
    fun `the permissions step waits for what alarms cannot work without`() {
        onboarding(1, listOf(card("Read your calendars", false), card("Show alarms full screen", false, required = false)))
        rule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `the permissions step can go on without full-screen alarms, and back`() {
        var step = -1
        onboarding(
            1,
            listOf(card("Read your calendars", true), card("Show alarms full screen", false, required = false)),
            onStep = { step = it },
        )
        rule.onNodeWithText("Allowed").assertIsDisplayed()
        rule.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(2, step)
        rule.onNodeWithText("Back").performClick()
        assertEquals(0, step)
    }

    @Test
    fun `a permission can be asked for, and a denied one explains itself`() {
        var allowed = false
        var settings = false
        onboarding(
            1,
            listOf(
                card("Read your calendars", false, note = "Calendar was denied.", onAllow = { allowed = true }, onSettings = { settings = true }),
            ),
        )
        rule.onNodeWithText("Calendar was denied.").assertIsDisplayed()
        rule.onNodeWithText("Allow").performClick()
        assertTrue(allowed)
        rule.onNodeWithText("Open app settings").performClick()
        assertTrue(settings)
    }

    @Test
    fun `a denied optional permission is only a warning, and a granted one shows no note`() {
        onboarding(
            1,
            listOf(
                card("Show alarms full screen", false, required = false, note = "Only a notification."),
                card("Read your calendars", true, note = "Hidden note."),
            ),
        )
        rule.onNodeWithText("Only a notification.").assertIsDisplayed()
        rule.onNodeWithText("Hidden note.").assertDoesNotExist()
    }

    @Test
    fun `the last step needs a calendar`() {
        onboarding(2, calendars = listOf(ALARMS))
        rule.onNodeWithText("Choose the alarm calendars").assertIsDisplayed()
        rule.onNodeWithText(CHOOSE_CALENDARS_LEAD).assertIsDisplayed()
        rule.onNodeWithText("No calendar chosen.").assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsNotEnabled()
    }

    @Test
    fun `the last step takes any number of calendars`() {
        onboarding(2, calendars = listOf(ALARMS, PLAIN), selected = setOf(ALARMS.id, PLAIN.id))
        rule.onNodeWithText("NextAlarm").assertIsOn()
        rule.onNodeWithText("Phone").assertIsOn()
        rule.onNodeWithText("Done").assertIsEnabled()
    }

    @Test
    fun `the last step finishes setup and reports the choice`() {
        var done = false
        var ticked: Pair<CalendarId, Boolean>? = null
        onboarding(2, calendars = listOf(ALARMS, PLAIN), selected = setOf(ALARMS.id), onToggle = { id, on -> ticked = id to on }, onDone = { done = true })
        rule.onNodeWithText("Phone").performClick()
        assertEquals(PLAIN.id to true, ticked)
        rule.onNodeWithText("Done").performClick()
        assertTrue(done)
    }

    @Test
    fun `the theme uses the fixed palette of each mode`() {
        var seen = emptyList<Any>()
        rule.setContent {
            NextAlarmTheme(dark = true) {
                seen = listOf(MaterialTheme.colorScheme.primary, LocalExtraColors.current)
            }
        }
        rule.waitForIdle()
        assertEquals(listOf<Any>(DarkColors.primary, DarkExtraColors), seen)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF00639B), LightColors.primary)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF1E6E3C), LightExtraColors.success)
    }
}

/** The navigation between the screens, and what they ask of Android. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class NextAlarmAppTest {
    @get:Rule
    val rule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val ready = AppState(
        loaded = true,
        setupDone = true,
        calendars = listOf(ALARMS),
        selectedCalendars = setOf(ALARMS.id),
        diagnostics = AppState().diagnostics.copy(calendar = "NextAlarm · DAVx⁵"),
    )
    private val completed = mutableListOf<String>()
    private val actions = RecordingActions()
    private val requests = mutableIntStateOf(0)

    private fun show(state: AppState) {
        rule.setContent {
            NextAlarmTheme(dark = false) {
                NextAlarmApp(state, 0, { id, ticked -> completed += "tick:${id.value}:$ticked" }, { completed += "complete" }, actions, requests.intValue)
            }
        }
    }

    private fun openFromSettings(row: String) {
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText(row).performClick()
    }

    private fun nextActivity(): Intent = shadowOf(application).nextStartedActivity

    private fun openNews() {
        openFromSettings("Alarm settings")
        rule.onNodeWithText("News").performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun `the news row opens screen 6, which starts from what is stored`() {
        show(ready)
        openNews()
        rule.onNodeWithText("Maximum duration").assertIsDisplayed()
        assertEquals("open", actions.newsLifecycle.last())
    }

    @Test
    fun `screen 6 reaches its actions`() {
        show(ready)
        openNews()
        rule.onNodeWithText("Turn off news").assertIsDisplayed()
        rule.onNodeWithContentDescription("Longer").performClick()
        assertEquals(listOf("lengthen"), actions.calls)
    }

    @Test
    fun `the back arrow leaves screen 6 for screen 5, and that closes it`() {
        show(ready)
        openNews()
        actions.newsLifecycle.clear()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("These apply to every alarm.").assertIsDisplayed()
        assertEquals(listOf("close"), actions.newsLifecycle)
    }

    @Test
    fun `leaving screen 5, back or forward, stops its sound`() {
        show(ready)
        openFromSettings("Alarm settings")
        rule.waitForIdle()
        actions.previewLifecycle.clear()
        rule.onNodeWithText("News").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("stopPreview"), actions.previewLifecycle)
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        actions.previewLifecycle.clear()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Alarm calendars").assertIsDisplayed()
        assertEquals(listOf("stopPreview"), actions.previewLifecycle)
    }

    @Test
    fun `screen 5 reaches the preview of its sound`() {
        show(ready)
        openFromSettings("Alarm settings")
        rule.onNodeWithContentDescription("Play the alarm sound").performClick()
        assertEquals(listOf("preview"), actions.calls)
    }

    @Test
    fun `a notification that opens another screen closes screen 6 too`() {
        show(ready)
        openNews()
        actions.newsLifecycle.clear()
        requests.intValue++
        rule.waitForIdle()
        rule.onNodeWithText("Diagnostics & history").assertIsDisplayed()
        assertEquals(listOf("close"), actions.newsLifecycle)
    }

    @Test
    fun `nothing shows before the first load`() {
        show(AppState())
        rule.onNodeWithText("Get started").assertDoesNotExist()
        rule.onNodeWithText("NextAlarm").assertDoesNotExist()
    }

    @Test
    fun `a new install starts with setup`() {
        show(AppState(loaded = true))
        rule.onNodeWithText("Get started").assertIsDisplayed()
    }

    @Test
    fun `setup goes through its steps to the end`() {
        shadowOf(application).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
        show(AppState(loaded = true, calendars = listOf(ALARMS), selectedCalendars = setOf(ALARMS.id)))
        rule.onNodeWithText("Get started").performClick()
        rule.onNodeWithText("Allow three things").assertIsDisplayed()
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("complete"), completed)
    }

    @Test
    fun `the permissions step asks for each permission`() {
        show(AppState(loaded = true))
        rule.onNodeWithText("Get started").performClick()
        rule.onNodeWithText("Read your calendars").assertIsDisplayed()
        rule.onNodeWithText("Send notifications").assertIsDisplayed()
        rule.onNodeWithText("Show alarms full screen").assertIsDisplayed()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `home leads to settings, the calendar, and back`() {
        show(ready)
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("NextAlarm · DAVx⁵").assertIsDisplayed()
        rule.onNodeWithText("Alarm calendars").performClick()
        rule.onNodeWithText(ALARM_CALENDARS_LEAD).assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Run setup again").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun `settings lead to the alarm settings, which report to the actions and come back`() {
        show(ready)
        openFromSettings("Alarm settings")
        rule.onNodeWithText("These apply to every alarm.").assertIsDisplayed()
        rule.onNodeWithText("Vibration").performScrollTo().performClick()
        assertEquals(listOf("vibrate:true"), actions.calls)
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Run setup again").assertIsDisplayed()
    }

    @Test
    fun `settings show the state of the last check and lead to diagnostics, which come back`() {
        show(ready.copy(diagnosticsSummary = "Last check failed"))
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Last check failed").performClick()
        rule.onNodeWithText("Past alarms").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Run setup again").assertIsDisplayed()
    }

    @Test
    fun `the test alarm goes to the actions`() {
        show(ready)
        openFromSettings("Diagnostics & history")
        rule.onNodeWithText("Ring a test alarm").performScrollTo().performClick()
        assertEquals(listOf("test"), actions.calls)
    }

    @Test
    fun `the fail-safe banner leads to diagnostics, and back to home`() {
        show(ready.copy(home = HomeModel(null, emptyList(), null, "Something is wrong.", null)))
        rule.onNodeWithText("See what went wrong").performClick()
        rule.onNodeWithText("Past alarms").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("See what went wrong").assertIsDisplayed()
    }

    @Test
    fun `a request from outside opens diagnostics, each time, with home behind it`() {
        show(ready)
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        requests.intValue = 1
        rule.waitForIdle()
        rule.onNodeWithText("Past alarms").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        requests.intValue = 2
        rule.waitForIdle()
        rule.onNodeWithText("Past alarms").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `setup comes first, even if a request asks for diagnostics`() {
        requests.intValue = 1
        show(AppState(loaded = true))
        rule.onNodeWithText("Get started").assertIsDisplayed()
    }

    @Test
    fun `ticking and unticking a calendar is reported`() {
        show(ready.copy(calendars = listOf(ALARMS, PLAIN)))
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Alarm calendars").performClick()
        rule.onNodeWithText("Phone").performClick()
        rule.onNodeWithText("NextAlarm").performClick()
        assertEquals(listOf("tick:2:true", "tick:1:false"), completed)
    }

    @Test
    fun `setup can be run again and returns home`() {
        shadowOf(application).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
        show(ready)
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Run setup again").performClick()
        rule.onNodeWithText("Get started").performClick()
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("complete"), completed)
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun `the source code opens in the browser`() {
        show(ready)
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("github.com/drehtuer/NextAlarmApp").performClick()
        val intent = nextActivity()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/drehtuer/NextAlarmApp", intent.dataString)
    }
}

/** Answers the file pickers the way Android does, from a real activity. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class DocumentPickersTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val actions = RecordingActions()
    private val ready = AppState(loaded = true, setupDone = true)
    private val picked = Uri.parse("content://docs/picked")

    private fun open(row: String, state: AppState = ready) {
        rule.setContent {
            NextAlarmTheme(dark = false) { NextAlarmApp(state, 0, { _, _ -> }, {}, actions) }
        }
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText(row).performClick()
    }

    private fun answer(request: ShadowActivity.IntentForResult, result: Uri?) {
        rule.runOnUiThread {
            rule.activity.activityResultRegistry.dispatchResult(
                request.requestCode,
                if (result != null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                result?.let { Intent().setData(it) },
            )
        }
        rule.waitForIdle()
    }

    private fun chooseFile(): ShadowActivity.IntentForResult {
        open("Alarm settings")
        rule.onNodeWithText("Choose file").performScrollTo().performClick()
        return shadowOf(rule.activity).nextStartedActivityForResult
    }

    private fun phoneSounds(state: AppState = ready): ShadowActivity.IntentForResult {
        open("Alarm settings", state)
        rule.onNodeWithText("Phone sounds").performScrollTo().performClick()
        return shadowOf(rule.activity).nextStartedActivityForResult
    }

    /** The phone's picker answers with an extra, not with the intent's data. */
    private fun answerPhoneSound(request: ShadowActivity.IntentForResult, result: Uri?) {
        rule.runOnUiThread {
            rule.activity.activityResultRegistry.dispatchResult(
                request.requestCode,
                if (result != null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                result?.let { Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, it) },
            )
        }
        rule.waitForIdle()
    }

    private fun exportLog(): ShadowActivity.IntentForResult {
        open("Diagnostics & history")
        rule.onNodeWithText("Export log").performScrollTo().performClick()
        return shadowOf(rule.activity).nextStartedActivityForResult
    }

    @Test
    fun `the sound picker offers only the supported types`() {
        val intent = chooseFile().intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(SUPPORTED_SOUND_MIME_TYPES, intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toSet())
    }

    @Test
    fun `a picked sound goes to the actions`() {
        answer(chooseFile(), picked)
        assertEquals(listOf("import:$picked"), actions.calls)
    }

    @Test
    fun `leaving the sound picker changes nothing`() {
        answer(chooseFile(), null)
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `Phone sounds opens the phone's own alarm sound picker, with the default marked while it is used`() {
        val intent = phoneSounds().intent
        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, intent.action)
        assertEquals(RingtoneManager.TYPE_ALARM, intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1))
        assertEquals(
            PickPhoneSound.DEFAULT_ALARM,
            intent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri::class.java),
        )
    }

    @Test
    fun `with a file set, the phone's picker marks nothing`() {
        val withFile = ready.copy(
            alarmSettings = buildAlarmSettings(
                DomainSettings.DEFAULT.copy(sound = SoundRef.File.forCopy("0".repeat(36), "morning.mp3")),
                null,
                null,
                null,
            ),
        )
        val intent = phoneSounds(withFile).intent
        assertTrue(!intent.hasExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI))
    }

    @Test
    fun `a phone sound goes to the actions`() {
        val oxygen = Uri.parse("content://media/internal/audio/media/37")
        answerPhoneSound(phoneSounds(), oxygen)
        assertEquals(listOf("phone:${PhoneSoundPick.Sound(oxygen.toString())}"), actions.calls)
    }

    @Test
    fun `the phone picker's default entry goes to the actions as the default`() {
        answerPhoneSound(phoneSounds(), PickPhoneSound.DEFAULT_ALARM)
        assertEquals(listOf("phone:${PhoneSoundPick.PhoneDefault}"), actions.calls)
    }

    @Test
    fun `leaving the phone's picker is reported as a cancel`() {
        answerPhoneSound(phoneSounds(), null)
        assertEquals(listOf("phone:${PhoneSoundPick.Cancelled}"), actions.calls)
    }

    @Test
    fun `the log is offered as a plain text file with its suggested name`() {
        val intent = exportLog().intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(ready.diagnostics.logFileName, intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun `a chosen document gets the log`() {
        answer(exportLog(), picked)
        assertEquals(listOf("export:$picked"), actions.calls)
    }

    @Test
    fun `leaving the document picker exports nothing`() {
        answer(exportLog(), null)
        assertTrue(actions.calls.isEmpty())
    }
}

/** Screen 6 in an activity whose life can be moved along. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class NewsLifecycleTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val actions = RecordingActions()

    private fun show(state: AppState) {
        rule.setContent { NextAlarmTheme(dark = false) { NextAlarmApp(state, 0, { _, _ -> }, {}, actions) } }
    }

    private fun hide() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
    }

    @Test
    fun `hiding screen 6 stops a test`() {
        show(AppState(loaded = true, setupDone = true))
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Alarm settings").performClick()
        rule.onNodeWithText("News").performScrollTo().performClick()
        rule.waitForIdle()
        hide()
        assertTrue("stopTest" in actions.newsLifecycle)
    }

    @Test
    fun `hiding screen 5 stops its sound, also after the screen has changed`() {
        var state by mutableStateOf(AppState(loaded = true, setupDone = true))
        rule.setContent { NextAlarmTheme(dark = false) { NextAlarmApp(state, 0, { _, _ -> }, {}, actions) } }
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Alarm settings").performClick()
        rule.waitForIdle()
        state = state.copy(alarmSettings = state.alarmSettings.withPreview(SoundPreviewStatus.PLAYING))
        rule.onNodeWithContentDescription("Stop the alarm sound").assertIsDisplayed()
        actions.previewLifecycle.clear()
        hide()
        assertEquals(listOf("stopPreview"), actions.previewLifecycle)
    }

    @Test
    fun `hiding screen 6 stops a test, also after the screen has changed`() {
        var state by mutableStateOf(AppState(loaded = true, setupDone = true))
        rule.setContent { NextAlarmTheme(dark = false) { NextAlarmApp(state, 0, { _, _ -> }, {}, actions) } }
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Alarm settings").performClick()
        rule.onNodeWithText("News").performScrollTo().performClick()
        rule.waitForIdle()
        state = state.copy(diagnosticsSummary = "changed")
        rule.waitForIdle()
        hide()
        assertTrue("stopTest" in actions.newsLifecycle)
    }

    @Test
    fun `home's sync button reaches the app, and is off while a sync is on its way`() {
        var state by mutableStateOf(AppState(loaded = true, setupDone = true))
        rule.setContent { NextAlarmTheme(dark = false) { NextAlarmApp(state, 0, { _, _ -> }, {}, actions) } }
        rule.onNodeWithContentDescription(SYNC_NOW).performClick()
        assertEquals(listOf("sync"), actions.calls)
        state = state.copy(syncPending = true)
        rule.onNodeWithContentDescription(SYNC_NOW).assertIsNotEnabled()
    }

    @Test
    fun `hiding another screen has no test to stop`() {
        show(AppState(loaded = true, setupDone = true))
        rule.waitForIdle()
        actions.previewLifecycle.clear()
        hide()
        assertTrue("stopTest" !in actions.newsLifecycle)
        assertTrue(actions.previewLifecycle.isEmpty())
    }
}

/** Answers permission requests the way Android does, from a real activity. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class PermissionRequestsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val application = ApplicationProvider.getApplicationContext<Application>()

    private fun showPermissions() {
        rule.setContent {
            NextAlarmTheme(dark = false) {
                NextAlarmApp(AppState(loaded = true), 0, { _, _ -> }, {}, RecordingActions(), canUseFullScreen = { false })
            }
        }
        rule.onNodeWithText("Get started").performClick()
    }

    private fun denyLastRequest() {
        val request = shadowOf(rule.activity).lastRequestedPermission
        val denied = IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED }
        rule.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, denied)
        rule.waitForIdle()
    }

    private fun clickAllow(index: Int) {
        rule.onAllNodesWithText("Allow")[index].performClick()
    }

    @Test
    fun `calendar access is requested, and a denial explains itself`() {
        showPermissions()
        clickAllow(0)
        assertEquals(listOf(Manifest.permission.READ_CALENDAR), shadowOf(rule.activity).lastRequestedPermission.requestedPermissions.toList())
        denyLastRequest()
        rule.onNodeWithText(
            "Calendar access was denied, so NextAlarm can't see any alarms and nothing will ring. Allow it in the app settings under Permissions.",
        ).assertIsDisplayed()
    }

    @Test
    fun `notifications are requested, and a denial leads to the app settings`() {
        showPermissions()
        clickAllow(1)
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), shadowOf(rule.activity).lastRequestedPermission.requestedPermissions.toList())
        denyLastRequest()
        rule.onNodeWithText(
            "Notifications were denied, so a ringing alarm can't show its screen or its Snooze and Dismiss buttons. Allow them in the app settings under Notifications.",
        ).assertIsDisplayed()
        rule.onNodeWithText("Open app settings").performClick()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(application).nextStartedActivity.action)
    }

    @Test
    fun `full-screen alarms open the system setting, and without them the alarm is only a notification`() {
        showPermissions()
        clickAllow(2)
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, shadowOf(application).nextStartedActivity.action)
        rule.onNodeWithText(
            "Alarms will still ring, but only as a notification at the top of the screen. Snooze and Dismiss are then small buttons in that notification.",
        ).assertIsDisplayed()
    }
}
