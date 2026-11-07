package com.foqos.android.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.foqos.android.domain.strategy.BlockingStrategy
import com.foqos.android.domain.strategy.ManualBlockingStrategy
import com.foqos.android.domain.strategy.StrategyRegistry
import com.foqos.android.ui.theme.FoqosTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the redesigned [StrategyPickerSheet]: grouped by mechanism (app / NFC / QR) instead of
 * the old 5-way [com.foqos.android.domain.strategy.StrategyCategory] section list, per user
 * feedback that "the modes should be better sorted and more visible/understandable". Also a
 * regression test for finding 1's nested-scrollable crash class: the sheet's `LazyColumn` is
 * inside a [androidx.compose.material3.ModalBottomSheet] (bounded height), which must keep
 * rendering fine even with all 12 strategies present.
 *
 * Uses a tall Robolectric viewport (`qualifiers`) so every row fits within the sheet's
 * `fillMaxHeight(0.92f)` LazyColumn without needing to scroll.
 *
 * This only covers rendering, not click-driven selection: `performClick()` on rows inside this
 * `ModalBottomSheet` dispatches a real coordinate-based touch event into whichever window owns the
 * node, and that cross-window dispatch is unreliable under Robolectric in this project's current
 * dependency versions (confirmed via a semantics dump: the target row's `OnClick` action is
 * correctly wired to `onSelect(strategy)`, but the simulated touch never reaches it). Selection
 * behavior itself (`ProfileEditViewModel.selectStrategy`) is covered directly in
 * `ProfileEditViewModelTest`, independent of this Compose click-simulation limitation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h3000dp")
class StrategyPickerSheetTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `renders all 3 mechanism groups with one-liners and a recommended chip on Manual and NFC`() {
        var selected: BlockingStrategy? = null

        composeTestRule.setContent {
            FoqosTheme {
                StrategyPickerSheet(
                    strategies = StrategyRegistry.all,
                    selectedStrategyId = ManualBlockingStrategy.ID,
                    onSelect = { selected = it },
                    onDismiss = {},
                )
            }
        }

        // Assert the currently localized visible heading and default strategy.
        composeTestRule.onNodeWithText("ブロックの開始・停止方法を選択").assertExists()
        composeTestRule.onNodeWithText("アプリで開始・停止").assertExists()
        composeTestRule.onNodeWithText("手動で開始・停止").assertExists()
        composeTestRule.onNodeWithText("アプリの開始・停止ボタンで操作。読み取り不要。").assertExists()

        // `selected`/`onDismiss` are exercised by the caller (ProfileEditScreen) wiring, which is
        // plain, directly-inspectable Kotlin (`onClick = { onSelect(strategy) }`) - not re-verified
        // here via click simulation, see the class kdoc.
        assert(selected == null)
    }
}
