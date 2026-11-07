package de.drehtuer.nextalarm.ui

import android.app.Application
import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.drehtuer.nextalarm.domain.AlarmScreenModel
import de.drehtuer.nextalarm.ui.screens.AlarmScreen
import de.drehtuer.nextalarm.ui.theme.DarkColors
import de.drehtuer.nextalarm.ui.theme.DarkExtraColors
import de.drehtuer.nextalarm.ui.theme.LocalExtraColors
import de.drehtuer.nextalarm.ui.theme.NextAlarmShapes
import de.drehtuer.nextalarm.ui.theme.NextAlarmTypography
import de.drehtuer.nextalarm.ui.theme.NightColors
import de.drehtuer.nextalarm.ui.theme.NightTheme
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val NIGHT_SURFACE = Color(0xFF000000)
private val NIGHT_ON_SURFACE = Color(0xFFE6E6E6)
private val NIGHT_ON_SURFACE_VARIANT = Color(0xFFA8A8A8)
private val NIGHT_SURFACE_CONTAINER = Color(0xFF1A1A1A)
private val NIGHT_OUTLINE = Color(0xFF474747)
private val NIGHT_PRIMARY = Color(0xFF99CBFF)
private val NIGHT_ON_PRIMARY = Color(0xFF003354)

/** "06:00" in `displayLarge`, in pixels at the density of these tests. */
private const val ROBOTO_WIDTH_OF_FIVE_AT_112_SP = 271f

private const val NO_TITLE = "Alarm (event has no title)"
private val VERY_LONG_TITLE = "Flight to Hamburg, leave at 6:15 and do not forget the passport. ".repeat(40).trim()

