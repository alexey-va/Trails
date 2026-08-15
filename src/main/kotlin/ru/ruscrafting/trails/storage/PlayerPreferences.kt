package ru.ruscrafting.trails.storage

data class PlayerPreferences(
    val enabled: Boolean? = null,
    val boost: Boolean? = null,
) {
    fun trailsEnabled(default: Boolean): Boolean = enabled ?: default

    fun boostEnabled(default: Boolean): Boolean = boost ?: default
}
