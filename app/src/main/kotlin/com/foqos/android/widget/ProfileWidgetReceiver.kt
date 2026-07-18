package com.foqos.android.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Standard Glance entry point registered in the manifest as an app-widget provider (see
 * `res/xml/profile_widget_info.xml`). Not `@AndroidEntryPoint` - see [WidgetEntryPoint]'s doc for
 * why - [ProfileWidget] itself resolves its dependencies through that `@EntryPoint` instead.
 */
class ProfileWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProfileWidget()
}
