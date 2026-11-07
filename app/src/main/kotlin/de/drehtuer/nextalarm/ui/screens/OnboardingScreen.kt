package de.drehtuer.nextalarm.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.DeviceCalendar
import de.drehtuer.nextalarm.ui.theme.LocalExtraColors
import de.drehtuer.nextalarm.ui.theme.filledButtonColors
import de.drehtuer.nextalarm.ui.theme.outlinedButtonBorder

/** One card of the permissions step. */
data class PermissionCard(
    val title: String,
    val description: String,
    val granted: Boolean,
    /** Shown when the permission was denied. Empty otherwise. */
    val deniedNote: String?,
    /** Whether going on without this permission means that alarms cannot work. */
    val required: Boolean,
    val onAllow: () -> Unit,
    val onOpenSettings: () -> Unit,
)

private const val LAST_STEP = 2

/** Screen 1: three steps, with the current one chosen by the caller. */
@Composable
fun OnboardingScreen(
    step: Int,
    onStepChange: (Int) -> Unit,
    permissions: List<PermissionCard>,
    calendars: List<DeviceCalendar>,
    selected: CalendarId?,
    onSelect: (CalendarId) -> Unit,
    onDone: () -> Unit,
) {
    val canGoOn = when (step) {
        0 -> true
        1 -> permissions.filter { it.required }.all { it.granted }
        else -> selected != null
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            Arrangement.spacedBy(24.dp),
        ) {
            Progress(step)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                Arrangement.spacedBy(16.dp),
            ) {
                when (step) {
                    0 -> Explanation()
                    1 -> Permissions(permissions)
                    else -> ChooseCalendar(calendars, selected, onSelect)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton(onClick = { onStepChange(step - 1) }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { if (step == LAST_STEP) onDone() else onStepChange(step + 1) },
                    enabled = canGoOn,
                    colors = filledButtonColors(),
                ) {
                    Text(listOf("Get started", "Continue", "Done")[step])
                }
            }
        }
    }
}

@Composable
private fun Progress(step: Int) {
    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
        repeat(LAST_STEP + 1) { index ->
            val color = if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.weight(1f).height(4.dp).background(color, CircleShape))
        }
    }
}

@Composable
private fun Heading(title: String, lead: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Text(lead, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Explanation() {
    Heading(
        "Your calendar sets the alarms",
        "NextAlarm has no alarm editor. You plan alarms in your calendar app, and this phone rings them.",
    )
    ExplanationRow(
        R.drawable.ic_calendar,
        "Every event is an alarm",
        "Each timed event in the calendar you choose rings at its start time.",
    )
    ExplanationRow(
        R.drawable.ic_repeat,
        "Regular alarms repeat",
        "For a weekday alarm, make a recurring event. Skip a day by deleting that one occurrence.",
    )
    ExplanationRow(
        R.drawable.ic_bin,
        "Change or delete in the calendar",
        "Move an event and its alarm moves. Delete it and the alarm is gone.",
    )
}

@Composable
private fun ExplanationRow(@DrawableRes icon: Int, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            Alignment.Center,
        ) {
            SymbolIcon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Permissions(permissions: List<PermissionCard>) {
    Heading("Allow three things", "NextAlarm only reads the one calendar you choose next. Nothing leaves your phone.")
    permissions.forEach { Permission(it) }
}

@Composable
private fun Permission(card: PermissionCard) {
    Surface(Modifier.fillMaxWidth(), MaterialTheme.shapes.large, MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.title, style = MaterialTheme.typography.labelLarge)
                    Text(card.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                if (card.granted) Allowed() else Button(onClick = card.onAllow) { Text("Allow") }
            }
            if (card.deniedNote != null && !card.granted) DeniedNote(card)
        }
    }
}

@Composable
private fun Allowed() {
    val green = LocalExtraColors.current.success
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        SymbolIcon(R.drawable.ic_check, null, tint = green)
        Text("Allowed", color = green, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DeniedNote(card: PermissionCard) {
    val colors = MaterialTheme.colorScheme
    val container: Color = if (card.required) colors.errorContainer else colors.secondaryContainer
    val content: Color = if (card.required) colors.onErrorContainer else colors.onSecondaryContainer
    Banner(title = null, body = card.deniedNote.orEmpty(), container = container, content = content) {
        OutlinedButton(onClick = card.onOpenSettings, border = outlinedButtonBorder()) { Text("Open app settings", color = content) }
    }
}

@Composable
private fun ChooseCalendar(calendars: List<DeviceCalendar>, selected: CalendarId?, onSelect: (CalendarId) -> Unit) {
    Heading(
        "Choose the alarm calendar",
        "Every timed event in it will ring. Use a calendar just for alarms, so meetings don't wake you.",
    )
    CalendarList(calendars, selected, onSelect)
}
