package com.foqos.android.testutil

import android.content.Context
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
import kotlinx.coroutines.Dispatchers

/**
 * Builds a real, Robolectric-backed [SessionManager] for tests that need one as a collaborator
 * (e.g. UI ViewModels). [SessionManager] isn't designed to be mocked, so tests use the real thing
 * with no-op service/widget side effects - the same pattern as `SessionManagerTest`.
 */
fun buildTestSessionManager(
    context: Context,
    db: AppDatabase,
    profileRepository: ProfileRepository,
    sessionRepository: SessionRepository = SessionRepository(db.blockedProfileSessionDao(), Dispatchers.Unconfined),
): SessionManager = SessionManager(
    profileRepository = profileRepository,
    sessionRepository = sessionRepository,
    softUnblockRepository = SoftUnblockRepository(db, db.softUnblockDao(), Dispatchers.Unconfined),
    blockingStateHolder = BlockingStateHolder(),
    alarmScheduler = FoqosAlarmScheduler(context),
    settingsDataStore = SettingsDataStore(context),
    ioDispatcher = Dispatchers.Unconfined,
    sessionServiceController = NoOpSessionServiceController(),
    widgetRefresher = NoOpWidgetRefresher(),
)
