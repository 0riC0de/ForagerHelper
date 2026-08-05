package foraginghelpermod.client.path

import foraginghelpermod.client.HelperConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Follows an A* path by pressing vanilla movement keys and rotating the player.
 * Prevents "dead keys" by checking physical GLFW hardware states on release.
 */
object WalkController {
	private const val WAYPOINT_REACH = 0.75
	private const val REPATH_INTERVAL = 20
	private const val STUCK_TICKS = 25
	private const val STUCK_MOVE_SQ = 0.04
	private const val LOOK_AHEAD = 5
	private const val PITCH_SMOOTHING = 0.02f
	private const val MAX_TURN_PER_TICK = 12.0f
	private const val TARGET_UPDATE_DELAY = 5

	var path: List<BlockPos> = emptyList()
		private set
	var pathIndex: Int = 0
		private set
	var status: String = "Idle"
		private set

	private var goal: BlockPos? = null
	private var ticksSincePath = 0
	private var stuckTicks = 0
	private var lastPos: Vec3d? = null
	private var holdingKeys = false
	private var lastPitch = 0f
	private var lastYaw = 0f
	private var lastLookPoint: Vec3d? = null
	private var ticksSinceLastLook = 0
	private var initializedLook = false

	fun tick(client: MinecraftClient, targetLog: BlockPos?, reach: Double) {
		val player = client.player ?: return stop(client)
		val world = client.world ?: return stop(client)

		if (!HelperConfig.autoWalk || targetLog == null) {
			stop(client)
			return
		}

		val eye = player.eyePos
		val targetCenter = Vec3d(targetLog.x + 0.5, targetLog.y + 0.5, targetLog.z + 0.5)
		if (eye.squaredDistanceTo(targetCenter) <= reach * reach) {
			status = "In reach"
			releaseMovement(client)
			if (HelperConfig.lookAtTarget) lookAt(player, targetCenter)
			applySneak(client)
			return
		}

		ticksSincePath++
		val needRepath =
			goal != targetLog ||
					path.isEmpty() ||
					pathIndex >= path.size ||
					ticksSincePath >= REPATH_INTERVAL ||
					stuckTicks >= STUCK_TICKS

		if (needRepath) {
			goal = targetLog.toImmutable()
			ticksSincePath = 0
			stuckTicks = 0
			val start = player.blockPos
			val result = AStarPathfinder.findPath(world, start, targetLog, reach)
			if (result == null || result.waypoints.isEmpty()) {
				status = "No path"
				releaseMovement(client)
				applySneak(client)
				path = emptyList()
				pathIndex = 0
				return
			}
			path = result.waypoints
			pathIndex = 0
			status = "Path ${path.size}"
		}

		while (pathIndex < path.size) {
			val wp = path[pathIndex]
			val wpCenter = Vec3d(wp.x + 0.5, player.y, wp.z + 0.5)
			val horiz =
				(player.x - wpCenter.x).let { it * it } + (player.z - wpCenter.z).let { it * it }
			if (horiz <= WAYPOINT_REACH * WAYPOINT_REACH && abs(player.y - wp.y) < 1.5) {
				pathIndex++
			} else {
				break
			}
		}

		if (pathIndex >= path.size) {
			status = "Arrived"
			releaseMovement(client)
			if (HelperConfig.lookAtTarget) lookAt(player, targetCenter)
			applySneak(client)
			return
		}

		val waypoint = path[pathIndex]
		val lookAheadIndex = minOf(pathIndex + LOOK_AHEAD, path.size - 1)
		val lookAheadBlock = path[lookAheadIndex]
		val newLookPoint = Vec3d(lookAheadBlock.x + 0.5, lookAheadBlock.y + 1.5, lookAheadBlock.z + 0.5)

		ticksSinceLastLook++
		if (ticksSinceLastLook >= TARGET_UPDATE_DELAY) {
			lastLookPoint = newLookPoint
			ticksSinceLastLook = 0
		}

		if (lastLookPoint != null) {
			lookAtNaturally(player, lastLookPoint!!)
		}

		val targetPoint = Vec3d(waypoint.x + 0.5, player.y, waypoint.z + 0.5)
		val targetYaw = yawTo(player, targetPoint)
		val yawDiff = MathHelper.wrapDegrees(targetYaw - player.yaw)
		turnToward(player, targetYaw)
		setMovement(client, yawDiff)

		val needJump = waypoint.y > player.blockPos.y && player.isOnGround
		client.options.jumpKey.setPressed(needJump)

		applySneak(client)
		holdingKeys = true

		val now = Vec3d(player.x, player.y, player.z)
		val prev = lastPos
		lastPos = now
		if (prev != null && now.squaredDistanceTo(prev) < STUCK_MOVE_SQ) {
			stuckTicks++
		} else {
			stuckTicks = 0
		}

		status = "Walking ${pathIndex + 1}/${path.size}"
	}

