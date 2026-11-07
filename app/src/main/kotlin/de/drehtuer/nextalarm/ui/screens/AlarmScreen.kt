package de.drehtuer.nextalarm.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.AlarmScreenModel
import de.drehtuer.nextalarm.domain.isLongAlarmTitle
import de.drehtuer.nextalarm.ui.theme.LongAlarmTitleStyle

/**
 * Screen 8. Wrap it in `NightTheme`.
 *
 * The buttons are measured first and the rest of the screen gets what is left, so they stay on screen whatever the
 * title or the font size. Only the title area scrolls.
 */
@Composable
fun AlarmScreen(model: AlarmScreenModel, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            ClockAndTitles(model)
            SnoozeButton(model.ringsAgain, onSnooze)
            Spacer(Modifier.height(48.dp))
            NightStopButton("Dismiss", onDismiss)
        }
    }
}

@Composable
private fun ColumnScope.ClockAndTitles(model: AlarmScreenModel) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .clipToBounds()
            .padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            model.date,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        Clock(model.time)
        Titles(model.countLine, model.titles)
    }
}

/**
 * At most `displayLarge`, but smaller if it would not fit on one line, as a time with seconds does not.
 *
 * Text that sizes itself ignores its line height, so the box provides it. This also keeps the titles in place when the
 * size of the time changes.
 */
@Composable
private fun Clock(time: String) {
    val style = MaterialTheme.typography.displayLarge
    Box(
        Modifier
            .fillMaxWidth()
            .height(with(LocalDensity.current) { style.lineHeight.toDp() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            time,
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(unbounded = true),
            autoSize = TextAutoSize.StepBased(maxFontSize = style.fontSize),
            textAlign = TextAlign.Center,
            softWrap = false,
            maxLines = 1,
            style = style,
        )
    }
}

/** Centred while they fit, and scrollable when they do not. */
@Composable
private fun ColumnScope.Titles(countLine: String?, titles: List<String?>) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(top = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (countLine != null) {
                Text(
                    countLine,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            titles.forEach { title -> AlarmTitle(title, single = titles.size == 1) }
        }
    }
}

@Composable
private fun AlarmTitle(title: String?, single: Boolean) {
    val style = when {
        title != null && isLongAlarmTitle(title) -> LongAlarmTitleStyle
        single -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium)
        else -> MaterialTheme.typography.headlineSmall
    }
    if (title != null) {
        Text(title, textAlign = TextAlign.Center, style = style)
    } else {
        Text(
            "Alarm (event has no title)",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = style.merge(TextStyle(fontStyle = FontStyle.Italic)),
        )
    }
}

@Composable
private fun SnoozeButton(ringsAgain: String, onSnooze: () -> Unit) {
    OutlinedButton(
        onClick = onSnooze,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp),
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Snooze", textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
            if (ringsAgain.isNotEmpty()) {
                Text(
                    ringsAgain,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** The filled pill of Dismiss on screen 8 and Stop on screen 9. */
@Composable
internal fun NightStopButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        SymbolIcon(R.drawable.ic_stop, null, tint = MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
        )
    }
}
