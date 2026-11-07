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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.AlarmSettingsModel
import de.drehtuer.nextalarm.domain.ValuePicker
import de.drehtuer.nextalarm.ui.AlarmSettingsActions

/**
 * Screen 5. [onChooseFile] opens the file picker. [onNews] is where the "News" row leads: screen 6, which does not
 * exist yet, so for now it is connected to nothing.
 */
@Composable
fun AlarmSettingsScreen(
    model: AlarmSettingsModel,
    actions: AlarmSettingsActions,
    onBack: () -> Unit,
    onChooseFile: () -> Unit,
    onNews: () -> Unit,
) {
    Scaffold(
        topBar = { NextAlarmTopBar("Alarm settings", onBack) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "These apply to every alarm.",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.lowVolume?.let { banner ->
                Banner(
                    title = banner.title,
                    body = banner.body,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    icon = R.drawable.ic_volume_off,
                )
            }
            SectionHeader("Sound")
            SoundRow(model, actions, onChooseFile)
            PickerRow("Volume ramp", "Rises from silent", model.volumeRamp, actions::setVolumeRamp)
            PickerRow("Maximum volume", "Not set: uses the phone's alarm volume", model.maxVolume, actions::setMaxVolume)
            SwitchRow("Vibration", null, model.vibrate, actions::setVibrate)
            SectionHeader("Ringing")
            PickerRow("Ring for", "Then it snoozes on its own", model.ringFor, actions::setRingFor)
            PickerRow("Snooze length", null, model.snoozeLength, actions::setSnoozeLength)
            SectionHeader("After you dismiss")
            SwitchRow("Speak the title", "Reads the event title aloud", model.speakTitle, actions::setSpeakTitle)
            NewsRow(model.news, onNews)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
        MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

/** The title and secondary line that all rows of this screen start with. */
@Composable
private fun RowText(title: String, secondary: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title)
        if (secondary != null) {
            Text(secondary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val RowModifier = Modifier.fillMaxWidth()

@Composable
private fun SoundRow(model: AlarmSettingsModel, actions: AlarmSettingsActions, onChooseFile: () -> Unit) {
    Row(
        RowModifier.heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        Arrangement.spacedBy(16.dp),
        Alignment.CenterVertically,
    ) {
        RowText("Alarm sound", model.soundName, Modifier.weight(1f))
        OutlinedButton(onClick = onChooseFile) { Text("Choose file") }
    }
    if (model.usesFile) {
        TextButton(onClick = actions::useDefaultSound, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Use the phone's default sound")
        }
    }
    model.soundRejection?.let { rejection ->
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), Arrangement.spacedBy(8.dp)) {
            SymbolIcon(R.drawable.ic_error, null, tint = MaterialTheme.colorScheme.error)
            Text(rejection, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun <T> PickerRow(title: String, secondary: String?, picker: ValuePicker<T>, onPick: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        RowModifier
            .clickable { open = true }
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        Arrangement.spacedBy(16.dp),
        Alignment.CenterVertically,
    ) {
        RowText(title, secondary, Modifier.weight(1f))
        Text(picker.label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    if (open) {
        PickerDialog(title, picker, onDismiss = { open = false }) {
            open = false
            onPick(it)
        }
    }
}

@Composable
private fun <T> PickerDialog(title: String, picker: ValuePicker<T>, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                picker.options.forEach { option ->
                    val selected = option.label == picker.label
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected, role = Role.RadioButton) { onPick(option.value) },
                        Arrangement.spacedBy(12.dp),
                        Alignment.CenterVertically,
                    ) {
                        RadioButton(selected, onClick = null)
                        Text(option.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchRow(title: String, secondary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        RowModifier
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        Arrangement.spacedBy(16.dp),
        Alignment.CenterVertically,
    ) {
        RowText(title, secondary, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun NewsRow(value: String, onNews: () -> Unit) {
    Row(
        RowModifier
            .clickable(onClick = onNews)
            .heightIn(min = 56.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        Arrangement.spacedBy(16.dp),
        Alignment.CenterVertically,
    ) {
        RowText("News", value, Modifier.weight(1f))
        SymbolIcon(R.drawable.ic_chevron, null)
    }
}
