@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.foqos.android.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foqos.android.domain.strategy.BlockingStrategy
import com.foqos.android.domain.strategy.StrategyCategory
import com.foqos.android.ui.common.strategyBadges
import com.foqos.android.ui.common.strategyEmoji

/**
 * Full strategy picker, grouped by [StrategyCategory] in registry order (Most popular / Easy to
 * start / Timers / Forever / More options), mirroring iOS's `BlockingStrategyPicker` sections.
 */
@Composable
fun StrategyPickerSheet(
    categorized: Map<StrategyCategory, List<BlockingStrategy>>,
    selectedStrategyId: String?,
    onSelect: (BlockingStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StrategyCategory.entries.forEach { category ->
                val strategies = categorized[category].orEmpty()
                if (strategies.isEmpty()) return@forEach

                item(key = "header-${category.name}") {
                    Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        Text(text = category.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = category.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(strategies, key = { it.id }) { strategy ->
                    StrategyRow(
                        strategy = strategy,
                        selected = strategy.id == selectedStrategyId,
                        onClick = { onSelect(strategy) },
                    )
                }
            }

            item { androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 16.dp)) }
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
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = strategyEmoji(strategy), style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = strategy.name, style = MaterialTheme.typography.titleSmall)
                Text(text = strategy.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val badges = strategyBadges(strategy)
                if (badges.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        badges.forEach { badge -> AssistChip(onClick = {}, label = { Text(badge, style = MaterialTheme.typography.labelSmall) }) }
                    }
                }
            }
        }
    }
}
