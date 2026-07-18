package com.foqos.android.service.nfc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure `java.net.URI`-based parsing - no Android dependency, runs as a plain JVM unit test. */
class ProfileLinkCodecTest {

    private val profileId = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `build produces the canonical foqos scheme link`() {
        assertThat(ProfileLinkCodec.buildProfileUri(profileId)).isEqualTo("foqos://profile/$profileId")
    }

    @Test
    fun `parse round-trips a built foqos scheme link`() {
        val uri = ProfileLinkCodec.buildProfileUri(profileId)
        assertThat(ProfileLinkCodec.parseProfileId(uri)).isEqualTo(profileId)
    }

    @Test
    fun `parse accepts the iOS-written https universal link`() {
        assertThat(ProfileLinkCodec.parseProfileId("https://foqos.app/profile/$profileId")).isEqualTo(profileId)
    }

    @Test
    fun `parse tolerates a trailing slash on the foqos scheme`() {
        assertThat(ProfileLinkCodec.parseProfileId("foqos://profile/$profileId/")).isEqualTo(profileId)
    }

    @Test
    fun `parse tolerates a trailing slash on the https link`() {
        assertThat(ProfileLinkCodec.parseProfileId("https://foqos.app/profile/$profileId/")).isEqualTo(profileId)
    }

    @Test
    fun `parse rejects a different scheme`() {
        assertThat(ProfileLinkCodec.parseProfileId("http://foqos.app/profile/$profileId")).isNull()
    }

    @Test
    fun `parse rejects a different https host`() {
        assertThat(ProfileLinkCodec.parseProfileId("https://example.com/profile/$profileId")).isNull()
    }

    @Test
    fun `parse rejects a foqos link missing the profile host segment`() {
        assertThat(ProfileLinkCodec.parseProfileId("foqos://$profileId")).isNull()
    }

    @Test
    fun `parse rejects an https link with extra path segments`() {
        assertThat(ProfileLinkCodec.parseProfileId("https://foqos.app/profile/$profileId/extra")).isNull()
    }

    @Test
    fun `parse rejects a blank id`() {
        assertThat(ProfileLinkCodec.parseProfileId("foqos://profile/")).isNull()
        assertThat(ProfileLinkCodec.parseProfileId("https://foqos.app/profile/")).isNull()
    }

    @Test
    fun `parse rejects garbage input`() {
        assertThat(ProfileLinkCodec.parseProfileId("not a uri at all")).isNull()
        assertThat(ProfileLinkCodec.parseProfileId("")).isNull()
        assertThat(ProfileLinkCodec.parseProfileId("   ")).isNull()
    }

    @Test
    fun `parse is case-insensitive on scheme and host`() {
        assertThat(ProfileLinkCodec.parseProfileId("FOQOS://PROFILE/$profileId")).isEqualTo(profileId)
        assertThat(ProfileLinkCodec.parseProfileId("HTTPS://FOQOS.APP/profile/$profileId")).isEqualTo(profileId)
    }
}
