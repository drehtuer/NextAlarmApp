package de.drehtuer.nextalarm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.ALARM_CALENDARS_TITLE
import de.drehtuer.nextalarm.domain.SETUP_SUMMARY

/** Where the rows of screen 3 lead. */
class SettingsTargets(
    val onBack: () -> Unit,
    val onAlarmCalendar: () -> Unit,
    val onAlarmSettings: () -> Unit,
    val onDiagnostics: () -> Unit,
    val onRunSetup: () -> Unit,
    val onSourceCode: () -> Unit,
)

/** Screen 3. */
@Composable
fun SettingsScreen(
    calendarSummary: String,
    diagnosticsSummary: String,
    version: String,
    targets: SettingsTargets,
) {
    Scaffold(
        topBar = { NextAlarmTopBar("Settings", targets.onBack) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsRow(ALARM_CALENDARS_TITLE, calendarSummary, targets.onAlarmCalendar)
            SettingsRow("Alarm settings", "Sound, snooze, speech and news", targets.onAlarmSettings)
            SettingsRow("Diagnostics & history", diagnosticsSummary, targets.onDiagnostics)
            SettingsRow("Run setup again", SETUP_SUMMARY, targets.onRunSetup)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "About",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                Arrangement.SpaceBetween,
                Alignment.CenterVertically,
            ) {
                Column {
                    Text("Version")
                    Text("Free software, GPL-3.0", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                Text(version, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = targets.onSourceCode)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                Arrangement.SpaceBetween,
                Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Source code")
                    Text(
                        "github.com/drehtuer/NextAlarmApp",
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SymbolIcon(R.drawable.ic_external_link, "Opens in the browser")
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        Arrangement.spacedBy(16.dp),
        Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        SymbolIcon(R.drawable.ic_chevron, null)
    }
}
