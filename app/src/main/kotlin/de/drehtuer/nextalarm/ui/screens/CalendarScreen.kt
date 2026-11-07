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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.domain.ALARM_CALENDARS_LEAD
import de.drehtuer.nextalarm.domain.ALARM_CALENDARS_TITLE
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.DeviceCalendar
import de.drehtuer.nextalarm.domain.MISSING_CALENDARS_HEADER
import de.drehtuer.nextalarm.domain.MISSING_CALENDAR_ROW
import de.drehtuer.nextalarm.domain.NO_CALENDAR_CHOSEN_BANNER
import de.drehtuer.nextalarm.domain.groupCalendars
import de.drehtuer.nextalarm.domain.missingCalendars
import de.drehtuer.nextalarm.ui.theme.nextAlarmCheckboxColors

/** Screen 4. */
@Composable
fun CalendarScreen(
    calendars: List<DeviceCalendar>,
    selected: Set<CalendarId>,
    onToggle: (CalendarId, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { NextAlarmTopBar(ALARM_CALENDARS_TITLE, onBack) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            Arrangement.spacedBy(16.dp),
        ) {
            Text(ALARM_CALENDARS_LEAD, Modifier.padding(horizontal = TEXT_INSET))
            CalendarList(calendars, selected, onToggle, headerInset = TEXT_INSET)
        }
    }
}

/**
 * The list shared by screen 4 and the last step of onboarding. [headerInset] brings the group headers to the design's
 * 24 dp margin for text, where the list itself is at the 16 dp margin for cards. Any number of calendars can be ticked.
 */
@Composable
fun CalendarList(
    calendars: List<DeviceCalendar>,
    selected: Set<CalendarId>,
    onToggle: (CalendarId, Boolean) -> Unit,
    headerInset: Dp = 0.dp,
) {
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected.isEmpty()) {
            Banner(
                title = NO_CALENDAR_CHOSEN_BANNER.title,
                body = NO_CALENDAR_CHOSEN_BANNER.body,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        groupCalendars(calendars).forEach { group ->
            GroupHeader(group.label, headerInset)
            group.calendars.forEach { calendar ->
                CalendarRow(calendar.name, calendar.color, calendar.id in selected) { onToggle(calendar.id, it) }
            }
        }
        val missing = missingCalendars(selected, calendars)
        if (missing.isNotEmpty()) GroupHeader(MISSING_CALENDARS_HEADER, headerInset)
        missing.forEach { calendar ->
            CalendarRow(MISSING_CALENDAR_ROW, color = null, ticked = true) { onToggle(calendar, it) }
        }
    }
}

@Composable
private fun GroupHeader(label: String, inset: Dp) {
    Text(
        label,
        Modifier.padding(horizontal = inset),
        MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun CalendarRow(name: String, color: Int?, ticked: Boolean, onToggle: (Boolean) -> Unit) {
    val background = if (ticked) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(background, MaterialTheme.shapes.large)
            .toggleable(ticked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 8.dp),
        Arrangement.spacedBy(12.dp),
        Alignment.CenterVertically,
    ) {
        Checkbox(ticked, onCheckedChange = null, Modifier.padding(8.dp), colors = nextAlarmCheckboxColors())
        // Without a colour the place stays empty, so that the names line up.
        val swatch = color?.let(::Color) ?: Color.Transparent
        Box(Modifier.size(12.dp).background(swatch, MaterialTheme.shapes.extraSmall))
        Text(name)
    }
}

/** With the 16 dp around the column, the design's 24 dp margin for text. */
private val TEXT_INSET = 8.dp
