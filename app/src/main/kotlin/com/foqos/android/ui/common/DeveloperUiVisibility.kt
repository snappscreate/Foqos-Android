package com.foqos.android.ui.common

/**
 * Switch to true to restore diagnostic buttons during development.
 *
 * This only controls whether UI entry points are visible. The actual location
 * simulation, NFC scan simulation, and reset implementations remain intact.
 */
object DeveloperUiVisibility {
    const val showDiagnosticControls: Boolean = false
}
