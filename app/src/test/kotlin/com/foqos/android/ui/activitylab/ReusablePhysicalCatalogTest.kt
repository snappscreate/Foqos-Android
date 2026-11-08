package com.foqos.android.ui.activitylab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.model.PhysicalUnblockItem
import com.foqos.android.data.model.PhysicalUnblockType
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReusablePhysicalCatalogTest {
    @Test
    fun `a registered credential remains reusable after its original profile disappears`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = ReusablePhysicalCatalog(context)
        val token = PhysicalUnblockItem(id = UUID.randomUUID().toString(),
            name = "玄関NFC", type = PhysicalUnblockType.NFC, codeValue = "nfc-sample")
        catalog.remember(listOf(token))
        // No source profile data is retained: the shared library owns this entry.
        val newCatalog = ReusablePhysicalCatalog(context)
        assertThat(newCatalog.items()).contains(token)
        newCatalog.remember(listOf(token.copy(name = "玄関タグ")))
        assertThat(newCatalog.items().first { it.id == token.id }.name).isEqualTo("玄関タグ")
    }
}
