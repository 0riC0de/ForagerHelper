package com.github.foragerhelper.movement

import com.github.foragerhelper.path.AStarPathfinder
import com.github.foragerhelper.path.PathEnvironment
import com.github.foragerhelper.path.PathResult
import com.github.foragerhelper.path.Pathfinder
import com.github.foragerhelper.path.SweptBoxLOS
import com.github.foragerhelper.path.WorldPathEnvironment
import com.github.foragerhelper.rotation.RotationEngine
import com.github.foragerhelper.target.BlockTarget
import com.github.foragerhelper.target.NavigationTarget
import com.github.foragerhelper.target.PositionTarget
import com.github.foragerhelper.target.TargetEnvironment
import com.github.foragerhelper.target.WorldTargetEnvironment
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Navigation state of the movement controller.
 */
enum class MovementState {
    IDLE,
    PATHING,
    IN_REACH,
    RECOVERING,
    COMPLETED
}

/**
 * Decoupled synthetic input structure representing directional WASD, jump, and sneak key states.
 */
data class MovementInput(
    val forward: Boolean = false,
    val back: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
    val jump: Boolean = false,
    val sneak: Boolean = false,
    val sprint: Boolean = false
) {
    val hasMotion: Boolean get() = forward || back || left || right
}

/**
 * Decoupled movement controller interface matching PROJECT.md.
 */
interface MovementController {
    fun setDestination(target: NavigationTarget)
    fun tick(client: MinecraftClient)
    fun tick(env: TargetEnvironment, playerYaw: Float): MovementInput
    fun stop()
    val isNavigating: Boolean
    val currentStatus: String
    val state: MovementState
    val currentWaypoints: List<Vec3d>
    val currentWaypointIndex: Int
    val activeTarget: NavigationTarget?

    companion object : MovementController {
        private var delegate: MovementController = DefaultMovementController()

        fun setDelegate(controller: MovementController) {
            this.delegate = controller
        }

        fun getDelegate(): MovementController = delegate

        override fun setDestination(target: NavigationTarget) = delegate.setDestination(target)
        override fun tick(client: MinecraftClient) = delegate.tick(client)
        override fun tick(env: TargetEnvironment, playerYaw: Float): MovementInput = delegate.tick(env, playerYaw)
        override fun stop() = delegate.stop()
        override val isNavigating: Boolean get() = delegate.isNavigating
        override val currentStatus: String get() = delegate.currentStatus
        override val state: MovementState get() = delegate.state
        override val currentWaypoints: List<Vec3d> get() = delegate.currentWaypoints
        override val currentWaypointIndex: Int get() = delegate.currentWaypointIndex
        override val activeTarget: NavigationTarget? get() = delegate.activeTarget
    }
}

/**
 * Production implementation of [MovementController] translating waypoint vectors
 * into local WASD keypresses decoupled from camera yaw, with unstuck recovery and
 * synchronized [RotationEngine] looking.
 */
