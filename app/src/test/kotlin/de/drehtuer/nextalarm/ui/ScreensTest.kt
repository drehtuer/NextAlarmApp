package de.drehtuer.nextalarm.ui

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.DeviceCalendar
import de.drehtuer.nextalarm.domain.HomeDay
import de.drehtuer.nextalarm.domain.HomeHero
import de.drehtuer.nextalarm.domain.HomeModel
import de.drehtuer.nextalarm.domain.HomeRow
import de.drehtuer.nextalarm.ui.screens.CalendarScreen
import de.drehtuer.nextalarm.ui.screens.HomeScreen
import de.drehtuer.nextalarm.ui.screens.OnboardingScreen
import de.drehtuer.nextalarm.ui.screens.PermissionCard
import de.drehtuer.nextalarm.ui.screens.SettingsScreen
import de.drehtuer.nextalarm.ui.theme.DarkColors
import de.drehtuer.nextalarm.ui.theme.DarkExtraColors
import de.drehtuer.nextalarm.ui.theme.LightColors
import de.drehtuer.nextalarm.ui.theme.LightExtraColors
import de.drehtuer.nextalarm.ui.theme.LocalExtraColors
import de.drehtuer.nextalarm.ui.theme.NextAlarmTheme
import androidx.compose.material3.MaterialTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private val ALARMS = DeviceCalendar(CalendarId(1), "NextAlarm", "me@cloud.example", "bitfire.at.davdroid", 0xFF00639B.toInt())
private val PLAIN = DeviceCalendar(CalendarId(2), "Phone", "local", "LOCAL", null)

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class ScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val empty = HomeModel(null, emptyList(), "Nothing is planned until Sunday 20:00.", null, null)

    private fun home(model: HomeModel, onSettings: () -> Unit = {}) {
        rule.setContent { NextAlarmTheme(dark = false) { HomeScreen(model, onSettings) } }
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
        home(empty.copy(failSafeBody = "The alarms below will still ring."))
        rule.onNodeWithText("New alarms aren't being added").assertIsDisplayed()
        rule.onNodeWithText("The alarms below will still ring.").assertIsDisplayed()
    }

    @Test
    fun `the calendar screen groups calendars and reports the choice`() {
        var chosen: CalendarId? = null
        var back = false
        rule.setContent {
            NextAlarmTheme {
                CalendarScreen(listOf(ALARMS, PLAIN), ALARMS.id, { chosen = it }, { back = true })
            }
        }
        rule.onNodeWithText("DAVx⁵ · me@cloud.example").assertIsDisplayed()
        rule.onNodeWithText("On this phone").assertIsDisplayed()
        rule.onNodeWithText("NextAlarm").assertIsSelected()
        rule.onNodeWithText("Phone").performClick()
        assertEquals(PLAIN.id, chosen)
        rule.onNodeWithContentDescription("Back").performClick()
        assertTrue(back)
    }

    @Test
    fun `the calendar screen warns when none is chosen`() {
        rule.setContent { NextAlarmTheme { CalendarScreen(listOf(PLAIN), null, {}, {}) } }
        rule.onNodeWithText("No calendar chosen.").assertIsDisplayed()
    }

    @Test
    fun `the calendar screen explains when the phone has none`() {
        rule.setContent { NextAlarmTheme { CalendarScreen(emptyList(), null, {}, {}) } }
        rule.onNodeWithText("No calendars on this phone").assertIsDisplayed()
    }

    @Test
    fun `settings show the calendar, the version and the rows`() {
        val calls = mutableListOf<String>()
        rule.setContent {
            NextAlarmTheme {
                SettingsScreen(
                    "NextAlarm · DAVx⁵",
                    "0.1.0 (build 1)",
                    { calls += "back" },
                    { calls += "calendar" },
                    { calls += "setup" },
                    { calls += "source" },
                )
            }
        }
        rule.onNodeWithText("NextAlarm · DAVx⁵").performClick()
        rule.onNodeWithText("Run setup again").performClick()
        rule.onNodeWithText("0.1.0 (build 1)").assertIsDisplayed()
        rule.onNodeWithText("github.com/drehtuer/NextAlarmApp").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("calendar", "setup", "source", "back"), calls)
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
        selected: CalendarId? = null,
        onStep: (Int) -> Unit = {},
        onSelect: (CalendarId) -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        rule.setContent {
            NextAlarmTheme(dark = true) {
                OnboardingScreen(step, onStep, permissions, calendars, selected, onSelect, onDone)
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
        rule.onNodeWithText("Choose the alarm calendar").assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsNotEnabled()
    }

    @Test
    fun `the last step finishes setup and reports the choice`() {
        var done = false
        var chosen: CalendarId? = null
        onboarding(2, calendars = listOf(ALARMS, PLAIN), selected = ALARMS.id, onSelect = { chosen = it }, onDone = { done = true })
        rule.onNodeWithText("Phone").performClick()
        assertEquals(PLAIN.id, chosen)
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
    private val ready = AppState(loaded = true, setupDone = true, calendars = listOf(ALARMS), selectedCalendar = ALARMS.id)
    private val completed = mutableListOf<String>()

    private fun show(state: AppState) {
        rule.setContent {
            NextAlarmTheme(dark = false) {
                NextAlarmApp(state, 0, { completed += "choose:${it.value}" }, { completed += "complete" })
            }
        }
    }

    private fun nextActivity(): Intent = shadowOf(application).nextStartedActivity

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
        show(AppState(loaded = true, calendars = listOf(ALARMS), selectedCalendar = ALARMS.id))
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
        rule.onNodeWithText("Alarm calendar").performClick()
        rule.onNodeWithText("Every timed event in this calendar rings at its start time. No other calendar is read.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Run setup again").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun `choosing a calendar is reported`() {
        show(ready.copy(calendars = listOf(ALARMS, PLAIN)))
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Alarm calendar").performClick()
        rule.onNodeWithText("Phone").performClick()
        assertEquals(listOf("choose:2"), completed)
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
                NextAlarmApp(AppState(loaded = true), 0, {}, {}, canUseFullScreen = { false })
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
