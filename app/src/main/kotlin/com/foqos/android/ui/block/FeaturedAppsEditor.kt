package com.foqos.android.ui.block

import android.widget.ImageView
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Drag an icon from either group into the other group to change featured selection.
 * Uses pointer positions in root coordinates, not Android's global drag manager.
 */
@Composable
fun FeaturedAppsEditor(
    allowed: Set<String>,
    featured: Set<String>,
    labelFor: (String) -> String,
    onFeaturedChange: (Set<String>) -> Unit,
) {
    var featuredBounds by remember { mutableStateOf(Rect.Zero) }
    var otherBounds by remember { mutableStateOf(Rect.Zero) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("アイコンを長押しして、もう一方の枠へドラッグ")
        Column(
            modifier = Modifier.fillMaxWidth()
                .onGloballyPositioned { featuredBounds = it.boundsInRoot() },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("おすすめアプリ（ここにドロップで追加）", style = MaterialTheme.typography.titleSmall)
            DragAppRows(
                packages = featured.intersect(allowed).sorted(),
                labelFor = labelFor,
                onDrop = { pkg, position ->
                    if (otherBounds.contains(position)) onFeaturedChange(featured - pkg)
                },
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth()
                .onGloballyPositioned { otherBounds = it.boundsInRoot() },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("その他のホワイトリスト（ここにドロップで除外）",
                style = MaterialTheme.typography.titleSmall)
            DragAppRows(
                packages = (allowed - featured).sorted(),
                labelFor = labelFor,
                onDrop = { pkg, position ->
                    if (featuredBounds.contains(position)) onFeaturedChange(featured + pkg)
                },
            )
        }
    }
}

@Composable
private fun DragAppRows(
    packages: List<String>,
    labelFor: (String) -> String,
    onDrop: (String, Offset) -> Unit,
) {
    if (packages.isEmpty()) {
        Text("ここにはまだアプリがない", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        packages.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { pkg ->
                    DragAppIcon(pkg, labelFor(pkg)) { position -> onDrop(pkg, position) }
                }
            }
        }
    }
}

@Composable
private fun DragAppIcon(
    packageName: String,
    label: String,
    onDrop: (Offset) -> Unit,
) {
    var rootTopLeft by remember(packageName) { mutableStateOf(Offset.Zero) }
    var dragPoint by remember(packageName) { mutableStateOf(Offset.Zero) }
    var translation by remember(packageName) { mutableStateOf(Offset.Zero) }
    var dragging by remember(packageName) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .size(width = 112.dp, height = 84.dp)
            .zIndex(if (dragging) 5f else 0f)
            .offset { IntOffset(translation.x.roundToInt(), translation.y.roundToInt()) }
            .onGloballyPositioned {
                if (!dragging) rootTopLeft = it.boundsInRoot().topLeft
            }
            .pointerInput(packageName) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        dragging = true
                        translation = Offset.Zero
                        dragPoint = rootTopLeft + offset
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        translation += delta
                        dragPoint += delta
                    },
                    onDragEnd = {
                        onDrop(dragPoint)
                        dragging = false
                        translation = Offset.Zero
                    },
                    onDragCancel = {
                        dragging = false
                        translation = Offset.Zero
                    },
                )
            }
            .graphicsLayer { alpha = if (dragging) 0.75f else 1f },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    setImageDrawable(runCatching {
                        context.packageManager.getApplicationIcon(packageName)
                    }.getOrNull())
                }
            },
            modifier = Modifier.size(48.dp),
        )
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall)
    }
}
