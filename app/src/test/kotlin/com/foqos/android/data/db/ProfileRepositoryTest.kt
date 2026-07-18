package com.foqos.android.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSchedule
import com.foqos.android.data.model.PhysicalUnblockItem
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.model.StrategyTimerData
import com.foqos.android.data.model.Weekday
import com.foqos.android.data.repository.ProfileRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ProfileRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ProfileRepository(db.blockedProfileDao(), kotlinx.coroutines.Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleProfile(name: String = "Deep Work") = BlockedProfile(
        id = "profile-1",
        name = name,
        blockedPackages = listOf("com.instagram.android", "com.twitter.android"),
        domains = listOf("reddit.com", "youtube.com"),
        strategyId = "NFCTimerBlockingStrategy",
        strategyDataJson = StrategyTimerData(durationInMinutes = 30, hideStopButton = true).toJson(),
        physicalUnblockItems = listOf(
            PhysicalUnblockItem(name = "Desk Tag", type = PhysicalUnblockType.NFC, codeValue = "04:AA:BB"),
            PhysicalUnblockItem(name = "Door QR", type = PhysicalUnblockType.QR_CODE, codeValue = "https://Example.com/"),
        ),
        schedule = BlockedProfileSchedule(
            days = listOf(Weekday.MONDAY, Weekday.WEDNESDAY),
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
        ),
        enableBreaks = true,
        breakTimeInMinutes = 20,
    )

    @Test
    fun `create then getById round-trips all json-backed fields`() = runTest {
        val created = repository.create(sampleProfile())

        val loaded = repository.getById(created.id)

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.blockedPackages).containsExactly("com.instagram.android", "com.twitter.android")
        assertThat(loaded.domains).containsExactly("reddit.com", "youtube.com")
        assertThat(loaded.physicalUnblockItems).hasSize(2)
        assertThat(loaded.physicalUnblockItems.first { it.type == PhysicalUnblockType.QR_CODE }.codeValue)
            .isEqualTo("https://Example.com/") // stored as-is; normalization happens at write-time call sites
        assertThat(loaded.schedule).isNotNull()
        assertThat(loaded.schedule!!.days).containsExactly(Weekday.MONDAY, Weekday.WEDNESDAY)
        assertThat(loaded.schedule!!.startHour).isEqualTo(9)

        val timerData = StrategyTimerData.fromJsonOrNull(loaded.strategyDataJson)
        assertThat(timerData.durationInMinutes).isEqualTo(30)
        assertThat(timerData.hideStopButton).isTrue()
    }

    @Test
    fun `create assigns increasing sort order`() = runTest {
        val first = repository.create(sampleProfile("First"))
        val second = repository.create(sampleProfile("Second").copy(id = "profile-2"))

        assertThat(first.sortOrder).isEqualTo(0)
        assertThat(second.sortOrder).isEqualTo(1)
    }

    @Test
    fun `observeAll orders by sort order`() = runTest {
        repository.create(sampleProfile("First"))
        repository.create(sampleProfile("Second").copy(id = "profile-2"))
        repository.reorder(listOf("profile-2", "profile-1"))

        val names = repository.observeAll().first().map { it.name }
        assertThat(names).containsExactly("Second", "First").inOrder()
    }

    @Test
    fun `delete removes the profile`() = runTest {
        val created = repository.create(sampleProfile())
        repository.delete(created.id)

        assertThat(repository.getById(created.id)).isNull()
    }

    @Test
    fun `clone copies fields with a new id, copy suffix and next sort order`() = runTest {
        val original = repository.create(sampleProfile("Deep Work"))
        val secondOriginal = repository.create(sampleProfile("Other").copy(id = "profile-2"))

        val cloned = repository.clone(original.id)

        assertThat(cloned).isNotNull()
        assertThat(cloned!!.id).isNotEqualTo(original.id)
        assertThat(cloned.name).isEqualTo("Deep Work copy")
        assertThat(cloned.blockedPackages).isEqualTo(original.blockedPackages)
        assertThat(cloned.domains).isEqualTo(original.domains)
        assertThat(cloned.physicalUnblockItems).isEqualTo(original.physicalUnblockItems)
        assertThat(cloned.schedule).isEqualTo(original.schedule)
        assertThat(cloned.sortOrder).isEqualTo(2)

        val stored = repository.getById(cloned.id)
        assertThat(stored).isNotNull()
        assertThat(stored!!.name).isEqualTo("Deep Work copy")
    }

    @Test
    fun `clone returns null for unknown profile id`() = runTest {
        assertThat(repository.clone("does-not-exist")).isNull()
    }
}
