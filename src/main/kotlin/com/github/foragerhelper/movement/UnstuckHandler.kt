package com.github.foragerhelper.movement

import com.github.foragerhelper.path.Pathfinder
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

/**
 * Multi-tier recovery stages for stuck navigation handling.
 */
enum class UnstuckTier {
    NONE,
    TIER_1_JUMP_NUDGE,
    TIER_2_REVERSE_STRAFE,
    TIER_3_REPATH_PENALIZE,
    TIER_4_ABORT
}

/**
 * Multi-tier unstuck recovery coordinator.
 *
 * Stagnation is detected when continuous movement is commanded for [stuckThresholdTicks]
 * (default 20 = 1.0s) but horizontal displacement is below [minDisplacementSq] (default 0.0025m = 0.05m).
 *
 * Tier 1: Jump pulse + forward nudge to clear edge snagging.
 * Tier 2: Reverse WASD + lateral strafe to disengage wall/corner obstacles.
 * Tier 3: Apply node penalty to current/adjacent nodes in [Pathfinder] and force immediate re-path.
 * Tier 4: Repeated failures on the same target trigger abort/dismissal to avoid infinite loops.
 */
class UnstuckHandler(
    var stuckThresholdTicks: Int = 20,
    var minDisplacementSq: Double = 0.0025,
    var tier1DurationTicks: Int = 8,
    var tier2DurationTicks: Int = 12,
    var maxTier3Attempts: Int = 3
) {
    var currentTier: UnstuckTier = UnstuckTier.NONE
        private set

    var recoveryTicksRemaining: Int = 0
        private set

    var consecutiveStuckCount: Int = 0
        private set

    var activeMovingTicks: Int = 0
        private set

    private var lastRecordedPos: Vec3d? = null
    private var strafeRightDirection: Boolean = true

    val isRecovering: Boolean
        get() = currentTier != UnstuckTier.NONE

    fun onTargetChanged() {
        reset()
        consecutiveStuckCount = 0
    }

    fun reset() {
        currentTier = UnstuckTier.NONE
        recoveryTicksRemaining = 0
        activeMovingTicks = 0
        lastRecordedPos = null
    }

    /**
     * Updates stagnation tracking and returns a [MovementInput] override if currently recovering.
     */
    fun tick(
        playerPos: Vec3d,
        isMoving: Boolean,
        pathfinder: Pathfinder? = null,
        onRepathRequested: (() -> Unit)? = null,
        onAbortRequested: (() -> Unit)? = null
    ): MovementInput? {
        // If actively executing a timed recovery maneuver:
        if (recoveryTicksRemaining > 0) {
            recoveryTicksRemaining--
            val input = when (currentTier) {
                UnstuckTier.TIER_1_JUMP_NUDGE -> {
                    MovementInput(forward = true, jump = true)
                }
                UnstuckTier.TIER_2_REVERSE_STRAFE -> {
                    MovementInput(
                        back = true,
                        left = !strafeRightDirection,
                        right = strafeRightDirection,
                        jump = recoveryTicksRemaining % 4 == 0
                    )
                }
                else -> null
            }
            if (recoveryTicksRemaining == 0) {
                currentTier = UnstuckTier.NONE
                activeMovingTicks = 0
                lastRecordedPos = playerPos
            }
            return input
        }

        if (!isMoving) {
            activeMovingTicks = 0
            lastRecordedPos = playerPos
            return null
        }

        val lastPos = lastRecordedPos
        if (lastPos == null) {
            lastRecordedPos = playerPos
            activeMovingTicks = 1
            return null
        }

        val distSq = (playerPos.x - lastPos.x) * (playerPos.x - lastPos.x) +
                     (playerPos.z - lastPos.z) * (playerPos.z - lastPos.z)

        if (distSq < minDisplacementSq) {
            activeMovingTicks++
        } else {
            activeMovingTicks = 0
            lastRecordedPos = playerPos
            if (consecutiveStuckCount > 0) {
                consecutiveStuckCount = 0
            }
            return null
        }

        if (activeMovingTicks >= stuckThresholdTicks) {
            activeMovingTicks = 0
            consecutiveStuckCount++
            strafeRightDirection = !strafeRightDirection

            return escalate(playerPos, pathfinder, onRepathRequested, onAbortRequested)
        }

        return null
    }

    private fun escalate(
        playerPos: Vec3d,
        pathfinder: Pathfinder?,
        onRepathRequested: (() -> Unit)?,
        onAbortRequested: (() -> Unit)?
    ): MovementInput? {
        return when {
            consecutiveStuckCount == 1 -> {
                currentTier = UnstuckTier.TIER_1_JUMP_NUDGE
                recoveryTicksRemaining = tier1DurationTicks
                MovementInput(forward = true, jump = true)
            }
            consecutiveStuckCount == 2 -> {
                currentTier = UnstuckTier.TIER_2_REVERSE_STRAFE
                recoveryTicksRemaining = tier2DurationTicks
                MovementInput(back = true, right = strafeRightDirection, left = !strafeRightDirection)
            }
            consecutiveStuckCount in 3..(maxTier3Attempts + 2) -> {
                currentTier = UnstuckTier.TIER_3_REPATH_PENALIZE
                val blockPos = BlockPos.ofFloored(playerPos)
                pathfinder?.penalizeNode(blockPos, 100.0f)
                pathfinder?.penalizeNode(blockPos.up(), 100.0f)
                onRepathRequested?.invoke()
                currentTier = UnstuckTier.NONE
                activeMovingTicks = 0
                null
            }
            else -> {
                currentTier = UnstuckTier.TIER_4_ABORT
                onAbortRequested?.invoke()
                reset()
                null
            }
        }
    }
}
