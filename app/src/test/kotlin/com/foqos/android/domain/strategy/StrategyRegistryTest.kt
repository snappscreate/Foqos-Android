package com.foqos.android.domain.strategy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StrategyRegistryTest {

    @Test
    fun `registry has all 12 strategies in the exact iOS order`() {
        val expectedIds = listOf(
            "NFCSoftUnblockBlockingStrategy",
            "QRSoftUnblockBlockingStrategy",
            "ManualBlockingStrategy",
            "NFCBlockingStrategy",
            "NFCManualBlockingStrategy",
            "NFCTimerBlockingStrategy",
            "NFCPauseTimerBlockingStrategy",
            "QRCodeBlockingStrategy",
            "QRManualBlockingStrategy",
            "QRTimerBlockingStrategy",
            "QRPauseTimerBlockingStrategy",
            "ShortcutTimerBlockingStrategy",
        )

        assertThat(StrategyRegistry.all.map { it.id }).isEqualTo(expectedIds)
    }

    @Test
    fun `every strategy id is unique`() {
        val ids = StrategyRegistry.all.map { it.id }
        assertThat(ids.toSet()).hasSize(ids.size)
    }

    @Test
    fun `byId resolves a known strategy`() {
        assertThat(StrategyRegistry.byId("ManualBlockingStrategy")).isInstanceOf(ManualBlockingStrategy::class.java)
        assertThat(StrategyRegistry.byId("QRPauseTimerBlockingStrategy"))
            .isInstanceOf(QrPauseTimerBlockingStrategy::class.java)
    }

    @Test
    fun `byId falls back to NFCBlockingStrategy for unknown ids`() {
        assertThat(StrategyRegistry.byId("SomethingThatDoesNotExist")).isInstanceOf(NfcBlockingStrategy::class.java)
    }

    @Test
    fun `byId falls back to NFCBlockingStrategy for null`() {
        assertThat(StrategyRegistry.byId(null)).isInstanceOf(NfcBlockingStrategy::class.java)
    }

    @Test
    fun `categorized groups strategies by picker category`() {
        val categorized = StrategyRegistry.categorized()

        assertThat(categorized[StrategyCategory.MOST_POPULAR]?.map { it.id })
            .containsExactly("NFCBlockingStrategy", "QRCodeBlockingStrategy")
            .inOrder()

        assertThat(categorized[StrategyCategory.EASY_TO_START]?.map { it.id })
            .containsExactly("ManualBlockingStrategy", "NFCManualBlockingStrategy", "QRManualBlockingStrategy")
            .inOrder()

        assertThat(categorized[StrategyCategory.TIMERS]?.map { it.id })
            .containsExactly("NFCTimerBlockingStrategy", "QRTimerBlockingStrategy", "ShortcutTimerBlockingStrategy")
            .inOrder()

        assertThat(categorized[StrategyCategory.FOREVER]?.map { it.id })
            .containsExactly(
                "NFCSoftUnblockBlockingStrategy",
                "QRSoftUnblockBlockingStrategy",
                "NFCPauseTimerBlockingStrategy",
                "QRPauseTimerBlockingStrategy",
            )
            .inOrder()

        assertThat(categorized.containsKey(StrategyCategory.MORE_OPTIONS)).isFalse()
    }

    @Test
    fun `allowsTimedBreaks is false only for soft-unblock strategies`() {
        val softUnblockIds = setOf("NFCSoftUnblockBlockingStrategy", "QRSoftUnblockBlockingStrategy")

        for (strategy in StrategyRegistry.all) {
            val expected = strategy.id !in softUnblockIds
            assertThat(strategy.allowsTimedBreaks).isEqualTo(expected)
        }
    }
}
