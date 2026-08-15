package ru.ruscrafting.trails.bukkit

enum class TrailToolKind(
    val id: String,
) {
    ADVANCE("advance"),
    INSPECT("inspect"),
    ;

    companion object {
        fun fromId(id: String): TrailToolKind? = entries.firstOrNull { it.id == id }
    }
}
