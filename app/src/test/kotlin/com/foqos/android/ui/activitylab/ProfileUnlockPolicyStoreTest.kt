package com.foqos.android.ui.activitylab

import android.content.Context
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.PhysicalUnblockItem
import com.foqos.android.data.model.PhysicalUnblockType
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileUnlockPolicyStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun uniqueProfile() = UUID.randomUUID().toString()

    @Test fun multiPauseCheckboxesPersistCredentialsAndAllOffMeansManual() {
        val id = uniqueProfile()
        val store = ProfileUnlockPolicyStore(context)
        val nfc = PhysicalUnblockItem(id = "nfc-one", name = "NFC",
            type = PhysicalUnblockType.NFC, codeValue = "tag-one")
        val qr = PhysicalUnblockItem(id = "qr-one", name = "QR",
            type = PhysicalUnblockType.QR_CODE, codeValue = "qr-one")
        try {
            val settings = ProfileUnlockPolicy(
                pauseMethods = setOf(UnlockMethod.NFC, UnlockMethod.QR),
                pauseNfcCredentialId = nfc.id,
                pauseQrCredentialId = qr.id,
                pauseNfcMinutes = 15,
                pauseQrMinutes = 10,
            )
            store.save(id, settings)
            val restored = ProfileUnlockPolicyStore(context).read(id)
            assertThat(restored.enabledPauseMethods())
                .containsExactly(UnlockMethod.NFC, UnlockMethod.QR)
            assertThat(restored.pauseCredentialFor(UnlockMethod.NFC)).isEqualTo(nfc.id)
            assertThat(restored.pauseCredentialFor(UnlockMethod.QR)).isEqualTo(qr.id)
            assertThat(restored.pauseMinutesFor(UnlockMethod.NFC)).isEqualTo(15)
            assertThat(restored.pauseMinutesFor(UnlockMethod.QR)).isEqualTo(10)
            val profile = BlockedProfile(id = id, name = "Multi", strategyId = "manual",
                physicalUnblockItems = listOf(nfc, qr))
            assertThat(store.isReady(profile)).isTrue()
            assertThat(store.isReady(profile.copy(physicalUnblockItems = listOf(nfc)))).isFalse()
            store.save(id, settings.copy(pauseMethods = emptySet()))
            assertThat(store.read(id).enabledPauseMethods()).isEmpty()
            assertThat(store.isReady(profile.copy(physicalUnblockItems = emptyList()))).isTrue()
        } finally { store.delete(id) }
    }

    @Test fun stopAndPauseAreIndependent() {
        val id = uniqueProfile()
        val store = ProfileUnlockPolicyStore(context)
        assertThat(store.configured(id)).isFalse()
        store.save(id, ProfileUnlockPolicy(
            stop = UnlockMethod.QR, pause = UnlockMethod.NFC, pauseMinutes = 15,
        ))
        assertThat(ProfileUnlockPolicyStore(context).read(id)).isEqualTo(
            ProfileUnlockPolicy(UnlockMethod.QR, UnlockMethod.NFC, 15)
        )
        store.save(id, store.read(id).copy(pause = UnlockMethod.ACTIVITY))
        assertThat(store.read(id).stop).isEqualTo(UnlockMethod.QR)
        store.delete(id)
        assertThat(store.configured(id)).isFalse()
    }

    @Test fun selectedCredentialMustExistInThatProfile() {
        val id = uniqueProfile()
        val store = ProfileUnlockPolicyStore(context)
        val selected = PhysicalUnblockItem(id = "shared-nfc", name = "机のタグ",
            type = PhysicalUnblockType.NFC, codeValue = "test-nfc-code")
        store.save(id, ProfileUnlockPolicy(stop = UnlockMethod.NFC,
            stopCredentialId = selected.id))
        try {
            val profile = BlockedProfile(id = id, name = "Focus", strategyId = "manual")
            assertThat(store.isReady(profile)).isFalse()
            assertThat(store.isReady(profile.copy(physicalUnblockItems = listOf(selected)))).isTrue()
            assertThat(store.isReady(profile.copy(physicalUnblockItems = listOf(selected.copy(
                type = PhysicalUnblockType.QR_CODE))))).isFalse()
        } finally {
            store.delete(id)
        }
    }

    @Test fun savedMovementCanBeReusedAfterOriginalProfileIsDeleted() {
        val first = uniqueProfile()
        val second = uniqueProfile()
        val activities = ActivityUnlockStore(context)
        val item = ActivityUnlockCondition(description = "スクワット30回")
        activities.save(first, listOf(item))
        activities.deleteProfile(first)
        assertThat(activities.reusableConditions().any { it.id == item.id }).isTrue()
        activities.importCondition(second, item)
        activities.setMainCondition(second, item.id)
        ProfileUnlockPolicyStore(context).save(second, ProfileUnlockPolicy(pause = UnlockMethod.ACTIVITY))
        assertThat(activities.selection(second, "session")?.conditionId).isEqualTo(item.id)
        activities.deleteProfile(second)
        ProfileUnlockPolicyStore(context).delete(second)
    }

    @Test fun cooldownIsProfileScopedAndSurvivesStoreRecreation() {
        val first = uniqueProfile()
        val second = uniqueProfile()
        val store = ProfileUnlockPolicyStore(context)
        store.save(first, ProfileUnlockPolicy(retryCooldownMinutes = 90))
        store.save(second, ProfileUnlockPolicy(retryCooldownMinutes = 20))
        try {
            store.markPauseAuthorized(first, nowMillis = 1_000_000L)
            val fresh = ProfileUnlockPolicyStore(context)
            assertThat(fresh.pauseRetryRemainingMillis(first, nowMillis = 1_000_000L + 1_000L))
                .isEqualTo(90 * 60_000L - 1_000L)
            assertThat(fresh.pauseRetryRemainingMillis(second, nowMillis = 1_000_000L + 1_000L))
                .isEqualTo(0L)
            assertThat(fresh.pauseRetryRemainingMillis(first, nowMillis = 1_000_000L + 90 * 60_000L))
                .isEqualTo(0L)
        } finally {
            store.delete(first)
            store.delete(second)
        }
    }

    @Test fun validMovementSetCanBeStartedWithoutStandaloneMainMovement() {
        val id = uniqueProfile()
        val movement = ActivityUnlockCondition(description = "腕立て伏せ30回")
        val activities = ActivityUnlockStore(context)
        val sets = ActivityChallengeSetStore(context)
        val policy = ProfileUnlockPolicyStore(context)
        try {
            activities.save(id, listOf(movement))
            sets.save(id, listOf(ActivityChallengeSet(conditionIds = listOf(movement.id), pauseMinutes = 15)))
            policy.save(id, ProfileUnlockPolicy(pause = UnlockMethod.ACTIVITY))
            val profile = BlockedProfile(id = id, name = "Set profile", strategyId = "manual")
            assertThat(policy.isReady(profile)).isTrue()
            activities.save(id, emptyList())
            assertThat(policy.isReady(profile)).isFalse()
        } finally {
            sets.deleteProfile(id)
            activities.deleteProfile(id)
            policy.delete(id)
        }
    }

    @Test fun mainVideoOnlyAppliesWhenPauseMethodIsActivity() {
        val id = uniqueProfile()
        val activity = ActivityUnlockStore(context)
        val condition = ActivityUnlockCondition(description = "腕立て伏せ30回")
        activity.save(id, listOf(condition))
        activity.setMainCondition(id, condition.id)
        val config = ProfileUnlockPolicyStore(context)
        config.save(id, ProfileUnlockPolicy(pause = UnlockMethod.MANUAL))
        assertThat(activity.selection(id, "session")).isNull()
        config.save(id, ProfileUnlockPolicy(pause = UnlockMethod.ACTIVITY))
        assertThat(activity.selection(id, "session")?.conditionId).isEqualTo(condition.id)
        config.delete(id)
    }

    @Test fun videoMainCanBeReplacedOnceWithSubcondition() {
        val id = uniqueProfile()
        val store = ActivityUnlockStore(context)
        val main = ActivityUnlockCondition(description = "腹筋50回")
        val sub = ActivityUnlockCondition(description = "掃除する")
        store.save(id, listOf(main, sub))
        store.setMainCondition(id, main.id)
        ProfileUnlockPolicyStore(context).save(id, ProfileUnlockPolicy(pause = UnlockMethod.ACTIVITY))
        assertThat(store.select(id, "session", sub.id)).isTrue()
        assertThat(store.select(id, "session", main.id)).isFalse()
        assertThat(store.selection(id, "session")?.conditionId).isEqualTo(sub.id)
        store.clear(id)
        ProfileUnlockPolicyStore(context).delete(id)
    }
}
