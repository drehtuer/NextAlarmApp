package de.drehtuer.nextalarm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.NowPlayingModel
import de.drehtuer.nextalarm.domain.PlayingKind
import de.drehtuer.nextalarm.domain.WakeUpScreenModel
import de.drehtuer.nextalarm.ui.theme.TABULAR_FIGURES

/**
 * Screen 9. Wrap it in `NightTheme`.
 *
 * Like on screen 8, what the user has to reach is measured first, so that it stays on screen whatever the titles or the
 * font size. Only the titles scroll.
 */
@Composable
fun WakeUpScreen(model: WakeUpScreenModel, onStop: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            ClockAndTitles(model)
            model.playing?.let { NowPlayingCard(it) }
            Text(
                "Unlocking the phone won't stop it.",
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            NightStopButton("Stop", onStop)
        }
    }
}

@Composable
private fun ColumnScope.ClockAndTitles(model: WakeUpScreenModel) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .clipToBounds()
            .padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            model.greeting,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(model.time, textAlign = TextAlign.Center, maxLines = 1, style = MaterialTheme.typography.displaySmall)
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
                model.titles.forEach { WakeUpTitle(it) }
            }
        }
    }
}

@Composable
private fun WakeUpTitle(title: String?) {
    val style = MaterialTheme.typography.headlineSmall
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
private fun NowPlayingCard(playing: NowPlayingModel) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindDisc(playing.kind)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        playing.kindLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        playing.mainLine,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
            PlayProgress(playing.progress)
            PlayTimes(playing.elapsed, playing.total)
        }
    }
}

/** The badge colours of the night theme: `#004A76` on `#CFE5FF`. */
@Composable
private fun KindDisc(kind: PlayingKind) {
    Box(
        Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.onPrimaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        SymbolIcon(
            when (kind) {
                PlayingKind.SPEECH -> R.drawable.ic_speaker
                PlayingKind.NEWS -> R.drawable.ic_rss
                PlayingKind.PODCAST -> R.drawable.ic_microphone
            },
            null,
            tint = MaterialTheme.colorScheme.primaryContainer,
        )
    }
}

@Composable
private fun PlayProgress(progress: Float?) {
    val modifier = Modifier
        .fillMaxWidth()
        .height(4.dp)
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier, color = color, trackColor = track)
    } else {
        LinearProgressIndicator(modifier, color = color, trackColor = track)
    }
}

@Composable
private fun PlayTimes(elapsed: String?, total: String?) {
    if (elapsed == null && total == null) return
    val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR_FIGURES)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(elapsed.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = style)
        Text(total.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = style)
    }
}
