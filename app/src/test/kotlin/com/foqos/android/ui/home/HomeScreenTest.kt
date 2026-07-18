package com.foqos.android.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.data.db.AppDatabase
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.data.repository.SessionRepository
import com.foqos.android.data.repository.SoftUnblockRepository
import com.foqos.android.domain.session.NoOpSessionServiceController
import com.foqos.android.domain.session.NoOpWidgetRefresher
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.service.alarm.FoqosAlarmScheduler
import com.foqos.android.service.session.BlockingStateHolder
import com.foqos.android.ui.theme.FoqosTheme
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose render smoke test: [HomeScreen] shows the empty state when no profiles are stored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeScreenTest {

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
    fun `empty state is shown when there are no profiles`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val profileRepository = ProfileRepository(db.blockedProfileDao(), Dispatchers.Unconfined)
        val sessionRepository = SessionRepository(db.blockedProfileSessionDao(), Dispatchers.Unconfined)
        val softUnblockRepository = SoftUnblockRepository(db, db.softUnblockDao(), Dispatchers.Unconfined)
        val settingsDataStore = SettingsDataStore(context)
        val sessionManager = SessionManager(
            profileRepository = profileRepository,
            sessionRepository = sessionRepository,
            softUnblockRepository = softUnblockRepository,
            blockingStateHolder = BlockingStateHolder(),
            alarmScheduler = FoqosAlarmScheduler(context),
            settingsDataStore = settingsDataStore,
            ioDispatcher = Dispatchers.Unconfined,
            sessionServiceController = NoOpSessionServiceController(),
            widgetRefresher = NoOpWidgetRefresher(),
        )
        val viewModel = HomeViewModel(profileRepository, sessionRepository, sessionManager, settingsDataStore, context)

        composeTestRule.setContent {
            FoqosTheme {
                HomeScreen(
                    onCreateProfile = {},
                    onEditProfile = {},
                    onOpenInsights = {},
                    onOpenSettings = {},
                    onOpenActiveSession = {},
                    viewModel = viewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("No focus profiles yet").assertExists()
    }
}
