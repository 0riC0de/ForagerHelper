package com.github.foragerhelper.target

import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.world.World

/**
 * Manages the active [NavigationTarget] lifecycle for ForagerHelper navigation.
 *
 * Responsibilities:
 * - Holding the currently active target (locked focus).
 * - Validating and dismissing stale/completed targets each tick.
 * - Providing scanner-driven target acquisition from a registered [TargetScanner].
 * - Dispatching focus updates by exposing [currentFocusPoint] for [RotationEngine].
 * - Exposing [currentGoalPos] for the [Pathfinder] to navigate toward.
 *
 * ### Lifecycle
 * ```
 * [IDLE] ──setTarget()──► [LOCKED] ──tick() invalidated──► [IDLE]
 *                                  ──tick() completed──────► [IDLE]
 *                                  ──lock()────────────────► [LOCKED] (replace)
 *                                  ──clear()───────────────► [IDLE]
 * ```
 *
 * Thread safety: single-threaded (Minecraft client thread only).
 */
class TargetManager {

    // =========================================================================
    // State
    // =========================================================================

    /** The currently locked navigation target, or null when idle. */
    var activeTarget: NavigationTarget? = null
        private set

    /** Most recently computed goal position (updated each [tick]). */
    var currentGoalPos: net.minecraft.util.math.Vec3d? = null
        private set

    /** Most recently computed focus point (updated each [tick]). */
    var currentFocusPoint: net.minecraft.util.math.Vec3d? = null
        private set

    /** Running count of targets that were locked since construction. */
    var totalTargetsLocked: Int = 0
        private set

    /** Running count of targets that were dismissed as completed. */
    var totalTargetsCompleted: Int = 0
        private set

    /** Running count of targets dismissed because they became invalid. */
    var totalTargetsInvalidated: Int = 0
        private set

    /** Listener called whenever the active target changes (set, cleared, expired). */
    var onTargetChanged: ((NavigationTarget?) -> Unit)? = null

    // =========================================================================
    // Target lock / clear
    // =========================================================================

    /**
     * Locks [target] as the active navigation goal, replacing any existing target.
     * Triggers [onTargetChanged] with the new target.
     */
    fun setTarget(target: NavigationTarget) {
        activeTarget = target
        totalTargetsLocked++
        onTargetChanged?.invoke(target)
    }

    /**
     * Clears the active target, transitioning to [IDLE].
     * Triggers [onTargetChanged] with null.
     */
    fun clear() {
        if (activeTarget != null) {
            activeTarget = null
            currentGoalPos = null
            currentFocusPoint = null
            onTargetChanged?.invoke(null)
        }
    }

    /**
     * Convenience alias for [setTarget] to match the lifecycle diagram nomenclature.
     */
    fun lock(target: NavigationTarget) = setTarget(target)

    // =========================================================================
    // Per-tick update
    // =========================================================================

    /**
     * Called once per client tick. Validates the active target and updates
     * [currentGoalPos] and [currentFocusPoint].
     *
     * - If the target is no longer [NavigationTarget.isValid], it is dismissed.
     * - If the target is [NavigationTarget.isCompleted], it is dismissed.
     * - Otherwise goal/focus positions are refreshed.
     *
     * @return The current [NavigationTarget] after validation, or null if idle.
     */
    fun tick(env: TargetEnvironment): NavigationTarget? {
        val target = activeTarget ?: return null

        // 1. Validity check
        if (!target.isValid(env)) {
            totalTargetsInvalidated++
            clear()
            return null
        }

        // 2. Completion check
        if (target.isCompleted(env)) {
            totalTargetsCompleted++
            clear()
            return null
        }

        // 3. Refresh spatial data
        currentGoalPos = target.getTargetPos(env)
        currentFocusPoint = target.getFocusPoint(env)

        return target
    }

    fun tick(world: World, player: ClientPlayerEntity): NavigationTarget? =
        tick(WorldTargetEnvironment(world, player))

    // =========================================================================
    // Scanner-driven acquisition
    // =========================================================================

    /**
     * Runs [scanner] around [origin] within [radius], locking the highest-priority
     * candidate that passes [filter] (default: all). Returns the locked target or
     * null if no candidates were found.
     *
     * This does NOT replace an existing target unless [forceReplace] is true.
     */
    fun <T : NavigationTarget> acquireFromScanner(
        scanner: TargetScanner<T>,
        env: TargetEnvironment,
        origin: net.minecraft.util.math.Vec3d,
        radius: Double,
        filter: ((T) -> Boolean)? = null,
        forceReplace: Boolean = false
    ): T? {
        if (activeTarget != null && !forceReplace) return null

        val candidates = scanner.scan(env, origin, radius)
        val chosen = if (filter != null) candidates.firstOrNull(filter) else candidates.firstOrNull()
        if (chosen != null) setTarget(chosen)
        return chosen
    }

    fun <T : NavigationTarget> acquireFromScanner(
        scanner: TargetScanner<T>,
        world: World,
        origin: net.minecraft.util.math.Vec3d,
        radius: Double,
        filter: ((T) -> Boolean)? = null,
        forceReplace: Boolean = false
    ): T? {
        if (activeTarget != null && !forceReplace) return null

        val candidates = scanner.scan(world, origin, radius)
        val chosen = if (filter != null) candidates.firstOrNull(filter) else candidates.firstOrNull()
        if (chosen != null) setTarget(chosen)
        return chosen
    }

    // =========================================================================
    // Status / debug
    // =========================================================================

    /** Returns true when a target is currently locked. */
    val hasTarget: Boolean get() = activeTarget != null

    /**
     * Returns a human-readable one-line status string combining target description
     * and manager stats.
     */
    fun describeStatus(player: ClientPlayerEntity): String {
        val target = activeTarget
        return if (target != null) {
            "[TargetManager LOCKED] ${target.describeStatus(player)}"
        } else {
            "[TargetManager IDLE] locked=$totalTargetsLocked completed=$totalTargetsCompleted invalidated=$totalTargetsInvalidated"
        }
    }
}
