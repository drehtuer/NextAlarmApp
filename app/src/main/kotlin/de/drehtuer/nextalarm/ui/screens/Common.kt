package de.drehtuer.nextalarm.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.R

@Composable
fun SymbolIcon(@DrawableRes icon: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(painterResource(icon), contentDescription, modifier, tint)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextAlarmTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { SymbolIcon(R.drawable.ic_back, "Back") }
            }
        },
        actions = { actions() },
    )
}

/** A warning or error card. The colours say how serious it is. */
@Composable
fun Banner(
    title: String?,
    body: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    action: @Composable () -> Unit = {},
) {
    Surface(modifier.fillMaxWidth(), MaterialTheme.shapes.large, container, content) {
        Row(Modifier.padding(16.dp), Arrangement.spacedBy(12.dp), Alignment.Top) {
            if (icon != null) SymbolIcon(icon, null, tint = content)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.labelLarge)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                action()
            }
        }
    }
}
