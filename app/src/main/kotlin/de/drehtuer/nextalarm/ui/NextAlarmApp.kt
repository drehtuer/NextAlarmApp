package de.drehtuer.nextalarm.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import de.drehtuer.nextalarm.BuildConfig
import de.drehtuer.nextalarm.data.SUPPORTED_SOUND_MIME_TYPES
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.calendarSummary
import de.drehtuer.nextalarm.ui.screens.AlarmSettingsScreen
import de.drehtuer.nextalarm.ui.screens.CalendarScreen
import de.drehtuer.nextalarm.ui.screens.DiagnosticsScreen
import de.drehtuer.nextalarm.ui.screens.HomeScreen
import de.drehtuer.nextalarm.ui.screens.OnboardingScreen
import de.drehtuer.nextalarm.ui.screens.PermissionCard
import de.drehtuer.nextalarm.ui.screens.SettingsScreen
import de.drehtuer.nextalarm.ui.screens.SettingsTargets

private const val SOURCE_CODE_URL = "https://github.com/drehtuer/NextAlarmApp"

const val VERSION_TEXT_FORMAT = "%s (build %d)"

const val LOG_MIME_TYPE = "text/plain"

enum class Route { HOME, SETTINGS, CALENDAR, ALARM_SETTINGS, DIAGNOSTICS, SETUP }

/**
 * The screens and how they connect. See the navigation diagram in docs/screens.md.
 *
 * [diagnosticsRequests] counts the requests to open screen 7 from outside, from a notification: each new value opens it.
 */
@Composable
fun NextAlarmApp(
    state: AppState,
    resumeCount: Int,
    onSelectCalendar: (CalendarId) -> Unit,
    onCompleteSetup: () -> Unit,
    actions: AppActions,
    diagnosticsRequests: Int = 0,
    canUseFullScreen: (Context) -> Boolean = ::canUseFullScreenIntent,
) {
    val context = LocalContext.current
    var stack by rememberSaveable { mutableStateOf(listOf(Route.HOME.name)) }
    val route = Route.valueOf(stack.last())
    val inSetup = !state.setupDone || route == Route.SETUP

    BackHandler(enabled = state.setupDone && stack.size > 1) { stack = stack.dropLast(1) }

    LaunchedEffect(diagnosticsRequests) {
        if (diagnosticsRequests > 0) stack = listOf(Route.HOME.name, Route.DIAGNOSTICS.name)
    }

    when {
        !state.loaded -> Surface(Modifier.fillMaxSize()) {}
        inSetup -> {
            var step by rememberSaveable { mutableIntStateOf(0) }
            OnboardingScreen(
                step = step,
                onStepChange = { step = it },
                permissions = permissionCards(resumeCount, canUseFullScreen),
                calendars = state.calendars,
                selected = state.selectedCalendar,
                onSelect = onSelectCalendar,
                onDone = {
                    onCompleteSetup()
                    step = 0
                    stack = listOf(Route.HOME.name)
                },
            )
        }
        route == Route.SETTINGS -> SettingsScreen(
            calendarSummary = calendarSummary(state.calendars.find { it.id == state.selectedCalendar }),
            diagnosticsSummary = state.diagnosticsSummary,
            version = VERSION_TEXT_FORMAT.format(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            targets = SettingsTargets(
                onBack = { stack = stack.dropLast(1) },
                onAlarmCalendar = { stack = stack + Route.CALENDAR.name },
                onAlarmSettings = { stack = stack + Route.ALARM_SETTINGS.name },
                onDiagnostics = { stack = stack + Route.DIAGNOSTICS.name },
                onRunSetup = { stack = stack + Route.SETUP.name },
                onSourceCode = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_CODE_URL))) },
            ),
        )
        route == Route.ALARM_SETTINGS -> {
            val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) actions.importSound(uri)
            }
            AlarmSettingsScreen(
                model = state.alarmSettings,
                actions = actions,
                onBack = { stack = stack.dropLast(1) },
                onChooseFile = { soundPicker.launch(SUPPORTED_SOUND_MIME_TYPES.toTypedArray()) },
                // Screen 6 does not exist yet. This is where it gets connected.
                onNews = {},
            )
        }
        route == Route.DIAGNOSTICS -> {
            val logDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LOG_MIME_TYPE)) { uri ->
                if (uri != null) actions.exportLog(uri)
            }
            DiagnosticsScreen(
                model = state.diagnostics,
                testAlarmPending = state.testAlarmPending,
                logNote = state.logNote,
                onBack = { stack = stack.dropLast(1) },
                onRingTestAlarm = actions::ringTestAlarm,
                onExportLog = { logDocument.launch(state.diagnostics.logFileName) },
            )
        }
        route == Route.CALENDAR -> CalendarScreen(
            calendars = state.calendars,
            selected = state.selectedCalendar,
            onSelect = onSelectCalendar,
            onBack = { stack = stack.dropLast(1) },
        )
        else -> HomeScreen(
            state.home,
            onSettings = { stack = stack + Route.SETTINGS.name },
            onDiagnostics = { stack = stack + Route.DIAGNOSTICS.name },
        )
    }
}

private fun Context.has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Context.appSettingsIntent() =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

/** The cards of the permissions step. [resumeCount] re-reads them when the user comes back from the system settings. */
@Composable
private fun permissionCards(resumeCount: Int, canUseFullScreen: (Context) -> Boolean): List<PermissionCard> {
    val context = LocalContext.current
    var calendarAsked by rememberSaveable { mutableStateOf(false) }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    var fullScreenAsked by rememberSaveable { mutableStateOf(false) }
    var results by rememberSaveable { mutableIntStateOf(0) }

    val calendarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        calendarAsked = true
        results++
    }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAsked = true
        results++
    }
    // These two values are read so that the cards are rebuilt after a permission result or a return from the settings.
    remember(resumeCount, results) {}

    val openSettings = { context.startActivity(context.appSettingsIntent()) }
    return listOf(
        PermissionCard(
            title = "Read your calendars",
            description = "To see the events in your alarm calendar. Only that one calendar is read.",
            granted = context.has(Manifest.permission.READ_CALENDAR),
            deniedNote = if (calendarAsked) {
                "Calendar access was denied, so NextAlarm can't see any alarms and nothing will ring. Allow it in " +
                    "the app settings under Permissions."
            } else {
                null
            },
            required = true,
            onAllow = { calendarLauncher.launch(Manifest.permission.READ_CALENDAR) },
            onOpenSettings = openSettings,
        ),
        PermissionCard(
            title = "Send notifications",
            description = "To show the next alarm, and to ring when the phone is locked.",
            granted = context.has(Manifest.permission.POST_NOTIFICATIONS),
            deniedNote = if (notificationsAsked) {
                "Notifications were denied, so a ringing alarm can't show its screen or its Snooze and Dismiss " +
                    "buttons. Allow them in the app settings under Notifications."
            } else {
                null
            },
            required = true,
            onAllow = { notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onOpenSettings = openSettings,
        ),
        PermissionCard(
            title = "Show alarms full screen",
            description = "So a ringing alarm fills the screen, even when the phone is locked.",
            granted = canUseFullScreen(context),
            deniedNote = if (fullScreenAsked) {
                "Alarms will still ring, but only as a notification at the top of the screen. Snooze and Dismiss " +
                    "are then small buttons in that notification."
            } else {
                null
            },
            required = false,
            onAllow = {
                fullScreenAsked = true
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", context.packageName, null)),
                )
            },
            onOpenSettings = openSettings,
        ),
    )
}
