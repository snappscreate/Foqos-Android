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
import androidx.compose.material3.MaterialTheme
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
    onUrl: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("使用できるアプリ・サイト", color = androidx.compose.ui.graphics.Color.White)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 4,
        ) {
            packages.sorted().forEach { pkg ->
                Column(
                    modifier = Modifier.size(width = 72.dp, height = 92.dp)
                        .clickable { onApp(pkg) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    AndroidView(
                        factory = { context ->
                            ImageView(context).apply {
                                setImageDrawable(runCatching {
                                    context.packageManager.getApplicationIcon(pkg)
                                }.getOrNull())
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        appLabel(pkg),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
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
