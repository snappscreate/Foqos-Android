@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.foqos.android.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foqos.android.R

/**
 * Searchable, checkbox multi-select list of every launchable app on the device (Foqos itself
 * excluded), embedded in the profile editor's "Apps" section. Shares [ProfileEditViewModel] with
 * the editor screen via a nav-graph-scoped `hiltViewModel`, so toggling a checkbox here updates
 * the same draft.
 */
@Composable
fun AppPickerScreen(
    viewModel: ProfileEditViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_done))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.app_picker_allow_mode_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (uiState.draft.allowModeEnabled) {
                            stringResource(R.string.app_picker_allow_mode_on_body)
                        } else {
                            stringResource(R.string.app_picker_allow_mode_off_body)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = uiState.draft.allowModeEnabled, onCheckedChange = viewModel::setAllowMode)
            }

            OutlinedTextField(
                value = uiState.appSearchQuery,
                onValueChange = viewModel::setAppSearchQuery,
                label = { Text(stringResource(R.string.app_picker_search_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            val filtered = uiState.installedApps.filter {
                uiState.appSearchQuery.isBlank() || it.label.contains(uiState.appSearchQuery, ignoreCase = true)
            }

            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in uiState.draft.blockedPackages
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val icon = rememberAppIconBitmap(app.packageName)
                        if (icon != null) {
                            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(36.dp))
                        } else {
                            Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(36.dp))
                        }
                        Text(text = app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Checkbox(checked = checked, onCheckedChange = { viewModel.toggleApp(app.packageName) })
                    }
                }
            }
        }
    }
}
