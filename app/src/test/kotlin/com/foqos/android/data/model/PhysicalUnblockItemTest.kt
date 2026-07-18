package com.foqos.android.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhysicalUnblockItemTest {

    @Test
    fun `trims whitespace on plain (non-url) values`() {
        assertThat(PhysicalUnblockItem.normalizeCodeValue("  04:A2:B3:C4  ")).isEqualTo("04:A2:B3:C4")
    }

    @Test
    fun `lowercases scheme and host for urls`() {
        assertThat(PhysicalUnblockItem.normalizeCodeValue("HTTPS://Example.COM/path"))
            .isEqualTo("https://example.com/path")
    }

    @Test
    fun `strips bare trailing slash on root path`() {
        assertThat(PhysicalUnblockItem.normalizeCodeValue("https://Example.com/"))
            .isEqualTo("https://example.com")
    }

    @Test
    fun `keeps trailing slash on non-root deep paths`() {
        assertThat(PhysicalUnblockItem.normalizeCodeValue("https://Example.com/foo/"))
            .isEqualTo("https://example.com/foo/")
    }

    @Test
    fun `keeps query and fragment even when path is root`() {
        assertThat(PhysicalUnblockItem.normalizeCodeValue("https://Example.com/?a=1"))
            .isEqualTo("https://example.com/?a=1")
    }

    @Test
    fun `values without host are returned trimmed only`() {
        assertThat(PhysicalUnblockItem.normalizeCodeValue(" mailto:foo@bar.com "))
            .isEqualTo("mailto:foo@bar.com")
    }

    @Test
    fun `empty value normalizes to empty`() {
        assertThat(PhysicalUnblockItem.normalizeCodeValue("   ")).isEqualTo("")
    }

    @Test
    fun `unparsable garbage falls back to trimmed original`() {
        val raw = "not a url {}<>"
        assertThat(PhysicalUnblockItem.normalizeCodeValue(raw)).isEqualTo(raw)
    }
}