class DefaultMovementController(
    val pathfinder: Pathfinder = AStarPathfinder(maxExpansions = 10000, maxComputeTimeMs = 100L, maxHorizontalRange = 96),
    val rotationEngine: RotationEngine = RotationEngine.getDelegate(),
    val unstuckHandler: UnstuckHandler = UnstuckHandler(),
    var repathIntervalTicks: Int = 20,
    var waypointRadius: Double = 0.65,
    private val clientProvider: () -> MinecraftClient? = { runCatching { MinecraftClient.getInstance() }.getOrNull() }
) : MovementController {

    override var activeTarget: NavigationTarget? = null
        private set

    override var state: MovementState = MovementState.IDLE
        private set

    override var currentWaypoints: List<Vec3d> = emptyList()
        private set

    override var currentWaypointIndex: Int = 0
        private set

    var lastComputedInput: MovementInput = MovementInput()
        private set

    var pathEnvironmentProvider: ((TargetEnvironment) -> PathEnvironment)? = null

    var onDestinationReached: (() -> Unit)? = null
    var onNavigationFailed: ((String) -> Unit)? = null

    private var ticksSincePath = 0
    private var lastGoalPos: Vec3d? = null

    var rightClearanceTicks: Int = 0
        private set
    var leftClearanceTicks: Int = 0
        private set

    override val isNavigating: Boolean
        get() = state == MovementState.PATHING || state == MovementState.RECOVERING

    override val currentStatus: String
        get() = when (state) {
            MovementState.IDLE -> "Idle"
            MovementState.PATHING -> "Pathing [${currentWaypointIndex}/${currentWaypoints.size}]"
            MovementState.IN_REACH -> "In Reach"
            MovementState.RECOVERING -> "Recovering (${unstuckHandler.currentTier})"
            MovementState.COMPLETED -> "Completed"
        }

    override fun setDestination(target: NavigationTarget) {
        activeTarget = target
        currentWaypoints = emptyList()
        currentWaypointIndex = 0
        ticksSincePath = repathIntervalTicks // Force immediate initial path computation
        lastGoalPos = null
        rightClearanceTicks = 0
        leftClearanceTicks = 0
        unstuckHandler.onTargetChanged()
        state = MovementState.PATHING
    }

    fun setWaypointsForTest(waypoints: List<Vec3d>, index: Int = 0) {
        this.currentWaypoints = waypoints
        this.currentWaypointIndex = index
        this.ticksSincePath = 0
    }

    override fun stop() {
        activeTarget = null
        currentWaypoints = emptyList()
        currentWaypointIndex = 0
        ticksSincePath = 0
        lastGoalPos = null
        rightClearanceTicks = 0
        leftClearanceTicks = 0
        unstuckHandler.reset()
        rotationEngine.reset()
        state = MovementState.IDLE
        lastComputedInput = MovementInput()

        val client = clientProvider()
        if (client != null) {
            releaseKeys(client)
        }
    }

    override fun tick(client: MinecraftClient) {
        val player = client.player ?: return stop()
        val world = client.world ?: return stop()

        if (client.currentScreen != null) {
            releaseKeys(client)
            return
        }

        val env = WorldTargetEnvironment(world, player)
        val input = tick(env, player.yaw)

        applyKeys(client, input)
    }

    override fun tick(env: TargetEnvironment, playerYaw: Float): MovementInput {
        val target = activeTarget
        if (target == null) {
            state = MovementState.IDLE
            lastComputedInput = MovementInput()
            return lastComputedInput
        }

        // 1. Target validity check
        if (!target.isValid(env)) {
            val reason = "Target invalidated"
            onNavigationFailed?.invoke(reason)
            stop()
            return MovementInput()
        }

        // 2. Target completion check
        if (target.isCompleted(env)) {
            state = MovementState.COMPLETED
            rotationEngine.setTarget(target.getFocusPoint(env))
            rotationEngine.setPathTangent(null)
            onDestinationReached?.invoke()
            lastComputedInput = MovementInput()
            return lastComputedInput
        }

        // 3. Interaction Reach check: do not prematurely freeze on high ground if still following path
        val targetPos = target.getTargetPos(env)
        val isNavigatingPath = currentWaypoints.isNotEmpty() && currentWaypointIndex < currentWaypoints.size - 1
        val isHighGroundLedge = targetPos != null && (env.playerPos.y - targetPos.y) > 1.6
        if (target !is PositionTarget && target.isInReach(env) && !isNavigatingPath && !isHighGroundLedge) {
            state = MovementState.IN_REACH
            rotationEngine.setTarget(target.getFocusPoint(env))
            rotationEngine.setPathTangent(null)
            lastComputedInput = MovementInput(sneak = true)
            return lastComputedInput
        }

        val goalPos = if (target is BlockTarget) {
            resolveBlockGoalPos(env, target)
        } else {
            targetPos
        }
        if (goalPos == null) {
            stop()
            return MovementInput()
        }

        val distToGoalSq = (goalPos.x - env.playerPos.x) * (goalPos.x - env.playerPos.x) + (goalPos.z - env.playerPos.z) * (goalPos.z - env.playerPos.z)
        val dyToGoal = goalPos.y - env.playerPos.y
        val isUnderDestination = distToGoalSq <= 2.25 && dyToGoal > 1.25

        if (isUnderDestination) {
            // Player below destination block: only repath if waypoints are exhausted
            if (currentWaypoints.isEmpty() || currentWaypointIndex >= currentWaypoints.size) {
                ticksSincePath = repathIntervalTicks
                computePath(env, goalPos)
            }
        }

        val yawRad = Math.toRadians(playerYaw.toDouble())
        val fwdX = -sin(yawRad)
        val fwdZ = cos(yawRad)
        val rightX = -cos(yawRad)
        val rightZ = -sin(yawRad)

        // Evaluate side obstacles and update clearance ticks BEFORE waypoint progression
        val rightSideBlocked = isSideBlocked(env, rightX, rightZ, fwdX, fwdZ)
        val leftSideBlocked = isSideBlocked(env, -rightX, -rightZ, fwdX, fwdZ)

        if (rightSideBlocked) {
            rightClearanceTicks = 6
        } else if (rightClearanceTicks > 0) {
            rightClearanceTicks--
        }

        if (leftSideBlocked) {
            leftClearanceTicks = 6
        } else if (leftClearanceTicks > 0) {
            leftClearanceTicks--
        }

        ticksSincePath++

        // 4. Path computation / periodic re-routing
        val goalMoved = lastGoalPos != null && goalPos.squaredDistanceTo(lastGoalPos!!) > 4.0
        val isClimbing = currentWaypoints.isNotEmpty() && currentWaypointIndex < currentWaypoints.size && dyToGoal > 1.0
        val needsPath = currentWaypoints.isEmpty() ||
                        currentWaypointIndex >= currentWaypoints.size ||
                        goalMoved ||
                        (ticksSincePath >= repathIntervalTicks && !isClimbing)

        if (needsPath) {
            computePath(env, goalPos)
        }

        // 5. Waypoint progression with fallsafe drop support and looking-block traverse
        while (currentWaypointIndex < currentWaypoints.size) {
            val wp = currentWaypoints[currentWaypointIndex]
            val dx = wp.x - env.playerPos.x
            val dz = wp.z - env.playerPos.z
            val dy = wp.y - env.playerPos.y
            val distSq = dx * dx + dz * dz

            val hasNextWp = currentWaypointIndex < currentWaypoints.size - 1
            val nextWpVisible = if (hasNextWp) {
                val nextWp = currentWaypoints[currentWaypointIndex + 1]
                val nextEye = Vec3d(nextWp.x, nextWp.y + 1.3, nextWp.z)
                hasLineOfSightRay(env, env.playerEyePos, nextEye)
            } else true

            // Corner wall protection: if turning around an obscured corner or side obstacle present, do not cut corner early
            val sideHazard = rightSideBlocked || leftSideBlocked || rightClearanceTicks > 0 || leftClearanceTicks > 0
            val effectiveRadius = if (!nextWpVisible || sideHazard) 0.35 else waypointRadius
            val reachedFlat = distSq <= effectiveRadius * effectiveRadius && Math.abs(dy) <= 1.25

            // Safe drop progression: advance intermediate drop waypoint if aligned horizontally and safe
            val reachedDrop = hasNextWp &&
                distSq <= (waypointRadius * 1.5) * (waypointRadius * 1.5) &&
                dy in -3.5..-0.5 && isDropSafe(env, wp)

            // Intermediate looking block overhead: if horizontally aligned under looking block, count as traversed and continue.
            // Only advance overhead waypoints if destination itself is NOT above player (i.e. not climbing)
            val goalIsAbove = dyToGoal > 1.0
            val reachedUnderLookingBlock = !goalIsAbove && hasNextWp &&
                (distSq <= maxOf(waypointRadius * waypointRadius, 0.8) ||
                 (kotlin.math.floor(env.playerPos.x).toInt() == kotlin.math.floor(wp.x).toInt() &&
                  kotlin.math.floor(env.playerPos.z).toInt() == kotlin.math.floor(wp.z).toInt())) &&
                dy > 0.0

            if (reachedFlat || reachedDrop || reachedUnderLookingBlock) {
                currentWaypointIndex++
            } else {
                break
            }
        }

        // 6. Check if reached end of waypoints
        val playerBlockX = kotlin.math.floor(env.playerPos.x).toInt()
        val playerBlockZ = kotlin.math.floor(env.playerPos.z).toInt()
        val goalBlockX = kotlin.math.floor(goalPos.x).toInt()
        val goalBlockZ = kotlin.math.floor(goalPos.z).toInt()
        val onGoalBlock = playerBlockX == goalBlockX && playerBlockZ == goalBlockZ && Math.abs(dyToGoal) <= 1.5

        val distToGoal = sqrt(distToGoalSq)
        val arrivalLimit = maxOf(waypointRadius, (target as? PositionTarget)?.arrivalRadius ?: 0.65, 0.75)
        val isNearGoal = (distToGoal <= arrivalLimit || onGoalBlock) && Math.abs(dyToGoal) <= 1.5

        if (currentWaypointIndex >= currentWaypoints.size || (currentWaypoints.isNotEmpty() && currentWaypointIndex == currentWaypoints.lastIndex && onGoalBlock)) {
            if (target.isCompleted(env) || (target is PositionTarget && isNearGoal)) {
                state = MovementState.COMPLETED
                rotationEngine.setTarget(target.getFocusPoint(env))
                rotationEngine.setPathTangent(null)
                onDestinationReached?.invoke()
                lastComputedInput = MovementInput()
                return lastComputedInput
            }
            if (target !is PositionTarget && target.isInReach(env) && !isHighGroundLedge) {
                state = MovementState.IN_REACH
                rotationEngine.setTarget(target.getFocusPoint(env))
                rotationEngine.setPathTangent(null)
                lastComputedInput = MovementInput()
                return lastComputedInput
            }
            // Transition to next segment of multi-part route
            currentWaypoints = emptyList()
            currentWaypointIndex = 0
            ticksSincePath = repathIntervalTicks
            computePath(env, goalPos)
        }

        // 7. Unstuck evaluation
        val recoveryInput = unstuckHandler.tick(
            playerPos = env.playerPos,
            isMoving = true,
            pathfinder = pathfinder,
            onRepathRequested = {
                ticksSincePath = repathIntervalTicks
                currentWaypoints = emptyList()
            },
            onAbortRequested = {
                onNavigationFailed?.invoke("Aborted: repeatedly stuck")
                stop()
            }
        )

        if (recoveryInput != null) {
            state = MovementState.RECOVERING
            lastComputedInput = recoveryInput
            return recoveryInput
        }

        state = MovementState.PATHING

        // 8. Decoupled WASD translation
        val targetWp = currentWaypoints.getOrNull(currentWaypointIndex) ?: goalPos
        val dx = targetWp.x - env.playerPos.x
        val dz = targetWp.z - env.playerPos.z
        val dy = targetWp.y - env.playerPos.y
        val dist = sqrt(dx * dx + dz * dz)

        val forwardDot = if (dist > 0.01) (dx * fwdX + dz * fwdZ) / dist else 0.0
        val rightDot = if (dist > 0.01) (dx * rightX + dz * rightZ) / dist else 0.0

        // Arrival deadzone: prevent rapid oscillation on the end block
        val inArrivalZone = dist < 0.35 || (target is PositionTarget && (distToGoal < 0.40 || onGoalBlock) && currentWaypointIndex >= currentWaypoints.lastIndex)
        val inDeadzone = inArrivalZone || dist < 0.20

        val moveFwdRaw = !inDeadzone && forwardDot > 0.38
        val moveBack = !inDeadzone && dist > 0.8 && forwardDot < -0.38
        val moveRightRaw = !inDeadzone && rightDot > 0.38
        val moveLeftRaw = !inDeadzone && rightDot < -0.38

        // Wall obstacle clearance: if turning or strafing laterally into a side obstacle, suppress strafe
        // and walk straight forward until the player's bounding box clears the side block!
        val rightBlocked = moveRightRaw && (rightSideBlocked || rightClearanceTicks > 0)
        val leftBlocked = moveLeftRaw && (leftSideBlocked || leftClearanceTicks > 0)

        val moveRight = moveRightRaw && !rightBlocked
        val moveLeft = moveLeftRaw && !leftBlocked
        val moveFwd = moveFwdRaw || ((rightBlocked || leftBlocked || rightClearanceTicks > 0 || leftClearanceTicks > 0) && !inDeadzone)

        // Fallsafe: unsafe drop abort check
        val isUnsafeDrop = dy < -3.5
        if (isUnsafeDrop && dist < 1.5) {
            // Unsafe cliff drop: trigger repath to find safe route around
            ticksSincePath = repathIntervalTicks
            currentWaypoints = emptyList()
        }

        // Parkour and jump logic
        val isGap = isParkourGap(env, targetWp)
        val isSafeDrop = dy in -3.5..-0.5 && isDropSafe(env, targetWp)

        val isNearEdge = if (isGap && dist > 0.01) {
            val dirX = dx / dist
            val dirZ = dz / dist
            val probePos = BlockPos.ofFloored(env.playerPos.x + dirX * 0.65, env.playerPos.y, env.playerPos.z + dirZ * 0.65)
            env.isAir(probePos) && env.isAir(probePos.down())
        } else false

        // Stairs & Slabs: Minecraft automatically steps up <= 0.60m elevations (slabs, stairs).
        // Suppress jump when stepping onto stairs, slabs, or step-ups to avoid slow awkward bouncing.
        val targetBlockPos = BlockPos.ofFloored(targetWp.x, targetWp.y, targetWp.z)
        val playerBlockPos = BlockPos.ofFloored(env.playerPos.x, env.playerPos.y, env.playerPos.z)
        val isStairOrSlab = env.isStepUpBlock(targetBlockPos) ||
                            env.isStepUpBlock(targetBlockPos.down()) ||
                            env.isStepUpBlock(playerBlockPos)

        val canStepUp = dy in 0.61..1.25 && !isSafeDrop && !isStairOrSlab
        val parkourJump = !isStairOrSlab && isGap && forwardDot > 0.65 && (isNearEdge || dist in 0.8..3.5)

        // Sprint-jump only on long straight flat stretches, never on stairs, and never if high speed
        val RUNNING_FASTER_THAN_JUMPING_SPEED = 0.20
        val isHighSpeed = env.movementSpeed >= RUNNING_FASTER_THAN_JUMPING_SPEED
        val isStraightStretch = hasStraightStretchAhead(env, currentWaypoints, currentWaypointIndex)
        val sprintJump = !isHighSpeed && isStraightStretch && moveFwd && forwardDot > 0.88 && abs(dy) <= 0.25 && !isUnderDestination

        // Only suppress jumping under destination if moving along flat ground/escape path; allow climbing jumps
        val allowJumpUnderDestination = canStepUp || (parkourJump && dy > 0.5) || dy > 0.4
        val isTrappedUnderCeiling = isUnderDestination && !allowJumpUnderDestination
        val jump = !isTrappedUnderCeiling && (canStepUp || parkourJump || sprintJump)
        val sprint = (isGap && forwardDot > 0.3) || (moveFwd && forwardDot > 0.82 && (dist > 3.0 || isStraightStretch)) || (isSafeDrop && dist < 1.0)

        // 9. Camera orientation update: line-of-sight aware lookahead
        val maxLookAhead = 3
        var bestLookIndex: Int? = null
        if (currentWaypoints.isNotEmpty()) {
            val upperLimit = minOf(currentWaypointIndex + maxLookAhead, currentWaypoints.size - 1)
            for (idx in currentWaypointIndex..upperLimit) {
                val candidateWp = currentWaypoints[idx]
                val eyeTarget = Vec3d(candidateWp.x, candidateWp.y + 1.3, candidateWp.z)
                if (hasLineOfSightRay(env, env.playerEyePos, eyeTarget)) {
                    // Do not look around corner if side obstacle is currently blocking the turn
                    if (idx > currentWaypointIndex) {
                        val candDx = candidateWp.x - env.playerPos.x
                        val candDz = candidateWp.z - env.playerPos.z
                        val candDist = sqrt(candDx * candDx + candDz * candDz)
                        if (candDist > 0.01) {
                            val candRightDot = (candDx * rightX + candDz * rightZ) / candDist
                            if (candRightDot > 0.25 && (rightSideBlocked || rightClearanceTicks > 0)) break
                            if (candRightDot < -0.25 && (leftSideBlocked || leftClearanceTicks > 0)) break
                        }
                    }
                    bestLookIndex = idx
                } else {
                    // Corner wall blocks line of sight: do not peek past the wall
                    break
                }
            }
        }

        val lookAheadTangent: Vec3d?
        val partFocusPoint: Vec3d?

        if (bestLookIndex != null) {
            val lookWp = currentWaypoints[bestLookIndex]
            val twpX = lookWp.x - env.playerPos.x
            val twpY = (lookWp.y + 1.3) - env.playerEyePos.y
            val twpZ = lookWp.z - env.playerPos.z
            // If player slightly overshot this waypoint within arrival deadzone, do not flip tangent backwards
            if (dist < 0.35 && forwardDot < 0.0) {
                lookAheadTangent = Vec3d(fwdX, 0.0, fwdZ)
            } else {
                lookAheadTangent = Vec3d(twpX, twpY, twpZ)
            }
            partFocusPoint = Vec3d(lookWp.x, lookWp.y + 1.3, lookWp.z)
        } else if (currentWaypoints.isNotEmpty()) {
            // Future waypoints obscured by wall or turning suppressed: walk straight along corridor tangent
            val wp = currentWaypoints[currentWaypointIndex]
            val wpDx = wp.x - env.playerPos.x
            val wpDz = wp.z - env.playerPos.z
            val wpDist = sqrt(wpDx * wpDx + wpDz * wpDz)
            if (wpDist > 0.1) {
                lookAheadTangent = Vec3d(wpDx, 0.0, wpDz)
            } else {
                lookAheadTangent = Vec3d(fwdX, 0.0, fwdZ)
            }
            partFocusPoint = null
        } else {
            val focus = target.getFocusPoint(env)
            val canSeeGoal = focus != null && hasLineOfSightRay(env, env.playerEyePos, focus)
            lookAheadTangent = Vec3d(
                goalPos.x - env.playerPos.x,
                goalPos.y - env.playerPos.y,
                goalPos.z - env.playerPos.z
            )
            partFocusPoint = if (canSeeGoal) focus else null
        }

        rotationEngine.setPathTangent(lookAheadTangent)

        val isStaringAtCeiling = isUnderDestination && (
            partFocusPoint == null ||
            (partFocusPoint.y - env.playerEyePos.y > 1.2 && distToGoalSq <= 2.25 && currentWaypointIndex >= currentWaypoints.lastIndex)
        )

        val canSeeFocus = partFocusPoint != null &&
            !isStaringAtCeiling &&
            (!isUnderDestination || dyToGoal <= 1.0 || (bestLookIndex != null && currentWaypointIndex < currentWaypoints.lastIndex)) &&
            hasLineOfSightRay(env, env.playerEyePos, partFocusPoint)

        if (canSeeFocus) {
            rotationEngine.setTarget(partFocusPoint)
        } else {
            // View obstructed by wall/cave: follow path tangent straight out!
            rotationEngine.setTarget(null)
        }

        val input = MovementInput(
            forward = moveFwd,
            back = moveBack,
            left = moveLeft,
            right = moveRight,
            jump = jump,
            sneak = false,
            sprint = sprint
        )
        lastComputedInput = input
        return input
    }

    private fun isDropSafe(env: TargetEnvironment, targetWp: Vec3d): Boolean {
        if (targetWp.y < 0.0) return false
        val pos = BlockPos.ofFloored(targetWp.x, targetWp.y, targetWp.z)
        val below = pos.down()
        return !env.isAir(below)
    }

    private fun isParkourGap(env: TargetEnvironment, targetWp: Vec3d): Boolean {
        val targetPos = BlockPos.ofFloored(targetWp.x, targetWp.y, targetWp.z)
        if (env.isStepUpBlock(targetPos) || env.isStepUpBlock(targetPos.down())) return false
        val dx = targetWp.x - env.playerPos.x
        val dz = targetWp.z - env.playerPos.z
        val horizDist = sqrt(dx * dx + dz * dz)
        if (horizDist !in 1.25..4.2) return false
        val dirX = dx / horizDist
        val dirZ = dz / horizDist
        val maxSteps = kotlin.math.floor(horizDist - 0.4).toInt()
        for (step in 1..maxSteps) {
            val checkX = env.playerPos.x + dirX * step
            val checkZ = env.playerPos.z + dirZ * step
            val checkPos = BlockPos.ofFloored(checkX, env.playerPos.y, checkZ)
            if (env.isAir(checkPos) && env.isAir(checkPos.down())) {
                return true
            }
        }
        return false
    }

    fun hasLineOfSightRay(env: TargetEnvironment, from: Vec3d, to: Vec3d): Boolean {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 0.1) return true

        val steps = maxOf(1, kotlin.math.ceil(dist / 0.20).toInt())
        val stepX = dx / steps
        val stepY = dy / steps
        val stepZ = dz / steps

        val endBlock = BlockPos.ofFloored(to.x, to.y, to.z)
        val startBlock = BlockPos.ofFloored(from.x, from.y, from.z)
        var prevBlock: BlockPos? = null

        val pathEnv = pathEnvironmentProvider?.invoke(env) ?: (env as? PathEnvironment) ?:
            (if (env is WorldTargetEnvironment) WorldPathEnvironment(env.world) else null)

        for (i in 1 until steps) {
            val cx = from.x + stepX * i
            val cy = from.y + stepY * i
            val cz = from.z + stepZ * i
            val block = BlockPos.ofFloored(cx, cy, cz)
            if (block == prevBlock || block == startBlock || block == endBlock) continue
            prevBlock = block

            if (pathEnv != null) {
                if (pathEnv.isSolid(block)) return false
            } else {
                if (!env.isAir(block)) return false
            }
        }
        return true
    }

    fun isSideBlocked(
        env: TargetEnvironment,
        dirX: Double,
        dirZ: Double,
        fwdX: Double = 0.0,
        fwdZ: Double = 0.0
    ): Boolean {
        val probeDist = 0.50
        val pathEnv = pathEnvironmentProvider?.invoke(env) ?: (env as? PathEnvironment) ?:
            (if (env is WorldTargetEnvironment) WorldPathEnvironment(env.world) else null)

        val longitudinalOffsets = doubleArrayOf(0.0, -0.40, -0.20, 0.25)
        for (longOffset in longitudinalOffsets) {
            val px = env.playerPos.x + dirX * probeDist + fwdX * longOffset
            val pz = env.playerPos.z + dirZ * probeDist + fwdZ * longOffset
            val footPos = BlockPos.ofFloored(px, env.playerPos.y + 0.2, pz)
            val headPos = BlockPos.ofFloored(px, env.playerPos.y + 1.2, pz)

            val blocked = if (pathEnv != null) {
                pathEnv.isSolid(footPos) || pathEnv.isSolid(headPos)
            } else {
                (!env.isAir(footPos) && !env.isTargetBlock(footPos)) ||
                (!env.isAir(headPos) && !env.isTargetBlock(headPos))
            }
            if (blocked) return true
        }
        return false
    }

    fun hasStraightStretchAhead(
        env: TargetEnvironment,
        waypoints: List<Vec3d>,
        currentIndex: Int
    ): Boolean {
        if (waypoints.isEmpty() || currentIndex >= waypoints.size) return false
        val start = env.playerPos
        val wp0 = waypoints[currentIndex]
        val v0x = wp0.x - start.x
        val v0z = wp0.z - start.z
        val d0 = sqrt(v0x * v0x + v0z * v0z)
        if (abs(wp0.y - start.y) > 0.3) return false

        val wp0Block = BlockPos.ofFloored(wp0.x, wp0.y, wp0.z)
        if (env.isStepUpBlock(wp0Block) || env.isStepUpBlock(wp0Block.down())) return false

        val MIN_STRAIGHT_DISTANCE = 5.0
        if (d0 >= MIN_STRAIGHT_DISTANCE) {
            return true
        }

        var accumulatedDist = d0
        var prevWp = wp0
        var prevDirX = if (d0 > 0.01) v0x / d0 else 0.0
        var prevDirZ = if (d0 > 0.01) v0z / d0 else 0.0

        val checkLimit = minOf(currentIndex + 6, waypoints.size - 1)
        for (i in (currentIndex + 1)..checkLimit) {
            val nextWp = waypoints[i]
            if (abs(nextWp.y - start.y) > 0.3) return false
            val nBlock = BlockPos.ofFloored(nextWp.x, nextWp.y, nextWp.z)
            if (env.isStepUpBlock(nBlock) || env.isStepUpBlock(nBlock.down())) return false

            val segX = nextWp.x - prevWp.x
            val segZ = nextWp.z - prevWp.z
            val segDist = sqrt(segX * segX + segZ * segZ)
            if (segDist < 0.1) continue

            val dirX = segX / segDist
            val dirZ = segZ / segDist
            val dot = prevDirX * dirX + prevDirZ * dirZ
            if (dot < 0.92) {
                break
            }
            accumulatedDist += segDist
            if (accumulatedDist >= MIN_STRAIGHT_DISTANCE) {
                if (i + 1 < waypoints.size) {
                    val afterWp = waypoints[i + 1]
                    val afterBlock = BlockPos.ofFloored(afterWp.x, afterWp.y, afterWp.z)
                    if (env.isStepUpBlock(afterBlock) || env.isStepUpBlock(afterBlock.down())) return false
                }
                return true
            }
            prevWp = nextWp
            prevDirX = dirX
            prevDirZ = dirZ
        }

        return accumulatedDist >= MIN_STRAIGHT_DISTANCE
    }

    fun resolveBlockGoalPos(env: TargetEnvironment, target: BlockTarget): Vec3d {
        val bp = target.blockPos
        val defaultGoal = target.getTargetPos(env) ?: Vec3d(bp.x + 0.5, bp.y + 1.0, bp.z + 0.5)

        val pathEnv = pathEnvironmentProvider?.invoke(env)
            ?: (if (env is WorldTargetEnvironment) WorldPathEnvironment(env.world) else null)
            ?: (env as? PathEnvironment)

        val topPos = BlockPos(bp.x, bp.y + 1, bp.z)
        val canStandOnTop = pathEnv != null &&
            !pathEnv.isSolid(topPos) && !pathEnv.isSolid(topPos.up()) &&
            (pathEnv.getStandHeight(topPos) != null || pathEnv.isSolid(bp))

        if (canStandOnTop) {
            return defaultGoal
        }

        if (pathEnv != null) {
            val reach = env.reachDistance
            val reachSq = reach * reach
            val bCenter = Vec3d(bp.x + 0.5, bp.y + 0.5, bp.z + 0.5)

            var bestSpot: Vec3d? = null
            var bestDistToPlayerSq = Double.MAX_VALUE

            for (dx in -4..4) {
                for (dz in -4..4) {
                    for (dy in -3..3) {
                        val candPos = BlockPos(bp.x + dx, bp.y + dy, bp.z + dz)
                        val standY = pathEnv.getStandHeight(candPos) ?: continue
                        val candVec = Vec3d(candPos.x + 0.5, standY, candPos.z + 0.5)
                        val eyeVec = Vec3d(candVec.x, candVec.y + 1.62, candVec.z)

                        if (eyeVec.squaredDistanceTo(bCenter) <= reachSq) {
                            val distToPlayerSq = env.playerPos.squaredDistanceTo(candVec)
                            if (distToPlayerSq < bestDistToPlayerSq) {
                                bestDistToPlayerSq = distToPlayerSq
                                bestSpot = candVec
                            }
                        }
                    }
                }
            }
            if (bestSpot != null) {
                return bestSpot
            }
        }

        return defaultGoal
    }

    fun findBridgeRoute(
        pathEnv: PathEnvironment,
        start: Vec3d,
        goal: Vec3d,
        maxNodes: Int = 6000
    ): PathResult? {
        val startPos = BlockPos.ofFloored(start.x, start.y, start.z)
        val startGroundY = pathEnv.getStandHeight(startPos) ?: start.y
        val startVec = Vec3d(startPos.x + 0.5, startGroundY, startPos.z + 0.5)

        val queue = ArrayDeque<BlockPos>()
        val parentMap = HashMap<Long, BlockPos>()
        val standYMap = HashMap<Long, Double>()
        val visited = HashSet<Long>()

        val startKey = startPos.asLong()
        queue.add(startPos)
        visited.add(startKey)
        standYMap[startKey] = startGroundY

        var bestPos: BlockPos = startPos
        var bestDistSq = startVec.squaredDistanceTo(goal)
        var goalFoundPos: BlockPos? = null

        val CARDINALS = arrayOf(
            BlockPos(0, 0, -1),
            BlockPos(0, 0, 1),
            BlockPos(1, 0, 0),
            BlockPos(-1, 0, 0)
        )

        var expansions = 0
        while (queue.isNotEmpty() && expansions < maxNodes) {
            val currentPos = queue.removeFirst()
            expansions++

            val curKey = currentPos.asLong()
            val curY = standYMap[curKey] ?: currentPos.y.toDouble()
            val currentVec = Vec3d(currentPos.x + 0.5, curY, currentPos.z + 0.5)
            val dSq = currentVec.squaredDistanceTo(goal)

            if (dSq < bestDistSq) {
                bestDistSq = dSq
                bestPos = currentPos
            }

            if (dSq <= 2.25) {
                goalFoundPos = currentPos
                break
            }

            for (offset in CARDINALS) {
                val nextPos = currentPos.add(offset)
                val nextKey = nextPos.asLong()
                if (nextKey in visited) continue

                var validGroundY: Double? = null
                var finalPos = nextPos

                val hFlat = pathEnv.getStandHeight(nextPos)
                if (hFlat != null && abs(hFlat - curY) <= 0.65) {
                    validGroundY = hFlat
                } else {
                    val hUp = pathEnv.getStandHeight(nextPos.up())
                    if (hUp != null && (hUp - curY) in 0.35..1.25) {
                        validGroundY = hUp
                        finalPos = nextPos.up()
                    } else {
                        val hDown = pathEnv.getStandHeight(nextPos.down())
                        if (hDown != null && (curY - hDown) in 0.35..1.25) {
                            validGroundY = hDown
                            finalPos = nextPos.down()
                        }
                    }
                }

                if (validGroundY != null) {
                    val finalKey = finalPos.asLong()
                    if (finalKey !in visited) {
                        visited.add(finalKey)
                        parentMap[finalKey] = currentPos
                        standYMap[finalKey] = validGroundY
                        queue.add(finalPos)
                    }
                }
            }
        }

        val targetEndPos = goalFoundPos ?: (if (bestDistSq < startVec.squaredDistanceTo(goal) - 16.0) bestPos else null)
        if (targetEndPos == null) return null

        val rawWaypoints = ArrayList<Vec3d>()
        var curr: BlockPos? = targetEndPos
        while (curr != null) {
            val y = standYMap[curr.asLong()] ?: curr.y.toDouble()
            rawWaypoints.add(Vec3d(curr.x + 0.5, y, curr.z + 0.5))
            curr = parentMap[curr.asLong()]
        }
        rawWaypoints.reverse()

        if (rawWaypoints.isEmpty()) return null

        val smoothed = SweptBoxLOS.smoothPath(pathEnv, rawWaypoints, isAnchorNode = null)
        return PathResult(success = true, waypoints = smoothed)
    }

    private fun findStandHeightNear(pathEnv: PathEnvironment?, x: Double, refY: Double, z: Double): Double? {
        if (pathEnv == null) return null
        val basePos = BlockPos.ofFloored(x, refY, z)
        pathEnv.getStandHeight(basePos)?.let { return it }
        for (offset in 1..12) {
            val downPos = basePos.down(offset)
            pathEnv.getStandHeight(downPos)?.let { return it }
            val upPos = basePos.up(offset)
            pathEnv.getStandHeight(upPos)?.let { return it }
        }
        return null
    }

    private fun computePath(env: TargetEnvironment, goalPos: Vec3d) {
        ticksSincePath = 0
        lastGoalPos = goalPos

        val pathEnv = pathEnvironmentProvider?.invoke(env)
            ?: (if (env is WorldTargetEnvironment) WorldPathEnvironment(env.world) else null)
            ?: (env as? PathEnvironment)

        val totalDist = env.playerPos.distanceTo(goalPos)

        // 1. Direct pathfinding: search directly to goalPos if within range (<= 128m),
        // allowing A* to find complete bridge routes across void/chasms!
        val directResult = when {
            pathEnv != null -> pathfinder.findPath(pathEnv, env.playerPos, goalPos)
            env is WorldTargetEnvironment -> pathfinder.findPath(env.world, env.playerPos, goalPos)
            else -> null
        }

        if (directResult != null && directResult.success && directResult.waypoints.isNotEmpty()) {
            currentWaypoints = directResult.waypoints
            currentWaypointIndex = 0
            return
        }

        // 2. Island terrain bridge search: search for contiguous walkable ground / bridge route
        // before giving up or falling back to segmented routing!
        if (pathEnv != null) {
            val bridgeResult = findBridgeRoute(pathEnv, env.playerPos, goalPos)
            if (bridgeResult != null && bridgeResult.success && bridgeResult.waypoints.isNotEmpty()) {
                currentWaypoints = bridgeResult.waypoints
                currentWaypointIndex = 0
                return
            }
        }

        // 3. Segmented pathfinding for very long distance routes (> 36m)
        if (totalDist > 36.0) {
            val dirX = (goalPos.x - env.playerPos.x) / totalDist
            val dirZ = (goalPos.z - env.playerPos.z) / totalDist
            val angles = doubleArrayOf(0.0, 15.0, -15.0, 30.0, -30.0, 45.0, -45.0, 60.0, -60.0)
            for (angleDeg in angles) {
                val rad = Math.toRadians(angleDeg)
                val cosA = kotlin.math.cos(rad)
                val sinA = kotlin.math.sin(rad)
                val rotDirX = dirX * cosA - dirZ * sinA
                val rotDirZ = dirX * sinA + dirZ * cosA

                for (segDist in doubleArrayOf(minOf(30.0, totalDist), 20.0, 15.0, 10.0)) {
                    val subX = env.playerPos.x + rotDirX * segDist
                    val subZ = env.playerPos.z + rotDirZ * segDist
                    val standY = findStandHeightNear(pathEnv, subX, env.playerPos.y, subZ)

                    if (standY != null) {
                        val subGoal = Vec3d(subX, standY, subZ)
                        val segResult = when {
                            pathEnv != null -> pathfinder.findPath(pathEnv, env.playerPos, subGoal)
                            env is WorldTargetEnvironment -> pathfinder.findPath(env.world, env.playerPos, subGoal)
                            else -> null
                        }
                        if (segResult != null && segResult.success && segResult.waypoints.isNotEmpty()) {
                            currentWaypoints = segResult.waypoints
                            currentWaypointIndex = 0
                            return
                        }
                    }
                }
            }
        }

        // 4. Fallback for headless environments without pathEnv/world
        if (pathEnv == null && env !is WorldTargetEnvironment) {
            val fallbackGoal = if (totalDist > 36.0) {
                val dirX = (goalPos.x - env.playerPos.x) / totalDist
                val dirZ = (goalPos.z - env.playerPos.z) / totalDist
                val segDist = minOf(30.0, totalDist)
                Vec3d(env.playerPos.x + dirX * segDist, env.playerPos.y, env.playerPos.z + dirZ * segDist)
            } else {
                goalPos
            }
            currentWaypoints = listOf(fallbackGoal)
            currentWaypointIndex = 0
            return
        }

        // Pathfinding failed: do NOT set dummy waypoints over void or into walls!
        // Keep currentWaypoints empty to prevent walking off cliffs.
    }

    private fun applyKeys(client: MinecraftClient, input: MovementInput) {
        client.options.forwardKey.setPressed(input.forward)
        client.options.backKey.setPressed(input.back)
        client.options.leftKey.setPressed(input.left)
        client.options.rightKey.setPressed(input.right)
        client.options.jumpKey.setPressed(input.jump)
        client.options.sneakKey.setPressed(input.sneak)
        client.options.sprintKey.setPressed(input.sprint)
    }

    private fun releaseKeys(client: MinecraftClient) {
        client.options.forwardKey.setPressed(false)
        client.options.backKey.setPressed(false)
        client.options.leftKey.setPressed(false)
        client.options.rightKey.setPressed(false)
        client.options.jumpKey.setPressed(false)
        client.options.sneakKey.setPressed(false)
        client.options.sprintKey.setPressed(false)
    }
}
