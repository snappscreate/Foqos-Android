package com.foqos.android.service.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocationRuleTest {
    @Test fun `valid geographic rule retains its values`() {
        val rule = LocationRule("profile-a", 33.5902, 130.4017, 200f)
        assertThat(rule.profileId).isEqualTo("profile-a")
        assertThat(rule.radiusMeters).isEqualTo(200f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid latitude is rejected`() {
        LocationRule("profile-a", 91.0, 130.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `too small a radius is rejected`() {
        LocationRule("profile-a", 33.0, 130.0, 5f)
    }
}
