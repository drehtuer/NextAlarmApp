package de.drehtuer.nextalarm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.DeviceCalendar
import de.drehtuer.nextalarm.domain.groupCalendars

/** Screen 4. */
@Composable
fun CalendarScreen(
    calendars: List<DeviceCalendar>,
    selected: CalendarId?,
    onSelect: (CalendarId) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { NextAlarmTopBar("Alarm calendar", onBack) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            Arrangement.spacedBy(16.dp),
        ) {
            Text("Every timed event in this calendar rings at its start time. No other calendar is read.")
            CalendarList(calendars, selected, onSelect)
        }
    }
}

/** The list shared by screen 4 and the last step of onboarding. */
@Composable
fun CalendarList(calendars: List<DeviceCalendar>, selected: CalendarId?, onSelect: (CalendarId) -> Unit) {
    if (calendars.isEmpty()) {
        Surface(Modifier.fillMaxWidth(), MaterialTheme.shapes.extraLarge, MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(24.dp), Arrangement.spacedBy(8.dp)) {
                Text("No calendars on this phone", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Sync one from your Nextcloud with an app such as DAVx⁵, or create a local calendar in your " +
                        "calendar app. Then come back here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }
    Column(Modifier.selectableGroup(), Arrangement.spacedBy(8.dp)) {
        if (selected == null) {
            Banner(
                title = "No calendar chosen.",
                body = "Until you choose one, no alarm rings.",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        groupCalendars(calendars).forEach { group ->
            Text(group.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            group.calendars.forEach { calendar -> CalendarRow(calendar, calendar.id == selected, onSelect) }
        }
    }
}

@Composable
private fun CalendarRow(calendar: DeviceCalendar, selected: Boolean, onSelect: (CalendarId) -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(background, MaterialTheme.shapes.large)
            .selectable(selected, role = Role.RadioButton) { onSelect(calendar.id) }
            .padding(horizontal = 8.dp),
        Arrangement.spacedBy(12.dp),
        Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, Modifier.padding(8.dp))
        calendar.color?.let { Box(Modifier.size(12.dp).background(Color(it), MaterialTheme.shapes.extraSmall)) }
        Text(calendar.name)
    }
}
