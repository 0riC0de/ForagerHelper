package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * High-performance swept bounding-box line-of-sight (LOS) checker and path smoother.
 *
 * Evaluates the player's 3D bounding box (0.6 width x 1.8 height) with safety clearance
 * margins along direct trajectories to eliminate diagonal corner snagging and validate
 * continuous ground support.
 */
object SweptBoxLOS {

    const val PLAYER_WIDTH: Double = 0.6
    const val PLAYER_HEIGHT: Double = 1.8
    const val DEFAULT_CLEARANCE_MARGIN: Double = 0.05
    const val DEFAULT_STEP_INTERVAL: Double = 0.20
    const val FOOT_CLEARANCE: Double = 0.02
    const val DEFAULT_MAX_STEP_UP: Double = 0.60
    const val DEFAULT_MAX_SAFE_DROP: Double = 1.10
    const val DEFAULT_GROUND_PROBE_RADIUS: Double = 0.15
    const val DEFAULT_MAX_LOOKAHEAD: Int = 24

    // =========================================================================
    // Line-of-Sight Evaluation
    // =========================================================================

    /**
     * Checks whether a player can walk directly from [from] to [to] in a straight line.
     * Evaluates full swept bounding-box clearance and continuous ground support.
     */
    fun hasLineOfSight(
        env: PathEnvironment,
        from: Vec3d,
        to: Vec3d,
        penaltyMap: NodePenaltyMap? = null,
        clearanceMargin: Double = DEFAULT_CLEARANCE_MARGIN,
        stepInterval: Double = DEFAULT_STEP_INTERVAL,
        requireGroundSupport: Boolean = true,
        maxSafeDrop: Double = DEFAULT_MAX_SAFE_DROP,
        maxStepUp: Double = DEFAULT_MAX_STEP_UP
    ): Boolean {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance < 1e-4) return true

        val halfWidth = (PLAYER_WIDTH / 2.0) + clearanceMargin

        // 1. Broadphase fast rejection: if the entire corridor is empty, skip sub-step obstacle checks
        val minX = min(from.x, to.x) - halfWidth
        val maxX = max(from.x, to.x) + halfWidth
        val minY = min(from.y, to.y) + FOOT_CLEARANCE
        val maxY = max(from.y, to.y) + PLAYER_HEIGHT
        val minZ = min(from.z, to.z) - halfWidth
        val maxZ = max(from.z, to.z) + halfWidth
        val broadphaseBox = Box(minX, minY, minZ, maxX, maxY, maxZ)

        val broadphaseClear = env.isPassable(broadphaseBox)

        // 2. Narrowphase discrete sub-stepping
        val steps = max(1, ceil(distance / stepInterval).toInt())
        val stepVec = Vec3d(dx / steps, dy / steps, dz / steps)

