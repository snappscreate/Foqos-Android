package com.foqos.android.ui.block

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusShortcutTest {
    private val apps = setOf("com.example.reader")
    private val urls = setOf("https://example.com")

    @Test fun allIncludesAppsUrlsAndPhoneOnce() {
        assertThat(FocusShortcut.all(apps, urls)).containsExactly(
            "app:com.example.reader", "url:https://example.com", "phone:",
        )
    }

    @Test fun onlyWhitelistedTargetsAreEligible() {
        assertThat(FocusShortcut.valid(FocusShortcut.PHONE, apps, urls)).isTrue()
        assertThat(FocusShortcut.valid(FocusShortcut.app("com.example.reader"), apps, urls)).isTrue()
        assertThat(FocusShortcut.valid(FocusShortcut.url("https://example.com"), apps, urls)).isTrue()
        assertThat(FocusShortcut.valid(FocusShortcut.app("com.example.blocked"), apps, urls)).isFalse()
        assertThat(FocusShortcut.valid(FocusShortcut.url("https://blocked.com"), apps, urls)).isFalse()
    }
}
