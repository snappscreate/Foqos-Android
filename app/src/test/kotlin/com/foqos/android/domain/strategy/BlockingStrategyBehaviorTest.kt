package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.PhysicalUnblockItem
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.model.StrategyPauseTimerData
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Behavior tests for the trickier strategy flows: same-tag stop validation, physical-unlock
 * overrides, force-started bypass, and pause -> stop sequencing.
 */
class BlockingStrategyBehaviorTest {

    private fun profile(
        strategyId: String,
        physicalUnblockItems: List<PhysicalUnblockItem> = emptyList(),
        strategyDataJson: String? = null,
    ) = BlockedProfile(
        id = "profile-1",
        name = "Focus",
        strategyId = strategyId,
        strategyDataJson = strategyDataJson,
        physicalUnblockItems = physicalUnblockItems,
    )

    @Test
    fun `NFC strategy rejects a stop scan that does not match the starting tag`() = runBlocking {
        val strategy = NfcBlockingStrategy()
        val host = FakeStrategyHost()
        val profile = profile(strategyId = strategy.id)

        val started = strategy.onStartInput(profile, StrategyInput.Code("TAG1", StrategyInput.CodeSource.NFC), host)
        val session = (started as SessionOutcome.Started).session
        assertThat(session.tag).isEqualTo("TAG1")

        val wrongTag = strategy.onStopInput(profile, session, StrategyInput.Code("TAG2", StrategyInput.CodeSource.NFC), host)
        assertThat(wrongTag).isInstanceOf(SessionOutcome.Rejected::class.java)
        assertThat((wrongTag as SessionOutcome.Rejected).reason).isEqualTo("You must scan the original tag to stop focus")
        assertThat(host.endedSessions).isEmpty()

        val sameTag = strategy.onStopInput(profile, session, StrategyInput.Code("TAG1", StrategyInput.CodeSource.NFC), host)
        assertThat(sameTag).isEqualTo(SessionOutcome.Stopped)
        assertThat(host.endedSessions).hasSize(1)
    }

    @Test
    fun `physical unblock item overrides the same-tag requirement`() = runBlocking {
        val strategy = NfcBlockingStrategy()
        val host = FakeStrategyHost()
        val physicalItem = PhysicalUnblockItem(name = "Front door tag", type = PhysicalUnblockType.NFC, codeValue = "AllowedTag")
        val profile = profile(strategyId = strategy.id, physicalUnblockItems = listOf(physicalItem))

        val started = strategy.onStartInput(profile, StrategyInput.Code("TAG1", StrategyInput.CodeSource.NFC), host)
        val session = (started as SessionOutcome.Started).session

        // Neither the original starting tag nor an arbitrary tag works once a physical item is configured...
        val wrongCode = strategy.onStopInput(profile, session, StrategyInput.Code("TAG1", StrategyInput.CodeSource.NFC), host)
        assertThat(wrongCode).isInstanceOf(SessionOutcome.Rejected::class.java)

        // ...only the configured physical item's code does, even though it differs from the starting tag.
        val allowedCode = strategy.onStopInput(profile, session, StrategyInput.Code("AllowedTag", StrategyInput.CodeSource.NFC), host)
        assertThat(allowedCode).isEqualTo(SessionOutcome.Stopped)
    }

    @Test
    fun `force-started session accepts any code to stop`() = runBlocking {
        val strategy = NfcBlockingStrategy()
        val host = FakeStrategyHost()
        val profile = profile(strategyId = strategy.id)
        val forceStartedSession = com.foqos.android.data.model.BlockedProfileSession(
            id = "s1",
            profileId = profile.id,
            tag = "original-tag",
            startTime = Instant.now(),
            forceStarted = true,
        )

        val outcome = strategy.onStopInput(
            profile,
            forceStartedSession,
            StrategyInput.Code("totally-different-code", StrategyInput.CodeSource.NFC),
            host,
        )

        assertThat(outcome).isEqualTo(SessionOutcome.Stopped)
        assertThat(host.endedSessions).hasSize(1)
    }

    @Test
    fun `NFC pause timer never fully stops even when scanned during a pause`() = runBlocking {
        val strategy = NfcPauseTimerBlockingStrategy()
        val host = FakeStrategyHost()
        val pauseData = StrategyPauseTimerData(pauseDurationInMinutes = 20)
        val profile = profile(strategyId = strategy.id, strategyDataJson = pauseData.toJson())

        val started = strategy.onStartInput(profile, StrategyInput.PauseData(pauseData), host)
        val session = (started as SessionOutcome.Started).session
        assertThat(session.isPauseActive()).isFalse()

        val firstScan = strategy.onStopInput(profile, session, StrategyInput.Code("tag", StrategyInput.CodeSource.NFC), host)
        assertThat(firstScan).isEqualTo(SessionOutcome.Paused)
        assertThat(host.pauseCalls).hasSize(1)
        assertThat(host.pauseCalls.single().second).isEqualTo(20)
        assertThat(host.endedSessions).isEmpty()

        // Simulate the SessionManager having recorded the pause start.
        val pausedSession = session.copy(pauseStartTime = Instant.now())

        val secondScan = strategy.onStopInput(profile, pausedSession, StrategyInput.Code("tag", StrategyInput.CodeSource.NFC), host)
        assertThat(secondScan).isInstanceOf(SessionOutcome.Rejected::class.java)
        assertThat(host.endedSessions).isEmpty()
    }

    @Test
    fun `soft-unblock strategy starts allowance tracking and stops on scan`() = runBlocking {
        val strategy = NfcSoftUnblockBlockingStrategy()
        val host = FakeStrategyHost()
        val profile = profile(strategyId = strategy.id)
        val data = com.foqos.android.data.model.SoftUnblockStrategyData(
            accessDurationInMinutes = 10,
            maximumUnblockCount = 3,
            allowanceResetIntervalInHours = null,
        )

        val started = strategy.onStartInput(profile, StrategyInput.SoftUnblockData(data), host)
        val session = (started as SessionOutcome.Started).session
        assertThat(host.softUnblockCalls).hasSize(1)
        assertThat(host.softUnblockCalls.single().second).isEqualTo(data)

        val stopped = strategy.onStopInput(profile, session, StrategyInput.Code("tag", StrategyInput.CodeSource.NFC), host)
        assertThat(stopped).isEqualTo(SessionOutcome.Stopped)
        assertThat(strategy.allowsTimedBreaks).isFalse()
    }
}
