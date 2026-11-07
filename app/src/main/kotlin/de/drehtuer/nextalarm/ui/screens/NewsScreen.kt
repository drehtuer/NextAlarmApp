package de.drehtuer.nextalarm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.DetectedFeedModel
import de.drehtuer.nextalarm.domain.NewsModel
import de.drehtuer.nextalarm.domain.NewsTestLine
import de.drehtuer.nextalarm.domain.NewsType
import de.drehtuer.nextalarm.ui.NewsActions
import de.drehtuer.nextalarm.ui.theme.filledButtonColors
import de.drehtuer.nextalarm.ui.theme.outlinedButtonBorder

/** Screen 6. */
@Composable
fun NewsScreen(model: NewsModel, actions: NewsActions, onBack: () -> Unit) {
    Scaffold(
        topBar = { NextAlarmTopBar("News", onBack) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "After you dismiss an alarm, NextAlarm can read an RSS feed aloud, or play the latest episode of a podcast.",
                Modifier.padding(horizontal = 8.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AddressField(model, actions::setNewsAddress)
            model.failure?.let { failure ->
                Banner(
                    title = failure.title,
                    body = failure.body,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    icon = R.drawable.ic_error,
                )
            }
            model.detected?.let { DetectedFeed(it) }
            DurationStepper(model, actions)
            TestButton(model, actions::toggleNewsTest)
            model.testLine?.let { TestProgress(it) }
            TextButton(actions::turnOffNews, Modifier.fillMaxWidth(), enabled = model.canTurnOff) {
                Text("Turn off news")
            }
        }
    }
}

/** Built from its parts, because only that way the border of a field that has lost the focus can be made thicker. */
@Composable
private fun AddressField(model: NewsModel, onChange: (String) -> Unit) {
    val colors = OutlinedTextFieldDefaults.colors()
    val interactions = remember { MutableInteractionSource() }
    val text = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = model.address,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = OutlinedTextFieldDefaults.MinHeight)
            .semantics { if (model.addressFailed) error(model.helper) },
        textStyle = LocalTextStyle.current.copy(color = text),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
        singleLine = true,
        interactionSource = interactions,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    ) { field ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = model.address,
            innerTextField = field,
            enabled = true,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactions,
            isError = model.addressFailed,
            label = { Text("Feed address") },
            placeholder = { Text("https://…") },
            supportingText = { Text(model.helper) },
            colors = colors,
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = true,
                    isError = model.addressFailed,
                    interactionSource = interactions,
                    colors = colors,
                    shape = MaterialTheme.shapes.small,
                    focusedBorderThickness = BORDER_FOCUSED,
                    unfocusedBorderThickness = if (model.addressFailed) BORDER_FOCUSED else BORDER_NORMAL,
                )
            },
        )
    }
}

@Composable
private fun DetectedFeed(feed: DetectedFeedModel) {
    Surface(Modifier.fillMaxWidth(), MaterialTheme.shapes.large, MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(16.dp), Arrangement.spacedBy(16.dp), Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(TILE)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                SymbolIcon(
                    when (feed.kind) {
                        NewsType.RSS -> R.drawable.ic_rss
                        NewsType.PODCAST -> R.drawable.ic_microphone
                    },
                    null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column {
                Text(feed.kindLine, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Text(feed.name, style = MaterialTheme.typography.bodyLarge)
                feed.detail?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DurationStepper(model: NewsModel, actions: NewsActions) {
    Row(Modifier.padding(horizontal = 8.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Maximum duration")
            Text(
                "Playback stops after this",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        StepButton("−", "Shorter", model.canShorten, actions::shortenNews)
        Text(model.minutes, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        StepButton("+", "Longer", model.canLengthen, actions::lengthenNews)
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick,
        Modifier
            .size(STEP_BUTTON)
            .semantics { contentDescription = description },
        enabled = enabled,
        border = outlinedButtonBorder(enabled),
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun TestButton(model: NewsModel, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth(), enabled = model.canTest, colors = filledButtonColors()) {
        // The icon follows the label, which in a disabled button is dimmed.
        SymbolIcon(
            if (model.testing) R.drawable.ic_stop else R.drawable.ic_play,
            null,
            Modifier.size(ButtonDefaults.IconSize),
            tint = LocalContentColor.current,
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(if (model.testing) "Stop test" else "Test playback")
    }
}

@Composable
private fun TestProgress(line: NewsTestLine) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val progress = line.progress
        val track = MaterialTheme.colorScheme.outlineVariant
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), trackColor = track)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = track)
        }
        Text(
            line.text,
            Modifier.padding(horizontal = 8.dp),
            MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private val TILE = 40.dp
private val STEP_BUTTON = 48.dp
private val BORDER_NORMAL = 1.dp
private val BORDER_FOCUSED = 2.dp
