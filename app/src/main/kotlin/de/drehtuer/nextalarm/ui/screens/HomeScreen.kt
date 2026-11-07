package de.drehtuer.nextalarm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.HomeDay
import de.drehtuer.nextalarm.domain.HomeHero
import de.drehtuer.nextalarm.domain.HomeModel
import de.drehtuer.nextalarm.domain.HomeRow
import de.drehtuer.nextalarm.ui.theme.ListTimeStyle

/** Screen 2. Read-only, because alarms are changed in the calendar app. */
@Composable
fun HomeScreen(model: HomeModel, onSettings: () -> Unit, onDiagnostics: () -> Unit) {
    Scaffold(
        topBar = {
            NextAlarmTopBar("NextAlarm", actions = {
                IconButton(onClick = onSettings) { SymbolIcon(R.drawable.ic_settings, "Settings") }
            })
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            model.failSafeBody?.let { body ->
                item {
                    Banner(
                        title = "New alarms aren't being added",
                        body = body,
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                        icon = R.drawable.ic_warning,
                    ) {
                        OutlinedButton(onClick = onDiagnostics) {
                            Text("See what went wrong", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
            model.hero?.let { hero -> item { Hero(hero) } }
            model.emptyBody?.let { body -> item { EmptyState(body) } }
            items(model.days) { day -> Day(day) }
            model.footer?.let { footer ->
                item {
                    Text(
                        footer,
                        Modifier.padding(horizontal = 8.dp),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventTitle(title: String?, maxLines: Int, style: TextStyle, modifier: Modifier = Modifier) {
    if (title != null) {
        Text(title, modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style)
    } else {
        Text("No title", modifier, MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic, style = style)
    }
}

@Composable
private fun Hero(hero: HomeHero) {
    Surface(
        Modifier.fillMaxWidth(),
        MaterialTheme.shapes.extraLarge,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(hero.label, style = MaterialTheme.typography.labelLarge)
            Text(hero.time, style = MaterialTheme.typography.displayMedium, maxLines = 1)
            Text(hero.relativeDay, style = MaterialTheme.typography.bodyMedium)
            EventTitle(hero.title, maxLines = 3, MaterialTheme.typography.titleMedium, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun EmptyState(body: String) {
    Surface(
        Modifier.fillMaxWidth(),
        MaterialTheme.shapes.extraLarge,
        MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(24.dp), Arrangement.spacedBy(8.dp)) {
            SymbolIcon(R.drawable.ic_calendar, null)
            Text("No alarms in the next 48 hours", style = MaterialTheme.typography.titleLarge)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Day(day: HomeDay) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            day.header,
            Modifier.padding(horizontal = 8.dp),
            MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Surface(Modifier.fillMaxWidth(), MaterialTheme.shapes.large, MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                day.rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlarmRow(row)
                }
            }
        }
    }
}

@Composable
private fun AlarmRow(row: HomeRow) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
        Text(row.time, Modifier.width(WIDE_TIME), style = ListTimeStyle, maxLines = 1)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EventTitle(row.title, maxLines = 2, MaterialTheme.typography.bodyLarge)
            row.snoozedUntil?.let { Chip(it) }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** The design's 72 dp plus room for a time with seconds, which it requires too and which does not fit in 72 dp. */
private val WIDE_TIME = 100.dp
