@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.foqos.android.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.ui.theme.FoqosTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shown by the OS when the widget is first placed (`ACTION_APPWIDGET_CONFIGURE`), and re-openable
 * later (e.g. from [WidgetUiState.NotConfigured]'s tap target) to change the pinned profile.
 *
 * **v1 simplification**: the pinned profile id is stored as a single global
 * [SettingsDataStore.pinnedWidgetProfileId] rather than per-`appWidgetId` - acceptable because this
 * app only ever expects one widget instance in practice, and per-widget storage (a
 * `SharedPreferences` file keyed by `appWidgetId`, mirrored to every [ProfileWidget] read) is
 * meaningfully more code for a feature nobody has asked for yet. Documented here rather than
 * silently done: adding more than one distinctly-pinned widget instance is *not* supported by this
 * implementation - placing a second widget just repoints the same global pin.
 */
@AndroidEntryPoint
class ProfileWidgetConfigActivity : ComponentActivity() {

    @Inject lateinit var profileRepository: ProfileRepository

    @Inject lateinit var settingsDataStore: SettingsDataStore

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Standard app-widget-config contract: default to CANCELED so an early exit (back press,
        // process death) leaves the host free to discard the pending placement.
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setContent {
            FoqosTheme {
                ConfigScreen(
                    profileRepository = profileRepository,
                    settingsDataStore = settingsDataStore,
                    onSave = { profileId -> save(profileId) },
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun save(profileId: String) {
        lifecycleScope.launch {
            settingsDataStore.setPinnedWidgetProfileId(profileId)
            ProfileWidget().updateAll(applicationContext)

            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_OK, resultValue)
            finish()
        }
    }
}

@Composable
private fun ConfigScreen(
    profileRepository: ProfileRepository,
    settingsDataStore: SettingsDataStore,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val profiles by profileRepository.observeAll().collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }

    // Pre-select whatever is already pinned, once profiles have loaded.
    LaunchedEffect(Unit) {
        selectedId = settingsDataStore.pinnedWidgetProfileId.first()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose a profile") },
                actions = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (profiles.isEmpty()) {
                Text("Create a profile in the app first.")
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    profiles.forEach { profile -> ProfileRow(profile, selectedId) { selectedId = profile.id } }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                enabled = selectedId != null,
                onClick = { selectedId?.let(onSave) },
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun ProfileRow(profile: BlockedProfile, selectedId: String?, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = profile.id == selectedId, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = profile.id == selectedId, onClick = onSelect)
        Spacer(modifier = Modifier.padding(start = 8.dp))
        Text(profile.name)
    }
}