	fun stop(client: MinecraftClient? = MinecraftClient.getInstance()) {
		path = emptyList()
		pathIndex = 0
		goal = null
		ticksSincePath = 0
		stuckTicks = 0
		lastPos = null
		lastPitch = 0f
		lastYaw = 0f
		lastLookPoint = null
		ticksSinceLastLook = 0
		initializedLook = false
		status = "Idle"

		if (client != null) {
			releaseMovement(client)
		}
	}

	private fun releaseMovement(client: MinecraftClient) {
		val options = client.options

		// Restore physical hardware state instead of just setting to false
		restorePhysicalKeyState(client, options.forwardKey)
		restorePhysicalKeyState(client, options.backKey)
		restorePhysicalKeyState(client, options.leftKey)
		restorePhysicalKeyState(client, options.rightKey)
		restorePhysicalKeyState(client, options.jumpKey)
		restorePhysicalKeyState(client, options.sprintKey)

		if (!HelperConfig.sneakWhileActive) {
			restorePhysicalKeyState(client, options.sneakKey)
		}

		holdingKeys = false
	}

	/**
	 * Polls the physical GLFW hardware state to ensure keys are not left "dead"
	 * if the user is holding them on their physical keyboard when the bot stops.
	 */
	private fun restorePhysicalKeyState(client: MinecraftClient, keyBinding: KeyBinding) {
		try {
			val key = InputUtil.fromTranslationKey(keyBinding.boundKeyTranslationKey)
			val window = client.window.handle

			val isPhysicallyPressed = if (key.category == InputUtil.Type.MOUSE) {
				GLFW.glfwGetMouseButton(window, key.code) == GLFW.GLFW_PRESS
			} else {
				// Directly use GLFW instead of InputUtil.isKeyPressed
				GLFW.glfwGetKey(window, key.code) == GLFW.GLFW_PRESS
			}

			keyBinding.setPressed(isPhysicallyPressed)
		} catch (e: Exception) {
			// Fallback to unpressed if mapping retrieval fails
			keyBinding.setPressed(false)
		}
	}

	private fun applySneak(client: MinecraftClient) {
		client.options.sneakKey.setPressed(HelperConfig.sneakWhileActive)
	}

	private fun lookAtNaturally(player: ClientPlayerEntity, point: Vec3d) {
		val dx = point.x - player.x
		val dy = point.y - player.eyeY
		val dz = point.z - player.z
		val horiz = sqrt(dx * dx + dz * dz)

		val targetYaw = MathHelper.wrapDegrees(Math.toDegrees(atan2(-dx, dz)).toFloat())
		var targetPitch = MathHelper.clamp(Math.toDegrees(-atan2(dy, horiz)).toFloat(), -90f, 90f)

		targetPitch = MathHelper.clamp(targetPitch, -35f, 30f)

		if (!initializedLook) {
			lastYaw = player.yaw
			lastPitch = player.pitch
			initializedLook = true
		}
		val yawDiff = MathHelper.wrapDegrees(targetYaw - lastYaw)
		val yawStep = MathHelper.clamp(yawDiff, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK)
		val smoothedYaw = lastYaw + yawStep
		val smoothedPitch = lastPitch + (targetPitch - lastPitch) * 0.18f

		lastYaw = smoothedYaw
		lastPitch = smoothedPitch

		player.yaw = smoothedYaw
		player.pitch = smoothedPitch
	}

	private fun turnToward(player: ClientPlayerEntity, targetYaw: Float) {
		val diff = MathHelper.wrapDegrees(targetYaw - player.yaw)
		player.yaw += MathHelper.clamp(diff, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK)
	}

	private fun yawTo(player: ClientPlayerEntity, point: Vec3d): Float =
		MathHelper.wrapDegrees(Math.toDegrees(atan2(-(point.x - player.x), point.z - player.z)).toFloat())

	private fun setMovement(client: MinecraftClient, yawDiff: Float) {
		val options = client.options
		// Forward movement is retained for normal walking; strafe input prevents wide arcs during turns.
		options.forwardKey.setPressed(true)
		options.backKey.setPressed(false)
		options.leftKey.setPressed(yawDiff < -55f)
		options.rightKey.setPressed(yawDiff > 55f)
		options.sprintKey.setPressed(abs(yawDiff) < 35f)
	}

	private fun lookAt(player: ClientPlayerEntity, point: Vec3d) {
		val dx = point.x - player.x
		val dy = point.y - player.eyeY
		val dz = point.z - player.z
		val horiz = sqrt(dx * dx + dz * dz)
		val yaw = MathHelper.wrapDegrees(Math.toDegrees(atan2(-dx, dz)).toFloat())
		val pitch = MathHelper.clamp(Math.toDegrees(-atan2(dy, horiz)).toFloat(), -90f, 90f)
		player.yaw = yaw
		player.pitch = pitch
	}
}
