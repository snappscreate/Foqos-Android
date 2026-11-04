package com.foqos.android.ui.block

/** Stable identifiers for launch destinations displayed on the focus shield. */
object FocusShortcut {
    const val PHONE = "phone:"
    const val APP_PREFIX = "app:"
    const val URL_PREFIX = "url:"

    fun app(packageName: String) = APP_PREFIX + packageName
    fun url(address: String) = URL_PREFIX + address

    fun valid(id: String, packages: Set<String>, urls: Set<String>): Boolean = when {
        id == PHONE -> true
        id.startsWith(APP_PREFIX) -> id.removePrefix(APP_PREFIX) in packages
        id.startsWith(URL_PREFIX) -> id.removePrefix(URL_PREFIX) in urls
        else -> false
    }

    fun all(packages: Set<String>, urls: Set<String>): Set<String> =
        packages.map(::app).toSet() + urls.map(::url).toSet() + PHONE
}
