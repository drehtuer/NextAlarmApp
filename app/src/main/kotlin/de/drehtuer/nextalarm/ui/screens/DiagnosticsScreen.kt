package de.drehtuer.nextalarm.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.DiagnosticsModel
import de.drehtuer.nextalarm.domain.LOG_TITLES_NOTE
import de.drehtuer.nextalarm.domain.OutcomeKind
import de.drehtuer.nextalarm.domain.PastAlarmRow
import de.drehtuer.nextalarm.domain.StatusCard
import de.drehtuer.nextalarm.domain.StatusKind
import de.drehtuer.nextalarm.domain.TEST_ALARM_NOTE
import de.drehtuer.nextalarm.ui.theme.LocalExtraColors
import de.drehtuer.nextalarm.ui.theme.TABULAR_FIGURES
import de.drehtuer.nextalarm.ui.theme.outlinedButtonBorder
import de.drehtuer.nextalarm.ui.theme.tonalButtonColors

/** How a status card or an outcome chip looks. */
@Immutable
private data class Tone(
    val container: Color,
    val content: Color,
    @param:DrawableRes val icon: Int,
)

/** Screen 7. [testAlarmPending] and [logNote] are what the buttons have started. */
@Composable
fun DiagnosticsScreen(
    model: DiagnosticsModel,
    testAlarmPending: Boolean,
    logNote: String?,
    onBack: () -> Unit,
    onRingTestAlarm: () -> Unit,
    onExportLog: () -> Unit,
) {
    Scaffold(
        topBar = { NextAlarmTopBar("Diagnostics & history", onBack) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            Arrangement.spacedBy(16.dp),
        ) {
            Status(model.status)
            Facts(model)
            Buttons(testAlarmPending, logNote, onRingTestAlarm, onExportLog)
            Text(
                "Past alarms",
                Modifier.padding(horizontal = TEXT_INSET),
                MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
            PastAlarms(model.pastAlarms)
        }
    }
}

@Composable
private fun statusTone(kind: StatusKind): Tone {
    val colors = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    return when (kind) {
        StatusKind.NOT_SET_UP -> Tone(colors.surfaceContainer, colors.onSurface, R.drawable.ic_settings)
        StatusKind.PLANNING -> Tone(colors.primaryContainer, colors.onPrimaryContainer, R.drawable.ic_refresh)
        StatusKind.PLANNED -> Tone(extra.successContainer, extra.onSuccessContainer, R.drawable.ic_check)
        StatusKind.FAIL_SAFE -> Tone(colors.errorContainer, colors.onErrorContainer, R.drawable.ic_warning)
    }
}

@Composable
private fun outcomeTone(kind: OutcomeKind): Tone {
    val colors = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    return when (kind) {
        OutcomeKind.DISMISSED -> Tone(extra.successContainer, extra.onSuccessContainer, R.drawable.ic_check)
        OutcomeKind.LATE -> Tone(colors.secondaryContainer, colors.onSecondaryContainer, R.drawable.ic_clock)
        OutcomeKind.UNANSWERED -> Tone(colors.secondaryContainer, colors.onSecondaryContainer, R.drawable.ic_warning)
        OutcomeKind.CANCELLED -> Tone(colors.surfaceVariant, colors.onSurfaceVariant, R.drawable.ic_cross)
        OutcomeKind.MISSED -> Tone(colors.errorContainer, colors.onErrorContainer, R.drawable.ic_cross)
    }
}

@Composable
private fun Status(status: StatusCard) {
    val tone = statusTone(status.kind)
    Banner(
        title = status.title,
        body = status.body,
        container = tone.container,
        content = tone.content,
        icon = tone.icon,
    ) {
        if (status.kind == StatusKind.PLANNING) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun Facts(model: DiagnosticsModel) {
    Surface(Modifier.fillMaxWidth(), MaterialTheme.shapes.large, MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(12.dp)) {
            Fact("Calendar", model.calendar)
            Fact("Next check", model.nextCheck)
            Fact("Full-screen alarms", model.fullScreen)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Buttons(testAlarmPending: Boolean, logNote: String?, onRingTestAlarm: () -> Unit, onExportLog: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onRingTestAlarm, Modifier.fillMaxWidth(), enabled = !testAlarmPending, colors = tonalButtonColors()) {
            // The icon follows the label, which in a disabled button is dimmed.
            SymbolIcon(R.drawable.ic_bell, null, Modifier.size(ButtonDefaults.IconSize), tint = LocalContentColor.current)
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Ring a test alarm")
        }
        if (testAlarmPending) Note(TEST_ALARM_NOTE)
        OutlinedButton(onExportLog, Modifier.fillMaxWidth(), border = outlinedButtonBorder()) { Text("Export log") }
        Note(LOG_TITLES_NOTE)
        logNote?.let { Note(it) }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = TEXT_INSET),
        MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun PastAlarms(rows: List<PastAlarmRow>) {
    Surface(Modifier.fillMaxWidth(), MaterialTheme.shapes.large, MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            if (rows.isEmpty()) {
                Text(
                    "No alarms have ended yet.",
                    Modifier.padding(16.dp),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PastAlarm(row)
            }
        }
    }
}

@Composable
private fun PastAlarm(row: PastAlarmRow) {
    Row(Modifier.padding(16.dp), Arrangement.spacedBy(12.dp)) {
        Text(
            row.time,
            Modifier
                .width(TIME_WIDTH)
                .alignByBaseline(),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
            maxLines = 1,
        )
        Column(Modifier.alignByBaseline(), Arrangement.spacedBy(4.dp)) {
            val title = row.title
            if (title != null) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(
                    "No title",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            OutcomeChip(row)
        }
    }
}

@Composable
private fun OutcomeChip(row: PastAlarmRow) {
    val tone = outcomeTone(row.kind)
    Surface(shape = MaterialTheme.shapes.small, color = tone.container, contentColor = tone.content) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            Arrangement.spacedBy(4.dp),
            Alignment.CenterVertically,
        ) {
            SymbolIcon(tone.icon, null, Modifier.size(CHIP_ICON), tint = tone.content)
            Text(row.outcome, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Room for "Thu 07:00:30", for an alarm whose time has seconds. */
private val TIME_WIDTH = 88.dp
private val CHIP_ICON = 16.dp

/** With the 16 dp around the column, the design's 24 dp margin for text. */
private val TEXT_INSET = 8.dp
