package com.foqos.android.ui.block

import android.graphics.BitmapFactory
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-shield launcher icons. Favicon fetches are best effort and only request
 * /favicon.ico from the explicitly allowlisted host, never page content.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FullScreenShortcutGrid(
    packages: Set<String>,
    urls: Set<String>,
    appLabel: (String) -> String,
    onApp: (String) -> Unit,
    onPhone: () -> Unit,
    onUrl: (String) -> Unit,
    featuredPackages: Set<String> = emptySet(),
    autoOpenSeconds: Int? = null,
    onEditFeatured: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (featuredPackages.isNotEmpty() || onEditFeatured != null) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.17f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("おすすめアプリ", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    if (onEditFeatured != null) {
                        TextButton(onClick = onEditFeatured) {
                            Text("編集", color = Color.White)
                        }
                    }
                }
                if (featuredPackages.isEmpty()) {
                    Text("編集から、許可アプリの中で自動起動する候補を選べるよ",
                        color = Color.White, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(if (autoOpenSeconds != null) "${autoOpenSeconds}秒後に候補からランダムに1つ起動" else "候補からランダムに自動起動",
                        color = Color.White, style = MaterialTheme.typography.labelSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 4,
                    ) {
                        featuredPackages.sorted().forEach { pkg ->
                            ShortcutAppIcon(pkg, appLabel, onApp, featured = true)
                        }
                    }
                }
            }
        }
        Text("その他の許可アプリ", color = Color.White)
        val regular = (packages - featuredPackages).sorted()
        if (regular.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 210.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                regular.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pair.forEach { pkg ->
                            ShortcutAppIcon(pkg, appLabel, onApp, featured = false)
                        }
                    }
                }
            }
        }
        Text("電話・許可サイト", color = Color.White)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 4,
        ) {
            Column(
                modifier = Modifier.size(width = 72.dp, height = 92.dp)
                    .clickable(onClick = onPhone),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Call,
                    contentDescription = "電話を開く",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
                Text("電話", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            urls.sorted().forEach { address ->
                Column(
                    modifier = Modifier.size(width = 72.dp, height = 92.dp)
                        .clickable { onUrl(address) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    val favicon = produceState<android.graphics.Bitmap?>(null, address) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                val connection = URL("$address/favicon.ico")
                                    .openConnection() as HttpURLConnection
                                connection.connectTimeout = 1800
                                connection.readTimeout = 1800
                                try {
                                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                                } finally {
                                    connection.disconnect()
                                }
                            }.getOrNull()
                        }
                    }.value
                    if (favicon != null) {
                        Image(
                            bitmap = favicon.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                        )
                    } else {
                        Text("🌐", style = MaterialTheme.typography.headlineLarge)
                    }
                    Text(
                        address.removePrefix("https://"),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Same launcher action for featured and regular allowlisted apps. */
@Composable
private fun ShortcutAppIcon(
    packageName: String,
    appLabel: (String) -> String,
    onApp: (String) -> Unit,
    featured: Boolean,
) {
    Column(
        modifier = Modifier.size(width = if (featured) 84.dp else 72.dp, height = if (featured) 104.dp else 92.dp)
            .clickable { onApp(packageName) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    setImageDrawable(runCatching {
                        context.packageManager.getApplicationIcon(packageName)
                    }.getOrNull())
                }
            },
            modifier = Modifier.size(if (featured) 60.dp else 48.dp),
        )
        Text(
            appLabel(packageName),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
            style = if (featured) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}
