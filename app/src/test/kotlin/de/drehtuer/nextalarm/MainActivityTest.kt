package de.drehtuer.nextalarm

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The whole app on its real parts. There is no real calendar, and setup has not been done. */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `a new install starts with setup`() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodes(hasText("Your calendar sets the alarms")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get started").assertIsDisplayed()
    }

    @Test
    fun `the activity stops watching in the background and can come back`() {
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodes(hasText("Get started")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
