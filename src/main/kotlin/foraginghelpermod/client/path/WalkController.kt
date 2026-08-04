package foraginghelpermod.client.path

import foraginghelpermod.client.HelperConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Follows an A* path by pressing vanilla movement keys and rotating the player.
 * Movement packets are whatever Minecraft sends from normal input — no forged packets.
 */
object WalkController {
	private const val WAYPOINT_REACH = 0.75
	private const val REPATH_INTERVAL = 20
	private const val STUCK_TICKS = 25
	private const val STUCK_MOVE_SQ = 0.04
	private const val LOOK_AHEAD = 5  // Look ahead 5 waypoints for natural head movement
	private const val PITCH_SMOOTHING = 0.02f  // Very slow pitch smoothing for human-like look
	private const val YAW_SMOOTHING = 0.03f  // Very slow yaw smoothing for natural head turning
	private const val TARGET_UPDATE_DELAY = 5  // Update look target every 15 ticks to avoid snappy changes

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
	private var lastPitch = 0f  // Track last pitch for smooth transitions
	private var lastYaw = 0f  // Track last yaw for smooth transitions
	private var lastLookPoint: Vec3d? = null  // Cache look target to avoid snappy changes
	private var ticksSinceLastLook = 0  // Track ticks since last look update

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
	
		// Look ahead to a point further down the path for natural head movement
		val lookAheadIndex = minOf(pathIndex + LOOK_AHEAD, path.size - 1)
		val lookAheadBlock = path[lookAheadIndex]
		val newLookPoint = Vec3d(lookAheadBlock.x + 0.5, lookAheadBlock.y + 1.5, lookAheadBlock.z + 0.5)
	
		// Update look target only every TARGET_UPDATE_DELAY ticks to avoid snappy target switching
		ticksSinceLastLook++
		if (ticksSinceLastLook >= TARGET_UPDATE_DELAY) {
			lastLookPoint = newLookPoint
			ticksSinceLastLook = 0
		}
		
		if (lastLookPoint != null) {
			lookAtNaturally(player, lastLookPoint!!)
		}

		val options = client.options
		
		// Keep keys pressed continuously - Minecraft handles the server mod compatibility
		options.forwardKey.setPressed(true)
		options.sprintKey.setPressed(true)
		options.leftKey.setPressed(false)
		options.rightKey.setPressed(false)
		options.backKey.setPressed(false)

		val needJump = waypoint.y > player.blockPos.y + 1 && player.isOnGround
		options.jumpKey.setPressed(needJump)
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
		status = "Idle"
		if (client != null) releaseMovement(client)
	}

	private fun releaseMovement(client: MinecraftClient) {
		if (!holdingKeys && !HelperConfig.sneakWhileActive) {
			// Still clear in case keys were left pressed.
		}
		val options = client.options
		options.forwardKey.setPressed(false)
		options.backKey.setPressed(false)
		options.leftKey.setPressed(false)
		options.rightKey.setPressed(false)
		options.jumpKey.setPressed(false)
		options.sprintKey.setPressed(false)
		if (!HelperConfig.sneakWhileActive) {
			options.sneakKey.setPressed(false)
		}
		holdingKeys = false
	}

	private fun applySneak(client: MinecraftClient) {
		client.options.sneakKey.setPressed(HelperConfig.sneakWhileActive)
	}

	private fun lookAtNaturally(player: ClientPlayerEntity, point: Vec3d) {
		val dx = point.x - player.x
		val dy = point.y - player.eyeY
		val dz = point.z - player.z
		val horiz = sqrt(dx * dx + dz * dz)
		
		// Calculate target yaw and pitch
		var targetYaw = MathHelper.wrapDegrees(Math.toDegrees(atan2(-dx, dz)).toFloat())
		var targetPitch = MathHelper.clamp(Math.toDegrees(-atan2(dy, horiz)).toFloat(), -90f, 90f)
		
		// Clamp pitch to eye level range for human-like looking (not straight down)
		targetPitch = MathHelper.clamp(targetPitch, -35f, 30f)
		
		// Handle yaw wrapping for shortest path
		var yawDiff = targetYaw - lastYaw
		if (yawDiff > 180f) yawDiff -= 360f
		if (yawDiff < -180f) yawDiff += 360f
		
		// Very slowly transition both yaw and pitch for extremely fluid head movement
		val smoothedYaw = lastYaw + yawDiff * YAW_SMOOTHING
		val smoothedPitch = lastPitch + (targetPitch - lastPitch) * PITCH_SMOOTHING
		
		lastYaw = smoothedYaw
		lastPitch = smoothedPitch
		
		player.yaw = smoothedYaw
		player.pitch = smoothedPitch
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
