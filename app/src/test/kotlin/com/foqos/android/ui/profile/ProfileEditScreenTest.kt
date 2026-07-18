package com.foqos.android.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.db.AppDatabase
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.service.alarm.FoqosAlarmScheduler
import com.foqos.android.service.nfc.NfcForegroundDispatchManager
import com.foqos.android.ui.navigation.Routes
import com.foqos.android.ui.theme.FoqosTheme
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose render smoke test: every top-level section of a new [ProfileEditScreen] renders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    @Test
    fun `new profile editor renders its sections`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)
        val viewModel = ProfileEditViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Routes.ARG_PROFILE_ID to null)),
            profileRepository = profileRepository,
            nfcDispatchManager = NfcForegroundDispatchManager(),
            alarmScheduler = FoqosAlarmScheduler(context),
            context = context,
        )

        var attempts = 0
        while (viewModel.uiState.value.loading && attempts < 400) {
            Thread.sleep(5)
            attempts++
        }

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
}
