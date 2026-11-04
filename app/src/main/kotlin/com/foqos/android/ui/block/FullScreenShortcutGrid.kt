package com.foqos.android.ui.block

import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Both normal and editing modes use the same tiles and sections. The entire tile,
 * including its caption, moves as one unit. Only the membership changes on drop.
 */
@Composable
fun FullScreenShortcutGrid(
    packages: Set<String>,
    urls: Set<String>,
    appLabel: (String) -> String,
    onApp: (String) -> Unit,
    onPhone: () -> Unit,
    onUrl: (String) -> Unit,
    featuredShortcuts: Set<String> = emptySet(),
    autoOpenSeconds: Int? = null,
    editingFeatured: Boolean = false,
    onEditFeatured: (() -> Unit)? = null,
    onFeaturedChange: ((Set<String>) -> Unit)? = null,
    allowedAppsExpanded: Boolean = false,
    onToggleAllowedApps: (() -> Unit)? = null,
) {
    val all = FocusShortcut.all(packages, urls)
    val featured = featuredShortcuts.intersect(all)
    val regular = (all - featured).sorted()
    var featuredBounds by remember { mutableStateOf(Rect.Zero) }
    var regularBounds by remember { mutableStateOf(Rect.Zero) }

    // Editing does not replace the UI with another layout or a dialog.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.17f), RoundedCornerShape(16.dp))
                .padding(12.dp)
                .onGloballyPositioned { featuredBounds = it.boundsInRoot() },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("おすすめアプリ・サイト", color = Color.White,
                    style = MaterialTheme.typography.titleMedium)
                if (onEditFeatured != null) {
                    TextButton(onClick = onEditFeatured) {
                        Text(if (editingFeatured) "完了" else "編集", color = Color.White)
                    }
                }
            }
            if (featured.isEmpty()) {
                Text(if (editingFeatured) "下の一覧から長押ししてここへ移動"
                    else "編集から自動起動する候補を選べるよ",
                    color = Color.White, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    if (editingFeatured) "長押しして、下の一覧へ移動すると解除"
                    else if (autoOpenSeconds != null) "${autoOpenSeconds}秒後にランダムに1つ起動"
                    else "選択した候補からランダムに自動起動",
                    color = Color.White, style = MaterialTheme.typography.labelSmall,
                )
            }
            featured.sorted().chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { id ->
                        key(id) {
                            ShortcutTile(
                                id = id, featured = true, appLabel = appLabel,
                                editing = editingFeatured, onApp = onApp, onPhone = onPhone, onUrl = onUrl,
                                onDrop = { position ->
                                    if (regularBounds.contains(position))
                                        onFeaturedChange?.invoke(featured - id)
                                },
                            )
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = { onToggleAllowedApps?.invoke() },
            modifier = Modifier.fillMaxWidth(),
            enabled = onToggleAllowedApps != null && !editingFeatured,
        ) {
            Text(if (editingFeatured) "許可されたアプリ（編集中）" else if (allowedAppsExpanded) "許可されたアプリを閉じる" else "許可されたアプリ")
        }

        if (allowedAppsExpanded || editingFeatured) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .onGloballyPositioned { regularBounds = it.boundsInRoot() },
            ) {
                Text("許可されたアプリ・URL・電話",
                    color = Color.White, style = MaterialTheme.typography.labelMedium)
                if (editingFeatured) {
                    Text("おすすめに追加するには上の枠へドラッグ",
                        color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 224.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    regular.chunked(5).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            row.forEach { id ->
                                key(id) {
                                    ShortcutTile(
                                        id = id, featured = false, appLabel = appLabel,
                                        editing = editingFeatured, onApp = onApp,
                                        onPhone = onPhone, onUrl = onUrl,
                                        onDrop = { position ->
                                            if (featuredBounds.contains(position))
                                                onFeaturedChange?.invoke(featured + id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutTile(
    id: String,
    featured: Boolean,
    appLabel: (String) -> String,
    editing: Boolean,
    onApp: (String) -> Unit,
    onPhone: () -> Unit,
    onUrl: (String) -> Unit,
    onDrop: (Offset) -> Unit,
) {
    val pkg = id.takeIf { it.startsWith(FocusShortcut.APP_PREFIX) }
        ?.removePrefix(FocusShortcut.APP_PREFIX)
    val url = id.takeIf { it.startsWith(FocusShortcut.URL_PREFIX) }
        ?.removePrefix(FocusShortcut.URL_PREFIX)
    val label = when {
        id == FocusShortcut.PHONE -> "電話"
        pkg != null -> appLabel(pkg)
        url != null -> url.removePrefix("https://").removePrefix("http://")
        else -> id
    }
    val width = if (featured) 76.dp else 57.dp
    val height = if (featured) 94.dp else 75.dp
    val iconSize = if (featured) 52.dp else 36.dp
    var basePosition by remember(id) { mutableStateOf(Offset.Zero) }
    var dragDelta by remember(id) { mutableStateOf(Offset.Zero) }
    var dragging by remember(id) { mutableStateOf(false) }
    val launch = {
        when {
            id == FocusShortcut.PHONE -> onPhone()
            pkg != null -> onApp(pkg)
            url != null -> onUrl(url)
        }
    }
    val gesture = if (editing) Modifier.pointerInput(id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { dragging = true; dragDelta = Offset.Zero },
            onDrag = { change, amount ->
                change.consume()
                dragDelta += amount
            },
            onDragEnd = {
                onDrop(basePosition + dragDelta)
                dragging = false
                dragDelta = Offset.Zero
            },
            onDragCancel = { dragging = false; dragDelta = Offset.Zero },
        )
    } else Modifier.clickable(onClick = launch)

    Column(
        modifier = Modifier
            .size(width = width, height = height)
            .onGloballyPositioned { if (!dragging) basePosition = it.boundsInRoot().center }
            .zIndex(if (dragging) 10f else 0f)
            .offset { IntOffset(dragDelta.x.roundToInt(), dragDelta.y.roundToInt()) }
            .then(gesture)
            .graphicsLayer { alpha = if (dragging) 0.8f else 1f },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        when {
            id == FocusShortcut.PHONE -> Icon(Icons.Filled.Call,
                contentDescription = "電話", tint = Color.White, modifier = Modifier.size(iconSize))
            pkg != null -> AndroidView(
                factory = { context -> ImageView(context) },
                update = { image ->
                    image.setImageDrawable(runCatching {
                        image.context.packageManager.getApplicationIcon(pkg)
                    }.getOrNull())
                },
                modifier = Modifier.size(iconSize),
            )
            url != null -> {
                val favicon by produceState<android.graphics.Bitmap?>(null, url) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val connection = URL("$url/favicon.ico")
                                .openConnection() as HttpURLConnection
                            connection.connectTimeout = 1800
                            connection.readTimeout = 1800
                            try { connection.inputStream.use { BitmapFactory.decodeStream(it) } }
                            finally { connection.disconnect() }
                        }.getOrNull()
                    }
                }
                if (favicon != null) Image(
                    bitmap = favicon!!.asImageBitmap(),
                    contentDescription = null, modifier = Modifier.size(iconSize),
                ) else Text("🌐", style = MaterialTheme.typography.headlineMedium)
            }
        }
        Text(
            label, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = Color.White, style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}
