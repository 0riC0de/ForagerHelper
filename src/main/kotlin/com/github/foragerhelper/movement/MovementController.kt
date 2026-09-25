package com.github.foragerhelper.movement

import com.github.foragerhelper.path.AStarPathfinder
import com.github.foragerhelper.path.PathEnvironment
import com.github.foragerhelper.path.PathResult
import com.github.foragerhelper.path.Pathfinder
import com.github.foragerhelper.rotation.RotationEngine
import com.github.foragerhelper.target.BlockTarget
import com.github.foragerhelper.target.NavigationTarget
import com.github.foragerhelper.target.PositionTarget
import com.github.foragerhelper.target.TargetEnvironment
import com.github.foragerhelper.target.WorldTargetEnvironment
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
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
    val pathfinder: Pathfinder = AStarPathfinder(),
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
        unstuckHandler.onTargetChanged()
        state = MovementState.PATHING
    }

    fun setWaypointsForTest(waypoints: List<Vec3d>) {
        this.currentWaypoints = waypoints
        this.currentWaypointIndex = 0
        this.ticksSincePath = 0
    }

    override fun stop() {
        activeTarget = null
        currentWaypoints = emptyList()
        currentWaypointIndex = 0
        ticksSincePath = 0
        lastGoalPos = null
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

        val goalPos = targetPos
        if (goalPos == null) {
            stop()
            return MovementInput()
        }

        ticksSincePath++

        // 4. Path computation / periodic re-routing
        val goalMoved = lastGoalPos != null && goalPos.squaredDistanceTo(lastGoalPos!!) > 4.0
        val needsPath = currentWaypoints.isEmpty() ||
                        currentWaypointIndex >= currentWaypoints.size ||
                        goalMoved ||
                        ticksSincePath >= repathIntervalTicks

        if (needsPath) {
            computePath(env, goalPos)
        }

        // 5. Waypoint progression with fallsafe drop support
        while (currentWaypointIndex < currentWaypoints.size) {
            val wp = currentWaypoints[currentWaypointIndex]
            val dx = wp.x - env.playerPos.x
            val dz = wp.z - env.playerPos.z
            val dy = wp.y - env.playerPos.y
            val distSq = dx * dx + dz * dz

            val reachedFlat = distSq <= waypointRadius * waypointRadius && Math.abs(dy) <= 1.25
            // Safe drop progression: advance intermediate drop waypoint if aligned horizontally and safe
            val reachedDrop = currentWaypointIndex < currentWaypoints.size - 1 &&
                distSq <= (waypointRadius * 1.5) * (waypointRadius * 1.5) &&
                dy in -3.5..-0.5 && isDropSafe(env, wp)

            if (reachedFlat || reachedDrop) {
                currentWaypointIndex++
            } else {
                break
            }
        }

        // 6. Check if reached end of waypoints
        if (currentWaypointIndex >= currentWaypoints.size) {
            if (target.isCompleted(env)) {
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

        val yawRad = Math.toRadians(playerYaw.toDouble())
        val fwdX = -sin(yawRad)
        val fwdZ = cos(yawRad)
        // In Minecraft coords: +X is East, +Z is South.
        // Facing South (yaw=0): forward=(0,1), Right (West) is (-1,0), Left (East) is (1,0).
        val rightX = -cos(yawRad)
        val rightZ = -sin(yawRad)

        val forwardDot = if (dist > 0.01) (dx * fwdX + dz * fwdZ) / dist else 0.0
        val rightDot = if (dist > 0.01) (dx * rightX + dz * rightZ) / dist else 0.0

        val moveFwd = forwardDot > 0.38
        val moveBack = forwardDot < -0.38
        val moveRight = rightDot > 0.38
        val moveLeft = rightDot < -0.38

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
        val jump = (dy > 0.5 && !isSafeDrop) || (isGap && forwardDot > 0.65 && dist in 0.8..3.2)
        val sprint = (isGap && forwardDot > 0.5) || (moveFwd && forwardDot > 0.82 && dist > 3.0) || (isSafeDrop && dist < 1.0)

        // 9. Camera orientation update: divide path into parts and focus on upcoming part
        val lookAheadSteps = 3
        val lookWpIndex = minOf(currentWaypointIndex + lookAheadSteps, currentWaypoints.size - 1)
        val lookWp = if (currentWaypoints.isNotEmpty() && lookWpIndex >= 0) currentWaypoints[lookWpIndex] else targetWp

        val lookAheadTangent = Vec3d(
            lookWp.x - env.playerPos.x,
            (lookWp.y + 1.3) - env.playerEyePos.y,
            lookWp.z - env.playerPos.z
        )
        rotationEngine.setPathTangent(lookAheadTangent)

        // While navigating along path parts, focus camera on upcoming part target
        // Only focus on interaction target when nearing completion
        val partFocusPoint = if (currentWaypoints.isNotEmpty()) {
            Vec3d(lookWp.x, lookWp.y + 1.3, lookWp.z)
        } else {
            target.getFocusPoint(env)
        }
        rotationEngine.setTarget(partFocusPoint)

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
        val dx = targetWp.x - env.playerPos.x
        val dz = targetWp.z - env.playerPos.z
        val horizDist = sqrt(dx * dx + dz * dz)
        if (horizDist !in 1.25..3.5) return false
        val midX = (env.playerPos.x + targetWp.x) * 0.5
        val midZ = (env.playerPos.z + targetWp.z) * 0.5
        val midPos = BlockPos.ofFloored(midX, env.playerPos.y, midZ)
        return env.isAir(midPos) && env.isAir(midPos.down())
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
            ?: (env as? PathEnvironment)

        val totalDist = env.playerPos.distanceTo(goalPos)
        val planningGoal = if (totalDist > 36.0) {
            // Segment the route into local human-like parts along the heading
            val dirX = (goalPos.x - env.playerPos.x) / totalDist
            val dirZ = (goalPos.z - env.playerPos.z) / totalDist
            val segDist = minOf(30.0, totalDist)
            val subX = env.playerPos.x + dirX * segDist
            val subZ = env.playerPos.z + dirZ * segDist
            val standY = findStandHeightNear(pathEnv, subX, env.playerPos.y, subZ) ?: env.playerPos.y
            Vec3d(subX, standY, subZ)
        } else {
            goalPos
        }

        val result = when {
            pathEnv != null -> pathfinder.findPath(pathEnv, env.playerPos, planningGoal)
            env is WorldTargetEnvironment -> pathfinder.findPath(env.world, env.playerPos, planningGoal)
            else -> PathResult(success = true, waypoints = listOf(env.playerPos, planningGoal))
        }

        if (result.success && result.waypoints.isNotEmpty()) {
            currentWaypoints = result.waypoints
            currentWaypointIndex = 0
        } else {
            if (currentWaypoints.isEmpty() || currentWaypointIndex >= currentWaypoints.size) {
                currentWaypoints = listOf(planningGoal)
                currentWaypointIndex = 0
            }
        }
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
