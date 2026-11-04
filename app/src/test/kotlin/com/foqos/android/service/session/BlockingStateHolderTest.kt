package com.foqos.android.service.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockingStateHolderTest {
    private fun state(profile: String) = BlockingState(
        profileId = profile, sessionId = "session-$profile", profileName = profile,
        blockedPackages = setOf("com.example.blocked"),
    )

    @Test fun `stopping profile A keeps profile B in enforcement map`() {
        val holder = BlockingStateHolder()
        holder.setState(state("a"))
        holder.setState(state("b"))
        holder.clearProfile("a")
        assertThat(holder.states.value.keys).containsExactly("b")
        assertThat(holder.state.value?.profileId).isEqualTo("b")
    }

    @Test fun `pausing profile A does not pause profile B`() {
        val holder = BlockingStateHolder()
        holder.setState(state("a"))
        holder.setState(state("b"))
        holder.updateProfile("a") { it.copy(pauseActive = true) }
        assertThat(holder.states.value["a"]?.pauseActive).isTrue()
        assertThat(holder.states.value["b"]?.pauseActive).isFalse()
    }

    @Test fun `clearing all sessions leaves no blocking state`() {
        val holder = BlockingStateHolder()
        holder.setState(state("a"))
        holder.setState(state("b"))
        holder.clear()
        assertThat(holder.states.value).isEmpty()
        assertThat(holder.state.value).isNull()
    }
}