        for (i in 0..steps) {
            val currX = from.x + stepVec.x * i
            val currY = from.y + stepVec.y * i
            val currZ = from.z + stepVec.z * i
            val pos = Vec3d(currX, currY, currZ)

            // Dynamic penalty check
            if (penaltyMap != null) {
                val block = BlockPos.ofFloored(currX, currY, currZ)
                if (penaltyMap.hasPenalty(block)) {
                    return false
                }
            }

            // Obstacle collision check (only needed if broadphase contained obstacles)
            if (!broadphaseClear) {
                val playerBox = Box(
                    currX - halfWidth, currY + FOOT_CLEARANCE, currZ - halfWidth,
                    currX + halfWidth, currY + PLAYER_HEIGHT, currZ + halfWidth
                )
                if (!env.isPassable(playerBox)) {
                    return false
                }
            }

            // Ground support check
            if (requireGroundSupport) {
                if (!hasGroundSupport(env, pos, DEFAULT_GROUND_PROBE_RADIUS, maxSafeDrop, maxStepUp)) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * Minecraft World convenience overload for [hasLineOfSight].
     */
    fun hasLineOfSight(
        world: World,
        from: Vec3d,
        to: Vec3d,
        clearanceMargin: Double = DEFAULT_CLEARANCE_MARGIN,
        stepInterval: Double = DEFAULT_STEP_INTERVAL,
        requireGroundSupport: Boolean = true,
        maxSafeDrop: Double = DEFAULT_MAX_SAFE_DROP,
        maxStepUp: Double = DEFAULT_MAX_STEP_UP
    ): Boolean {
        return hasLineOfSight(
            WorldPathEnvironment(world), from, to, null,
            clearanceMargin, stepInterval, requireGroundSupport, maxSafeDrop, maxStepUp
        )
    }

    /**
     * Overload for [hasLineOfSight] with [PathEnvironment] without penaltyMap.
     */
    fun hasLineOfSight(
        env: PathEnvironment,
        from: Vec3d,
        to: Vec3d,
        clearanceMargin: Double,
        stepInterval: Double = DEFAULT_STEP_INTERVAL,
        requireGroundSupport: Boolean = true,
        maxSafeDrop: Double = DEFAULT_MAX_SAFE_DROP,
        maxStepUp: Double = DEFAULT_MAX_STEP_UP
    ): Boolean {
        return hasLineOfSight(
            env, from, to, null,
            clearanceMargin, stepInterval, requireGroundSupport, maxSafeDrop, maxStepUp
        )
    }

    /**
     * Headless/decoupled line-of-sight check using custom collision predicates.
     */
    fun hasLineOfSight(
        isObstacleBlocked: (Box) -> Boolean,
        isGroundSupported: ((Vec3d) -> Boolean)?,
        from: Vec3d,
        to: Vec3d,
        clearanceMargin: Double = DEFAULT_CLEARANCE_MARGIN,
        stepInterval: Double = DEFAULT_STEP_INTERVAL
    ): Boolean {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance < 1e-4) return true

        val halfWidth = (PLAYER_WIDTH / 2.0) + clearanceMargin
        val steps = max(1, ceil(distance / stepInterval).toInt())
        val stepVec = Vec3d(dx / steps, dy / steps, dz / steps)

        for (i in 0..steps) {
            val currX = from.x + stepVec.x * i
            val currY = from.y + stepVec.y * i
            val currZ = from.z + stepVec.z * i
            val pos = Vec3d(currX, currY, currZ)

            val playerBox = Box(
                currX - halfWidth, currY + FOOT_CLEARANCE, currZ - halfWidth,
                currX + halfWidth, currY + PLAYER_HEIGHT, currZ + halfWidth
            )
            if (isObstacleBlocked(playerBox)) {
                return false
            }

            if (isGroundSupported != null && !isGroundSupported(pos)) {
                return false
            }
        }

        return true
    }

    // =========================================================================
    // Ground Support Validation
    // =========================================================================

    /**
     * Checks if solid, walkable ground exists directly beneath the player at [pos].
     */
    fun hasGroundSupport(
        env: PathEnvironment,
        pos: Vec3d,
        probeRadius: Double = DEFAULT_GROUND_PROBE_RADIUS,
        maxSafeDrop: Double = DEFAULT_MAX_SAFE_DROP,
        maxStepUp: Double = DEFAULT_MAX_STEP_UP
    ): Boolean {
        val probeBox = Box(
            pos.x - probeRadius, pos.y - maxSafeDrop, pos.z - probeRadius,
            pos.x + probeRadius, pos.y + maxStepUp, pos.z + probeRadius
        )

        val collisions = env.getBlockCollisions(probeBox)
        var supported = false

        for (box in collisions) {
            if (box.maxY in (pos.y - maxSafeDrop - 0.05)..(pos.y + maxStepUp + 0.05)) {
                supported = true
                break
            }
        }

        if (!supported) {
            // Fallback: check stand height directly at center position
            val centerBlock = BlockPos.ofFloored(pos.x, pos.y, pos.z)
            val standH = env.getStandHeight(centerBlock)
            if (standH != null && standH in (pos.y - maxSafeDrop - 0.05)..(pos.y + maxStepUp + 0.05)) {
                supported = true
            }
        }

        if (!supported) return false

        // Check for lethal/hazardous ground blocks
        val blockUnder = BlockPos.ofFloored(pos.x, pos.y - 0.2, pos.z)
        if (env.isHazard(blockUnder)) {
            return false
        }

        return true
    }

    // =========================================================================
    // Path Smoothing Algorithm
    // =========================================================================

    /**
     * Smoothes raw A* waypoints into direct straight line segments using greedy lookahead.
     * Respects [isAnchorNode] to preserve mandatory takeoff/landing points (e.g. jumps, drops, parkour).
     */
    fun smoothPath(
        env: PathEnvironment,
        rawPath: List<Vec3d>,
        penaltyMap: NodePenaltyMap? = null,
        isAnchorNode: ((Int) -> Boolean)? = null,
        clearanceMargin: Double = DEFAULT_CLEARANCE_MARGIN,
        requireGroundSupport: Boolean = true,
        maxLookahead: Int = DEFAULT_MAX_LOOKAHEAD
    ): List<Vec3d> {
        if (rawPath.size <= 2) return rawPath

        val smoothed = ArrayList<Vec3d>(rawPath.size)
        smoothed.add(rawPath[0])

        var currentIdx = 0
        while (currentIdx < rawPath.lastIndex) {
            var nextIdx = currentIdx + 1
            val maxTarget = min(rawPath.lastIndex, currentIdx + maxLookahead)

            for (candidateIdx in maxTarget downTo currentIdx + 2) {
                // If any node strictly between current and candidate is an anchor node, do NOT skip past it
                var intermediateHasAnchor = false
                if (isAnchorNode != null) {
                    for (k in (currentIdx + 1) until candidateIdx) {
                        if (isAnchorNode(k)) {
                            intermediateHasAnchor = true
                            break
                        }
                    }
                }
                if (intermediateHasAnchor) continue

                val from = rawPath[currentIdx]
                val to = rawPath[candidateIdx]

                val dy = abs(to.y - from.y)
                val dx = to.x - from.x
                val dz = to.z - from.z
                val horizDist = sqrt(dx * dx + dz * dz)

                // Slope check: do not bridge steep vertical cliffs in a single segment
                if (dy > 1.25 && dy > horizDist * 1.05) continue

                if (hasLineOfSight(env, from, to, penaltyMap, clearanceMargin, DEFAULT_STEP_INTERVAL, requireGroundSupport)) {
                    nextIdx = candidateIdx
                    break
                }
            }

            smoothed.add(rawPath[nextIdx])
            currentIdx = nextIdx
        }

        return smoothed
    }

    /**
     * Convenience overload for smoothing without penaltyMap.
     */
    fun smoothPath(
        env: PathEnvironment,
        rawPath: List<Vec3d>,
        isAnchorNode: ((Int) -> Boolean)?,
        clearanceMargin: Double = DEFAULT_CLEARANCE_MARGIN,
        requireGroundSupport: Boolean = true,
        maxLookahead: Int = DEFAULT_MAX_LOOKAHEAD
    ): List<Vec3d> {
        return smoothPath(env, rawPath, null, isAnchorNode, clearanceMargin, requireGroundSupport, maxLookahead)
    }

    /**
     * Minecraft World convenience overload for [smoothPath].
     */
    fun smoothPath(
        world: World,
        rawPath: List<Vec3d>,
        isAnchorNode: ((Int) -> Boolean)? = null,
        clearanceMargin: Double = DEFAULT_CLEARANCE_MARGIN,
        requireGroundSupport: Boolean = true,
        maxLookahead: Int = DEFAULT_MAX_LOOKAHEAD
    ): List<Vec3d> {
        return smoothPath(
            WorldPathEnvironment(world), rawPath, isAnchorNode,
            clearanceMargin, requireGroundSupport, maxLookahead
        )
    }

    /**
     * Convenience overload for smoothing paths given as [BlockPos].
     * Centers waypoints horizontally at (+0.5, +0.5).
     */
    fun smoothBlockPath(
        env: PathEnvironment,
        rawPath: List<BlockPos>,
        clearanceMargin: Double = DEFAULT_CLEARANCE_MARGIN,
        requireGroundSupport: Boolean = true,
        maxLookahead: Int = DEFAULT_MAX_LOOKAHEAD
    ): List<Vec3d> {
        val vecPath = rawPath.map { Vec3d(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
        return smoothPath(env, vecPath, null, clearanceMargin, requireGroundSupport, maxLookahead)
    }
}
