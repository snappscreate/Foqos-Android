package com.foqos.android.ui.block

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Remaining soft-unblock allowance to offer on the block screen, if any. */
data class SoftUnblockInfo(val remainingOpens: Int, val accessMinutes: Int)

/**
 * One curated "fun block message" bundle, ported from iOS's
 * `ShieldConfigurationExtension.getFunBlockMessage` - keeps the tone light/encouraging rather than
 * scolding, matching the iOS shield's design intent.
 */
private data class FunMessage(val emoji: String, val title: String, val subtitle: (String) -> String)

private val FUN_MESSAGES = listOf(
    FunMessage("📵", "Not right now") { name -> "$name can wait. You're choosing your time on purpose." },
    FunMessage("🧠", "Brain check") { name -> "Do you actually want $name... or was it autopilot?" },
    FunMessage("🎯", "Stay on target") { name -> "One small step toward your goal first. Then decide on $name." },
    FunMessage("⏳", "Give it 2 minutes") { name -> "Finish the next tiny thing. $name will still be there after." },
    FunMessage("🛡️", "Shield up") { _ -> "Focus is protected. You've got this." },
    FunMessage("🔒", "Locked in") { _ -> "This block is temporary. Your momentum isn't." },
    FunMessage("🧱", "Boundary set") { _ -> "You made a plan. This is you sticking to it." },
    FunMessage("✨", "Glow mode") { _ -> "You're building attention - that's the real flex." },
    FunMessage("🫶", "Be kind to you") { _ -> "No shame. Just a gentle nudge back to what matters." },
    FunMessage("🌐", "Not this detour") { name -> "$name isn't part of the mission right now." },
)

/**
 * Deterministically (but not obviously repetitively) picks a [FunMessage] for [seedText] - mirrors
 * iOS's stable-per-title hashing so the same blocked app tends to show the same tone within a day,
 * without needing a random-number generator here.
 */
private fun pickFunMessage(seedText: String): FunMessage {
    val hash = seedText.fold(0) { acc, c -> acc * 31 + c.code }
    val index = (hash.takeIf { it >= 0 } ?: -hash).mod(FUN_MESSAGES.size)
    return FUN_MESSAGES[index]
}

/**
 * Full-screen block overlay content shown over a blocked app/website. Pure Compose function so it
 * can be previewed/iterated on without the surrounding [android.app.Activity] plumbing -
 * [com.foqos.android.service.overlay.BlockOverlayActivity] supplies all the state.
 */
@Composable
fun BlockScreenContent(
    profileName: String,
    blockedLabel: String,
    sessionStartEpochMillis: Long,
    softUnblockInfo: SoftUnblockInfo?,
    onGoBack: () -> Unit,
    onOpenTemporarily: () -> Unit,
    blockedIcon: (@Composable () -> Unit)? = null,
    nfcReady: Boolean = false,
    onSimulateNfc: (() -> Unit)? = null,
    distanceDescription: String? = null,
    nfcMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val message = remember(blockedLabel) { pickFunMessage(blockedLabel) }

    var elapsedMillis by remember(sessionStartEpochMillis) {
        mutableLongStateOf((System.currentTimeMillis() - sessionStartEpochMillis).coerceAtLeast(0L))
    }
    LaunchedEffect(sessionStartEpochMillis) {
        while (true) {
            elapsedMillis = (System.currentTimeMillis() - sessionStartEpochMillis).coerceAtLeast(0L)
            delay(1_000L)
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(primary, background),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .background(Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(32.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (blockedIcon != null) blockedIcon() else Text(text = "🔒", fontSize = 40.sp)
            }

            AnimatedContent(targetState = message, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "fun-message") { msg ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "${msg.emoji}  Stay focused", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
                    Text(text = msg.subtitle(blockedLabel), fontSize = 16.sp, color = Color.White.copy(alpha = 0.88f), textAlign = TextAlign.Center)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = profileName, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                Text(text = "Blocking $blockedLabel", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                Text(text = formatElapsed(elapsedMillis), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }

            if (distanceDescription != null) {
                Text(distanceDescription, fontSize = 14.sp, color = Color.White, textAlign = TextAlign.Center)
            }
            if (nfcReady) {
                Text("Hold a registered NFC tag near your phone to unlock or pause",
                    fontSize = 14.sp, color = Color.White, textAlign = TextAlign.Center)
            }
            if (!nfcMessage.isNullOrBlank()) {
                Text(nfcMessage, fontSize = 14.sp, color = Color.White, textAlign = TextAlign.Center)
            }
            if (onSimulateNfc != null) {
                OutlinedButton(onClick = onSimulateNfc, modifier = Modifier.fillMaxWidth()) {
                    Text("DEBUG: Simulate NFC scan")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onGoBack,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Go back")
            }

            if (softUnblockInfo != null && softUnblockInfo.remainingOpens > 0) {
                OutlinedButton(
                    onClick = onOpenTemporarily,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Open for ${softUnblockInfo.accessMinutes} min (${softUnblockInfo.remainingOpens} left)")
                }
            }
        }
    }
}

private fun formatElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