/** Native graphics, because only then is text measured with real fonts. The fit checks would mean nothing otherwise. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class AlarmScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun model(
        titles: List<String?> = listOf("Flight"),
        time: String = "06:00",
        countLine: String? = null,
        ringsAgain: String = "Rings again at 06:10",
    ) = AlarmScreenModel("Saturday 7 November", time, countLine, titles, ringsAgain)

    private fun show(
        model: AlarmScreenModel,
        fontScale: Float = 1f,
        onSnooze: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                NightTheme { AlarmScreen(model, onSnooze, onDismiss) }
            }
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun text(value: String) = rule.onNodeWithText(value, useUnmergedTree = true).layout().layoutInput.style

    private fun lineWidth(value: String) = rule.onNodeWithText(value).layout().run { getLineRight(0) - getLineLeft(0) }

    /** At 112 sp a time with seconds would be about 420 dp wide, so this also fails if it does not shrink. */
    private fun assertFitsOnOneLine(time: String) {
        val layout = rule.onNodeWithText(time).layout()
        assertEquals(1, layout.lineCount, "$time wraps")
        assertFalse(layout.hasVisualOverflow, "$time is clipped")
        assertTrue(lineWidth(time) <= layout.size.width, "$time is wider than its space")
    }

    /** Text that sizes itself is as high as its font says, so the line shows in where its middle is. */
    private fun assertClockIsOnA120DpLine(time: String) {
        val date = rule.onNodeWithText("Saturday 7 November").getUnclippedBoundsInRoot()
        val clock = rule.onNodeWithText(time).getUnclippedBoundsInRoot()
        assertEquals(60f, ((clock.top + clock.bottom) / 2 - date.bottom).value, 0.5f, "the middle of $time")
    }

    private fun assertButtonsAreOnScreen() {
        val root = rule.onRoot().getBoundsInRoot()
        for (button in listOf("Snooze", "Dismiss")) {
            val bounds = rule.onNodeWithText(button).assertIsDisplayed().getBoundsInRoot()
            assertTrue(bounds.bottom <= root.bottom, "$button ends at ${bounds.bottom}, below the screen at ${root.bottom}")
        }
    }

    @Test
    fun `it shows the date, the time, the title and both buttons`() {
        show(model())
        listOf("Saturday 7 November", "06:00", "Flight", "Snooze", "Rings again at 06:10", "Dismiss").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun `snooze and dismiss call back and nothing else`() {
        var snoozed = 0
        var dismissed = 0
        show(model(), onSnooze = { snoozed++ }, onDismiss = { dismissed++ })
        rule.onNodeWithText("Snooze").performClick()
        assertEquals(1 to 0, snoozed to dismissed)
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(1 to 1, snoozed to dismissed)
    }

    @Test
    fun `the buttons have the size and place of the design`() {
        show(model())
        val root = rule.onRoot().getBoundsInRoot()
        val snooze = rule.onNodeWithText("Snooze")
            .assertHeightIsEqualTo(112.dp)
            .assertWidthIsEqualTo(363.dp)
            .assertLeftPositionInRootIsEqualTo(24.dp)
        val dismiss = rule.onNodeWithText("Dismiss")
            .assertHeightIsEqualTo(80.dp)
            .assertWidthIsEqualTo(363.dp)
            .assertLeftPositionInRootIsEqualTo(24.dp)
        assertEquals(48.dp, dismiss.getBoundsInRoot().top - snooze.getBoundsInRoot().bottom)
        assertEquals(24.dp, root.bottom - dismiss.getBoundsInRoot().bottom)
    }

    @Test
    fun `the content starts 48 dp below the top`() {
        show(model())
        rule.onNodeWithText("Saturday 7 November").assertTopPositionInRootIsEqualTo(48.dp)
    }

    @Test
    fun `the content stays inside the window insets, and the black goes behind them`() {
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            NightTheme { AlarmScreen(model(), {}, {}) }
        }
        rule.runOnUiThread {
            view.dispatchApplyWindowInsets(
                WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 58, 0, 24))
                    .setVisible(WindowInsets.Type.systemBars(), true)
                    .build(),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Saturday 7 November").assertTopPositionInRootIsEqualTo(106.dp)
        val root = rule.onRoot().getBoundsInRoot()
        assertEquals(48.dp, root.bottom - rule.onNodeWithText("Dismiss").getBoundsInRoot().bottom)
        val image = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(NIGHT_SURFACE, image[image.width / 2, 2])
        assertEquals(NIGHT_SURFACE, image[image.width / 2, image.height - 2])
    }

    @Test
    fun `the time has the largest size when it fits`() {
        show(model())
        val style = text("06:00")
        assertEquals(NIGHT_ON_SURFACE, style.color)
        assertEquals("tnum", style.fontFeatureSettings)
        assertClockIsOnA120DpLine("06:00")
        assertFitsOnOneLine("06:00")
        // The layout keeps reporting the size it was asked for, so the size that was chosen shows in the width.
        val width = lineWidth("06:00")
        assertTrue(width > ROBOTO_WIDTH_OF_FIVE_AT_112_SP - 1 && width < ROBOTO_WIDTH_OF_FIVE_AT_112_SP + 1, "$width wide")
    }

    @Test
    fun `a time with seconds shrinks to fit on one line`() {
        show(model(time = "06:00:30"))
        assertClockIsOnA120DpLine("06:00:30")
        assertFitsOnOneLine("06:00:30")
    }

    @Test
    fun `the widest time with seconds fits too`() {
        show(model(time = "20:08:08"))
        assertFitsOnOneLine("20:08:08")
    }

    @Test
    fun `the time fits at a very large font scale`() {
        show(model(time = "06:00:30"), fontScale = 2f)
        assertFitsOnOneLine("06:00:30")
    }

    @Test
    fun `one title is a medium headline`() {
        show(model(listOf("Flight")))
        val style = text("Flight")
        assertEquals(32.sp, style.fontSize)
        assertEquals(40.sp, style.lineHeight)
        assertEquals(FontWeight.Medium, style.fontWeight)
        assertEquals(NIGHT_ON_SURFACE, style.color)
    }

    @Test
    fun `several titles have a count line and are small headlines`() {
        show(model(listOf("Flight", "Pills", "Taxi"), countLine = "3 alarms"))
        rule.onNodeWithText("3 alarms").assertIsDisplayed()
        assertEquals(NIGHT_ON_SURFACE_VARIANT, text("3 alarms").color)
        listOf("Flight", "Pills", "Taxi").forEach {
            val style = text(it)
            assertEquals(26.sp, style.fontSize)
            assertEquals(34.sp, style.lineHeight)
            assertEquals(FontWeight.Medium, style.fontWeight)
        }
    }

    @Test
    fun `a long title is smaller, alone or among others, and a title just below the limit is not`() {
        val long = "x ".repeat(31).trim()
        val limit = "y ".repeat(30).trim()
        assertEquals(61, long.length)
        assertEquals(59, limit.length)
        show(model(listOf(long, limit, "Pills"), countLine = "3 alarms"))
        assertEquals(24.sp, text(long).fontSize)
        assertEquals(32.sp, text(long).lineHeight)
        assertEquals(FontWeight.Medium, text(long).fontWeight)
        assertEquals(26.sp, text(limit).fontSize)
    }

    @Test
    fun `a single long title is smaller too`() {
        show(model(listOf(VERY_LONG_TITLE)))
        assertEquals(24.sp, text(VERY_LONG_TITLE).fontSize)
    }

    @Test
    fun `a missing title says so, in italics`() {
        show(model(listOf(null)))
        val style = text(NO_TITLE)
        assertEquals(FontStyle.Italic, style.fontStyle)
        assertEquals(NIGHT_ON_SURFACE_VARIANT, style.color)
        assertEquals(32.sp, style.fontSize)
    }

    @Test
    fun `a missing title among others has the size of the others`() {
        show(model(listOf(null, "Flight"), countLine = "2 alarms"))
        assertEquals(26.sp, text(NO_TITLE).fontSize)
        rule.onNodeWithText("Flight").assertIsDisplayed()
    }

    @Test
    fun `a very long title is not truncated and the buttons stay on screen`() {
        show(model(listOf(VERY_LONG_TITLE)))
        val layout = rule.onNodeWithText(VERY_LONG_TITLE).layout()
        assertFalse(layout.hasVisualOverflow, "the title is cut off")
        assertTrue(layout.lineCount > 20, "only ${layout.lineCount} lines")
        assertButtonsAreOnScreen()
        rule.onNodeWithText("Snooze").assertHeightIsEqualTo(112.dp)
        rule.onNodeWithText("Dismiss").assertHeightIsEqualTo(80.dp)
        rule.onNodeWithText("Saturday 7 November").assertIsDisplayed()
    }

    @Test
    fun `many titles scroll, one by one, while the clock and the buttons stay`() {
        val titles = (1..30).map { "Alarm number $it" }
        show(model(titles, countLine = "30 alarms"))
        rule.onNodeWithText("Alarm number 30").assertIsNotDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Alarm number 30"))
        rule.onNodeWithText("Alarm number 30").assertIsDisplayed()
        rule.onNodeWithText("Alarm number 1").assertIsNotDisplayed()
        rule.onNodeWithText("06:00").assertIsDisplayed()
        assertButtonsAreOnScreen()
    }

    @Test
    fun `the buttons stay on screen at a very large font scale`() {
        show(model(listOf(VERY_LONG_TITLE), time = "06:00:30"), fontScale = 2f)
        assertButtonsAreOnScreen()
        rule.onNodeWithText("Snooze").assertHeightIsAtLeast(112.dp)
        rule.onNodeWithText("Dismiss").assertHeightIsAtLeast(80.dp)
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun `the buttons stay on screen when the window is short and the font is very large`() {
        show(model(listOf(VERY_LONG_TITLE)), fontScale = 2f)
        assertButtonsAreOnScreen()
    }

    @Test
    fun `without a ringing alarm the line about ringing again is left out`() {
        show(model(emptyList(), ringsAgain = ""))
        rule.onNodeWithText("Snooze").assertIsDisplayed()
        rule.onNodeWithText("Rings again", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the colours are those of the night theme`() {
        show(model())
        val image = rule.onRoot().captureToImage().toPixelMap()
        val snooze = rule.onNodeWithText("Snooze").getBoundsInRoot()
        val dismiss = rule.onNodeWithText("Dismiss").getBoundsInRoot()
        val density = rule.density.density
        fun pixel(x: Float, y: Float) = image[(x * density).toInt(), (y * density).toInt()]
        assertEquals(NIGHT_SURFACE, pixel(4f, 4f))
        assertEquals(NIGHT_SURFACE_CONTAINER, pixel(snooze.left.value + 60f, snooze.top.value + 20f))
        assertEquals(NIGHT_OUTLINE, pixel(snooze.left.value + 180f, snooze.top.value + 1f))
        assertEquals(NIGHT_PRIMARY, pixel(dismiss.left.value + 60f, dismiss.top.value + 40f))
        assertEquals(NIGHT_ON_PRIMARY, text("Dismiss").color)
        assertEquals(NIGHT_ON_SURFACE, text("Snooze").color)
        assertEquals(NIGHT_ON_SURFACE_VARIANT, text("Rings again at 06:10").color)
        assertEquals(NIGHT_ON_SURFACE_VARIANT, text("Saturday 7 November").color)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h923dp")
class NightThemeTest {
    @get:Rule
    val rule = createComposeRule()

    private fun assertNightTheme() {
        var checked = false
        rule.setContent {
            NightTheme {
                assertEquals(NightColors, MaterialTheme.colorScheme)
                assertEquals(NextAlarmTypography, MaterialTheme.typography)
                assertEquals(NextAlarmShapes, MaterialTheme.shapes)
                assertEquals(DarkExtraColors, LocalExtraColors.current)
                checked = true
            }
        }
        rule.waitForIdle()
        assertTrue(checked)
    }

    @Test
    @Config(qualifiers = "w411dp-h923dp-notnight")
    fun `the night theme applies with the system in light mode`() = assertNightTheme()

    @Test
    @Config(qualifiers = "w411dp-h923dp-night")
    fun `the night theme applies with the system in dark mode`() = assertNightTheme()

    @Test
    fun `the night colours are those of the design`() {
        assertEquals(NIGHT_SURFACE, NightColors.background)
        assertEquals(NIGHT_SURFACE, NightColors.surface)
        assertEquals(NIGHT_ON_SURFACE, NightColors.onBackground)
        assertEquals(NIGHT_ON_SURFACE, NightColors.onSurface)
        assertEquals(NIGHT_ON_SURFACE_VARIANT, NightColors.onSurfaceVariant)
        assertEquals(NIGHT_SURFACE_CONTAINER, NightColors.surfaceContainer)
        assertEquals(NIGHT_OUTLINE, NightColors.outline)
        assertEquals(NIGHT_PRIMARY, NightColors.primary)
        assertEquals(NIGHT_ON_PRIMARY, NightColors.onPrimary)
    }

    @Test
    fun `the other roles come from the dark palette, not the Material baseline`() {
        assertEquals(DarkColors.secondary, NightColors.secondary)
        assertEquals(DarkColors.error, NightColors.error)
        assertEquals(DarkColors.primaryContainer, NightColors.primaryContainer)
    }
}
