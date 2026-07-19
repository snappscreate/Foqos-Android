package com.foqos.android.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.db.AppDatabase
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSchedule
import com.foqos.android.data.model.PhysicalUnblockItem
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.model.Weekday
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.domain.strategy.NfcManualBlockingStrategy
import com.foqos.android.service.alarm.FoqosAlarmScheduler
import com.foqos.android.service.nfc.NfcForegroundDispatchManager
import com.foqos.android.ui.navigation.Routes
import com.foqos.android.ui.theme.FoqosTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose render smoke test: every top-level section of a new [ProfileEditScreen] renders.
 *
 * Uses a tall Robolectric viewport (`qualifiers`) so the [StrategyPickerSheet]'s `LazyColumn`
 * renders every row without needing test-time scrolling (see the strategy-picker test below).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h3000dp")
class ProfileEditScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun awaitLoaded(viewModel: ProfileEditViewModel) {
        var attempts = 0
        while (viewModel.uiState.value.loading && attempts < 400) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
            attempts++
        }
    }

    @Test
    fun `new profile editor renders its sections`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)
        val viewModel = ProfileEditViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.ARG_PROFILE_ID to null)),
            profileRepository = profileRepository,
            nfcDispatchManager = NfcForegroundDispatchManager(),
            alarmScheduler = FoqosAlarmScheduler(context),
            sessionManager = com.foqos.android.testutil.buildTestSessionManager(context, db, profileRepository),
            context = context,
        )

        awaitLoaded(viewModel)

        composeTestRule.setContent {
            FoqosTheme {
                ProfileEditScreen(viewModel = viewModel, onDone = {}, onOpenAppPicker = {})
            }
        }

        composeTestRule.onNodeWithText("Blocking strategy").assertExists()
        composeTestRule.onNodeWithText("Apps").assertExists()
        composeTestRule.onNodeWithText("Websites").assertExists()
        composeTestRule.onNodeWithText("Schedule").assertExists()
        composeTestRule.onNodeWithText("Breaks").assertExists()
        composeTestRule.onNodeWithText("Session protection").assertExists()
    }

    /**
     * Regression test for the field crash-loop found on a Samsung Galaxy S21: opening an
     * *existing* profile (edit mode, `isPersisted == true`) renders [PhysicalUnlockEditor] inside
     * [ProfileEditScreen]'s `Modifier.verticalScroll` column. Before the fix,
     * `PhysicalUnlockEditorContent` used a `LazyColumn` for its (non-empty) item list, and a
     * `LazyColumn` nested in a vertically-scrollable parent throws
     * `IllegalStateException: Vertically scrollable component was measured with an infinity
     * maximum height constraints` as soon as Compose tries to lay it out - which is exactly what
     * this test exercises by seeding a saved profile with physical unlock items, a configured
     * schedule, and breaks enabled so every section is expanded and non-trivial. The prior
     * emulator walkthroughs only covered *create* mode (where this section is hidden entirely),
     * which is why this path was missed.
     *
     * Confirmed this fails on the pre-fix code: temporarily reverting `PhysicalUnlockEditor.kt`'s
     * `Column`+`forEach` back to the original `LazyColumn` (with this test otherwise unchanged)
     * reproduces the exact `IllegalStateException` from the field logcat, thrown out of Compose's
     * layout pass; restoring the fix makes it pass again.
     */
    @Test
    fun `existing profile editor with physical unlocks renders in edit mode without crashing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)

        val savedProfile = BlockedProfile(
            id = "saved-profile-1",
            name = "Deep Work",
            strategyId = NfcManualBlockingStrategy.ID,
            enableBreaks = true,
            breakTimeInMinutes = 15,
            physicalUnblockItems = listOf(
                PhysicalUnblockItem(name = "Desk tag", type = PhysicalUnblockType.NFC, codeValue = "foqos://profile/1"),
                PhysicalUnblockItem(name = "Kitchen tag", type = PhysicalUnblockType.NFC, codeValue = "foqos://profile/2"),
                PhysicalUnblockItem(name = "Fridge code", type = PhysicalUnblockType.QR_CODE, codeValue = "foqos://profile/3"),
            ),
            schedule = BlockedProfileSchedule(
                days = listOf(Weekday.MONDAY, Weekday.WEDNESDAY, Weekday.FRIDAY),
                startHour = 9,
                startMinute = 0,
                endHour = 17,
                endMinute = 0,
            ),
        )
        runBlocking { profileRepository.upsert(savedProfile) }

        val viewModel = ProfileEditViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.ARG_PROFILE_ID to savedProfile.id)),
            profileRepository = profileRepository,
            nfcDispatchManager = NfcForegroundDispatchManager(),
            alarmScheduler = FoqosAlarmScheduler(context),
            sessionManager = com.foqos.android.testutil.buildTestSessionManager(context, db, profileRepository),
            context = context,
        )

        awaitLoaded(viewModel)
        check(viewModel.uiState.value.isPersisted) { "Test setup failed: profile did not load as persisted/edit mode" }

        // This is the crash reproduction: rendering must not throw.
        composeTestRule.setContent {
            FoqosTheme {
                ProfileEditScreen(viewModel = viewModel, onDone = {}, onOpenAppPicker = {})
            }
        }

        composeTestRule.onNodeWithText("Physical unlocks").assertExists()
        composeTestRule.onNodeWithText("Desk tag").assertExists()
        composeTestRule.onNodeWithText("Kitchen tag").assertExists()
        composeTestRule.onNodeWithText("Fridge code").assertExists()
        composeTestRule.onNodeWithText("Schedule").assertExists()
        composeTestRule.onNodeWithText("Breaks").assertExists()
    }

    /**
     * Opens the redesigned [StrategyPickerSheet] from an *edit-mode* [ProfileEditScreen] (the
     * profile editor's `verticalScroll` column is still on screen behind the sheet, same as the
     * crash-reproduction test above) and checks the new mechanism-grouped layout renders: 3 group
     * headers (not the old 5-category list) plus a "Recommended" chip. Clicking the editor's own
     * strategy card (in the *main* compose root, not a popup) to open the sheet is exercised here;
     * clicking a row *inside* the sheet is not - `ModalBottomSheet` content renders in its own
     * window, and Robolectric's touch-simulation across windows is unreliable in this project's
     * current dependency versions (see `StrategyPickerSheetTest`'s kdoc). Strategy selection itself
     * is covered directly in `ProfileEditViewModelTest`.
     */
    @Test
    fun `strategy picker opens over the edit-mode editor with the redesigned mechanism groups`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)
        val savedProfile = BlockedProfile(id = "saved-profile-2", name = "Reading", strategyId = com.foqos.android.domain.strategy.ManualBlockingStrategy.ID)
        runBlocking { profileRepository.upsert(savedProfile) }

        val viewModel = ProfileEditViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.ARG_PROFILE_ID to savedProfile.id)),
            profileRepository = profileRepository,
            nfcDispatchManager = NfcForegroundDispatchManager(),
            alarmScheduler = FoqosAlarmScheduler(context),
            sessionManager = com.foqos.android.testutil.buildTestSessionManager(context, db, profileRepository),
            context = context,
        )
        awaitLoaded(viewModel)

        composeTestRule.setContent {
            FoqosTheme {
                ProfileEditScreen(viewModel = viewModel, onDone = {}, onOpenAppPicker = {})
            }
        }

        composeTestRule.onNodeWithText("Manual").performClick()

        composeTestRule.onNodeWithText("How do you want to start and stop focus sessions?").assertExists()
        composeTestRule.onNodeWithText("Start & stop in the app").assertExists()
        composeTestRule.onNodeWithText("NFC tag").assertExists()
        composeTestRule.onNodeWithText("QR code").assertExists()
        composeTestRule.onNodeWithText("NFC + Manual").assertExists()
        composeTestRule.onAllNodesWithText("Recommended").assertCountEquals(2)
    }
}
