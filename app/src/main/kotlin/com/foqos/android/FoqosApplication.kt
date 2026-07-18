package com.foqos.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for Foqos. Bootstraps Hilt's dependency graph.
 */
@HiltAndroidApp
class FoqosApplication : Application()
