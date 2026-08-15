package ru.ruscrafting.trails.service

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

interface WalkSpeedTarget {
    val id: UUID
    var walkSpeed: Float
}

class SpeedController {
    private data class State(
        var baseline: Float,
        var lastApplied: Float,
        var multiplier: Double,
        var immediate: Boolean,
    )

    private val states = ConcurrentHashMap<UUID, State>()

    fun target(subject: WalkSpeedTarget, multiplier: Double, immediate: Boolean = false) {
        require(multiplier in 0.0..5.0) { "multiplier must be between 0 and 5" }
        states.compute(subject.id) { _, existing ->
            val state = existing ?: State(subject.walkSpeed, subject.walkSpeed, multiplier, immediate)
            detectExternalChange(subject, state)
            state.multiplier = multiplier
            state.immediate = immediate
            state
        }
    }

    fun tick(subjects: Map<UUID, WalkSpeedTarget>, step: Float) {
        require(step > 0.0F) { "step must be positive" }
        states.entries.forEach { (uuid, state) ->
            val subject = subjects[uuid]
            if (subject == null) {
                states.remove(uuid)
                return@forEach
            }
            detectExternalChange(subject, state)
            val target = (state.baseline * state.multiplier).coerceIn(-1.0, 1.0).toFloat()
            val next =
                when {
                    state.immediate -> target
                    subject.walkSpeed < target -> minOf(subject.walkSpeed + step, target)
                    subject.walkSpeed > target -> maxOf(subject.walkSpeed - step, target)
                    else -> target
                }
            subject.walkSpeed = next
            state.lastApplied = next
            if (approximately(next, state.baseline) && approximately(state.multiplier, 1.0)) states.remove(uuid)
        }
    }

    fun restore(subject: WalkSpeedTarget) {
        val state = states.remove(subject.id) ?: return
        if (approximately(subject.walkSpeed, state.lastApplied)) subject.walkSpeed = state.baseline
    }

    fun restoreAll(subjects: Map<UUID, WalkSpeedTarget>) {
        states.keys.toList().forEach { uuid -> subjects[uuid]?.let(::restore) ?: states.remove(uuid) }
    }

    fun isActive(uuid: UUID): Boolean = states.containsKey(uuid)

    private fun detectExternalChange(subject: WalkSpeedTarget, state: State) {
        if (!approximately(subject.walkSpeed, state.lastApplied)) {
            state.baseline = subject.walkSpeed
            state.lastApplied = subject.walkSpeed
        }
    }

    private fun approximately(left: Float, right: Float): Boolean = abs(left - right) < EPSILON

    private fun approximately(left: Double, right: Double): Boolean = kotlin.math.abs(left - right) < EPSILON

    companion object {
        private const val EPSILON = 0.00001
    }
}
