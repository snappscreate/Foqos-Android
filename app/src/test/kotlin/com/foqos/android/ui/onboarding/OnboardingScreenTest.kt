package com.foqos.android.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.foqos.android.ui.theme.FoqosTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose render smoke test: [OnboardingScreen]'s welcome page renders and paging advances through all 3 pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `welcome page renders and next advances to features then permissions`() {
        var finished = false
        composeTestRule.setContent {
            FoqosTheme {
                OnboardingScreen(onFinished = { finished = true })
            }
        }

        composeTestRule.onNodeWithText("Focus with intention").assertExists()

        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.onNodeWithText("What Foqos can do").assertExists()

        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.onNodeWithText("Set up permissions").assertExists()

        composeTestRule.onNodeWithText("Get started").performClick()
        assert(finished)
    }
}
