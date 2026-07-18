package com.foqos.android.ui.navigation

/** Every destination in [FoqosNavHost], in one place so screens never hand-build route strings. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val PROFILE_EDIT_PATTERN = "profileEdit?profileId={profileId}"
    const val APP_PICKER = "appPicker"
    const val INSIGHTS_PATTERN = "insights/{profileId}"
    const val ACTIVE_SESSION = "activeSession"
    const val SETTINGS = "settings"
    const val EXPORT = "export"

    const val ARG_PROFILE_ID = "profileId"

    /** Route for creating a brand-new profile (no id yet). */
    fun profileEditNew(): String = "profileEdit"

    /** Route for editing an existing profile by [profileId]. */
    fun profileEdit(profileId: String): String = "profileEdit?profileId=$profileId"

    fun insights(profileId: String): String = "insights/$profileId"
}
