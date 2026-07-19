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

/**
 * Compose render smoke test for [AppPickerScreen] - part of the LazyColumn/verticalScroll audit
 * (finding 1): its `LazyColumn` sits directly under a plain (non-scrollable) `Column`, so it is
 * bounded and safe, unlike the `PhysicalUnlockEditor` bug. This test guards against a future
 * regression that wraps this screen's content in a `verticalScroll` parent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppPickerScreenTest {

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
    fun `app picker renders without crashing`() {
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

        var attempts = 0
        while (viewModel.uiState.value.loading && attempts < 400) {
            Thread.sleep(5)
            attempts++
        }

        composeTestRule.setContent {
            FoqosTheme {
                AppPickerScreen(viewModel = viewModel, onDone = {})
            }
        }

        composeTestRule.onNodeWithText("Allow mode").assertExists()
    }
}
