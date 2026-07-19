@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.foqos.android.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foqos.android.domain.strategy.BlockingStrategy
import com.foqos.android.ui.common.StrategyPickerGroup
import com.foqos.android.ui.common.isRecommendedStrategy
import com.foqos.android.ui.common.strategyCapabilityBadges
import com.foqos.android.ui.common.strategyOneLiner
import com.foqos.android.ui.common.strategyPickerGroup
import com.foqos.android.ui.common.strategyPickerIcon

/**
 * Redesigned strategy picker: strategies are grouped by *mechanism* ([StrategyPickerGroup] - app /
 * NFC / QR) rather than the old 5-way [com.foqos.android.domain.strategy.StrategyCategory] section
 * list, which user testing found unclear ("the modes should be better sorted and more
 * visible/understandable"). Each row states in one plain-language sentence exactly how starting
 * and stopping work, and the two most common strategies (Manual, NFC Tags) carry a "Recommended"
 * chip.
 *
 * The sheet's [LazyColumn] is explicitly height-bounded ([Modifier.fillMaxHeight] fraction) even
 * though [ModalBottomSheet] content is already measured with a finite max height - this keeps the
 * bound obvious at the call site so a future edit that moves this composable into a scrollable
 * parent (the bug that caused the profile-editor crash-loop, see `PhysicalUnlockEditor`) is less
 * likely to silently reintroduce an unbounded nested-scrollable crash.
 */
@Composable
fun StrategyPickerSheet(
    strategies: List<BlockingStrategy>,
    selectedStrategyId: String?,
    onSelect: (BlockingStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grouped = strategies.groupBy { strategyPickerGroup(it) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "picker-header") {
                Column(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                    Text(
                        text = "How do you want to start and stop focus sessions?",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Pick how sessions are triggered. You can fine-tune timers and physical unlocks after choosing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            StrategyPickerGroup.entries.forEach { group ->
                val groupStrategies = grouped[group].orEmpty()
                if (groupStrategies.isEmpty()) return@forEach

                item(key = "header-${group.name}") {
                    Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        Text(text = group.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = group.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(groupStrategies, key = { it.id }) { strategy ->
                    StrategyRow(
                        strategy = strategy,
                        selected = strategy.id == selectedStrategyId,
                        onClick = { onSelect(strategy) },
                    )
                }
            }

            item(key = "picker-footer-spacer") {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 16.dp))
            }
        }
    }
}

@Composable
private fun StrategyRow(strategy: BlockingStrategy, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = strategyPickerIcon(strategy),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = strategy.name, style = MaterialTheme.typography.titleSmall)
                    if (isRecommendedStrategy(strategy)) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Recommended", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                        )
                    }
                }
                Text(
                    text = strategyOneLiner(strategy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                val badges = strategyCapabilityBadges(strategy)
                if (badges.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        badges.forEach { badge -> AssistChip(onClick = {}, label = { Text(badge, style = MaterialTheme.typography.labelSmall) }) }
                    }
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
