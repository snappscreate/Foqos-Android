package com.foqos.android.ui.activitylab

import android.content.Context
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
