package com.foqos.android.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.db.AppDatabase
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.data.repository.SessionRepository
import com.foqos.android.domain.strategy.ManualBlockingStrategy
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
 * Compose render smoke test for [ExportScreen] - part of the LazyColumn/verticalScroll audit
 * (finding 1): its `LazyColumn` uses `Modifier.weight(1f)` inside a non-scrollable `Column`, so it
 * is explicitly bounded and safe. This test guards against a future regression that wraps this
 * screen's content in a `verticalScroll` parent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportScreenTest {

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
    fun `export screen renders its profile list without crashing`() {
        val profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)
        val sessionRepository = SessionRepository(db.blockedProfileSessionDao(), Dispatchers.Unconfined)
        runBlocking {
            profileRepository.create(BlockedProfile(id = "p1", name = "Deep Work", strategyId = ManualBlockingStrategy.ID))
        }
        val viewModel = ExportViewModel(profileRepository, sessionRepository)

        var attempts = 0
        while (viewModel.uiState.value.profiles.isEmpty() && attempts < 400) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
            attempts++
        }

        composeTestRule.setContent {
            FoqosTheme {
                ExportScreen(onBack = {}, viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithText("Deep Work").assertExists()
    }
}
