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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.domain.WeatherModel
import de.drehtuer.nextalarm.ui.WeatherActions
import de.drehtuer.nextalarm.ui.theme.outlinedButtonBorder

/**
 * The weather after the news, reached from screen 5. [onUseCurrentLocation] goes by way of the location permission, which
 * the caller asks for.
 */
@Composable
fun WeatherScreen(
    model: WeatherModel,
    actions: WeatherActions,
    onBack: () -> Unit,
    onUseCurrentLocation: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = { NextAlarmTopBar("Weather", onBack) },
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
                "After the news, NextAlarm can say today's weather forecast. It comes from Open-Meteo.",
                Modifier.padding(horizontal = 8.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.chosen?.let { Text(it, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium) }
            OutlinedTextField(
                value = model.query,
                onValueChange = actions::setWeatherQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (model.chosen == null) "Place" else "Another place") },
                supportingText = { Text(model.helper) },
                isError = model.searchFailed,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.small,
            )
            if (model.places.isNotEmpty()) {
                Column {
                    model.places.forEachIndexed { index, label ->
                        PlaceRow(label) { actions.chooseWeatherPlace(index) }
                    }
                }
            }
            SwitchRow(
                "Use the current location",
                "Where the phone was last, else the place above",
                model.useCurrentLocation,
                onUseCurrentLocation,
                margin = PICKER_MARGIN,
                enabled = model.canUseCurrentLocation,
            )
            PickerRow("Language", "Of the forecast", model.language, actions::setWeatherLanguage, PICKER_MARGIN)
            OutlinedButton(
                onClick = actions::previewWeather,
                modifier = Modifier.fillMaxWidth(),
                enabled = model.canPreview,
                border = outlinedButtonBorder(model.canPreview),
            ) { Text("Show the forecast") }
            model.previewLine?.let { line ->
                Text(
                    line,
                    Modifier.padding(horizontal = 8.dp),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(actions::turnOffWeather, Modifier.fillMaxWidth(), enabled = model.canTurnOff) {
                Text("Turn off weather")
            }
        }
    }
}

@Composable
private fun PlaceRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = PICKER_MARGIN),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private val PICKER_MARGIN = 8.dp
