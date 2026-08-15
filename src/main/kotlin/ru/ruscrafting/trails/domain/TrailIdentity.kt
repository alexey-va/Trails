package ru.ruscrafting.trails.domain

data class TrailIdentity(
    val trailName: String,
    val stageIndex: Int,
) {
    init {
        require(trailName.isNotBlank()) { "trailName must not be blank" }
        require(':' !in trailName) { "trailName must not contain ':'" }
        require(stageIndex >= 0) { "stageIndex must be non-negative" }
    }

    fun serialize(): String = "$trailName:$stageIndex"

    companion object {
        fun parse(value: String?): TrailIdentity? {
            if (value.isNullOrBlank()) return null
            val separator = value.lastIndexOf(':')
            if (separator <= 0 || separator == value.lastIndex) return null
            val name = value.substring(0, separator)
            val index = value.substring(separator + 1).toIntOrNull() ?: return null
            return runCatching { TrailIdentity(name, index) }.getOrNull()
        }
    }
}
