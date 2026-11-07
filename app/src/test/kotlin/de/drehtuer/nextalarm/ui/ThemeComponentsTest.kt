package de.drehtuer.nextalarm.ui

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.SwitchColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import de.drehtuer.nextalarm.ui.theme.DarkColors
import de.drehtuer.nextalarm.ui.theme.LightColors
import de.drehtuer.nextalarm.ui.theme.NextAlarmTheme
import de.drehtuer.nextalarm.ui.theme.filledButtonColors
import de.drehtuer.nextalarm.ui.theme.nextAlarmRadioColors
import de.drehtuer.nextalarm.ui.theme.nextAlarmSwitchColors
import de.drehtuer.nextalarm.ui.theme.outlinedButtonBorder
import de.drehtuer.nextalarm.ui.theme.tonalButtonColors
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The colours docs/design.md gives these components, instead of Material's defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ThemeComponentsTest {
    @get:Rule
    val rule = createComposeRule()

    private class Seen(
        val scheme: ColorScheme,
        val filled: ButtonColors,
        val tonal: ButtonColors,
        val border: BorderStroke,
        val disabledBorder: BorderStroke,
        val switch: SwitchColors,
        val radio: RadioButtonColors,
    )

    private fun seen(dark: Boolean): Seen {
        lateinit var seen: Seen
        rule.setContent {
            NextAlarmTheme(dark = dark) {
                seen = Seen(
                    MaterialTheme.colorScheme,
                    filledButtonColors(),
                    tonalButtonColors(),
                    outlinedButtonBorder(),
                    outlinedButtonBorder(enabled = false),
                    nextAlarmSwitchColors(),
                    nextAlarmRadioColors(),
                )
            }
        }
        rule.waitForIdle()
        return seen
    }

    private fun assertDesignColours(seen: Seen, expected: ColorScheme) {
        assertEquals(expected.primary, seen.scheme.primary)
        assertEquals(expected.primary, seen.filled.containerColor)
        assertEquals(expected.surfaceContainerHigh, seen.filled.disabledContainerColor)
        assertEquals(expected.primaryContainer, seen.tonal.containerColor)
        assertEquals(expected.onPrimaryContainer, seen.tonal.contentColor)
        assertEquals(expected.surfaceContainerHigh, seen.tonal.disabledContainerColor)
        assertEquals(1.dp, seen.border.width)
        assertEquals(SolidColor(expected.outline), seen.border.brush)
        assertEquals(SolidColor(expected.outline.copy(alpha = 0.38f)), seen.disabledBorder.brush)
        assertEquals(expected.surfaceContainerHigh, seen.switch.uncheckedTrackColor)
        assertEquals(expected.outline, seen.switch.uncheckedThumbColor)
        assertEquals(expected.outline, seen.switch.uncheckedBorderColor)
        assertEquals(expected.primary, seen.switch.checkedTrackColor)
        assertEquals(expected.outline, seen.radio.unselectedColor)
        assertEquals(expected.primary, seen.radio.selectedColor)
    }

    @Test
    fun `the light components have the design's colours`() = assertDesignColours(seen(dark = false), LightColors)

    @Test
    fun `the dark components have the design's colours`() = assertDesignColours(seen(dark = true), DarkColors)

    @Test
    fun `a tonal button is not in the colour of warnings, and a switch is neutral`() {
        val seen = seen(dark = false)
        assertEquals(Color(0xFFCFE5FF), seen.tonal.containerColor)
        assertEquals(Color(0xFFE4E4E4), seen.switch.uncheckedTrackColor)
    }
}
