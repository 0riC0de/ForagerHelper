package foraginghelpermod.client.path

import foraginghelpermod.client.HelperConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.client.world.ClientWorld
import net.minecraft.registry.Registries
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.util.Hand
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

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
	private const val TARGET_UPDATE_DELAY = 5
	private const val VOID_USE_COOLDOWN = 40
	private const val FAILED_JUMP_TICKS = 12

	var path: List<BlockPos> = emptyList()
		private set
	var pathIndex: Int = 0
		private set
	var status: String = "Idle"
		private set
	var etherWarpTarget: BlockPos? = null
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
	private var lookPlanTargetYaw = Float.NaN
	private var lookPlanTargetPitch = Float.NaN
	private var lookPlanStartYaw = 0f
	private var lookPlanStartPitch = 0f
	private var lookPlanDeltaYaw = 0f
	private var lookPlanDeltaPitch = 0f
	private var lookPlanStep = 0
	private var lookPlanSteps = 1
	private var lookPlanCurve = 0f
	private var voidUseCooldown = 0
	private var fallingTicks = 0
	private var etherWarpScanCooldown = 0

	fun tick(
		client: MinecraftClient,
		targetLog: BlockPos?,
		reach: Double,
		forceRoute: Boolean = false,
		exactDestination: Boolean = false,
	) {
		val player = client.player ?: return stop(client)
		val world = client.world ?: return stop(client)
		if (voidUseCooldown > 0) voidUseCooldown--
		if (etherWarpScanCooldown > 0) etherWarpScanCooldown--
		if (!player.isOnGround && player.velocity.y < -0.2) fallingTicks++ else if (player.isOnGround) fallingTicks = 0

		if ((!HelperConfig.autoWalk && !forceRoute) || targetLog == null) {
			stop(client)
			return
		}

		val targetCenter = Vec3d(targetLog.x + 0.5, targetLog.y + 0.5, targetLog.z + 0.5)
		if (fallingTicks >= 5 && !hasNearbyGround(world, player.blockPos, 8)) {
			if (tryAspectOfVoid(client, targetCenter, "Void recovery")) return
		}

		val eye = player.eyePos
		val exactDestinationReached = !exactDestination || player.blockPos == targetLog
		if (exactDestinationReached && eye.squaredDistanceTo(targetCenter) <= reach * reach) {
			status = "In reach"
			releaseMovement(client)
			if (HelperConfig.lookAtTarget) lookAtBlock(player, targetLog)
			applySneak(client)
			return
		}

		val targetHorizontalDistance = horizontalDistance(player, targetCenter)
		if (targetHorizontalDistance > 57.0 || aspectOfVoidHand(player) == null) etherWarpTarget = null
		if (HelperConfig.useAspectOfVoid && aspectOfVoidHand(player) != null && targetHorizontalDistance <= 57.0) {
			if (etherWarpScanCooldown <= 0) {
				etherWarpTarget = EtherWarpPlanner.findBestTarget(world, Vec3d(player.x, player.y, player.z), targetLog)
				etherWarpScanCooldown = 5
			}
			val warpTarget = etherWarpTarget
			if (warpTarget != null && tryAspectOfVoid(client, waypointCenter(warpTarget, warpTarget.y + 1.0), "Ether Warp")) return
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
			val result = AStarPathfinder.findPath(
				world, start, targetLog, reach,
				maxHorizontalRange = 40,
				exactDestination = exactDestination,
			)
			if (result == null || result.waypoints.isEmpty()) {
				if (tryAspectOfVoid(client, targetCenter, "No safe path")) return
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

		if (pathIndex >= path.size && exactDestination && player.blockPos != targetLog) {
			// The pathfinder intentionally returns a local segment for long routes.
			// Clear it and let the next tick calculate the next segment from the
			// player's new position.
			path = emptyList()
			pathIndex = 0
			ticksSincePath = REPATH_INTERVAL
			releaseMovement(client)
			status = "Planning next route"
			return
		}

		if (pathIndex >= path.size) {
			status = "Arrived"
			releaseMovement(client)
			if (HelperConfig.lookAtTarget) lookAtBlock(player, targetLog)
			applySneak(client)
			return
		}

		val waypoint = path[pathIndex]
		val lookAheadIndex = minOf(pathIndex + LOOK_AHEAD, path.size - 1)
		val lookAheadBlock = path[lookAheadIndex]
		val newLookPoint = Vec3d(lookAheadBlock.x + 0.5, lookAheadBlock.y + 1.5, lookAheadBlock.z + 0.5)

		ticksSinceLastLook++
		if (lastLookPoint == null ||
			(ticksSinceLastLook >= TARGET_UPDATE_DELAY && lastLookPoint!!.squaredDistanceTo(newLookPoint) > 0.02)) {
			lastLookPoint = humanizeLookPoint(newLookPoint)
			ticksSinceLastLook = 0
		}

		if (lastLookPoint != null) {
			lookAtNaturally(player, lastLookPoint!!)
		}

		val targetPoint = Vec3d(waypoint.x + 0.5, player.y, waypoint.z + 0.5)
		val targetYaw = yawTo(player, targetPoint)
		val yawDiff = MathHelper.wrapDegrees(targetYaw - player.yaw)
		val movementStart = if (pathIndex > 0) path[pathIndex - 1] else player.blockPos
		val parkour = AStarPathfinder.isParkourJump(world, movementStart, waypoint)
		if (stuckTicks >= FAILED_JUMP_TICKS && tryAspectOfVoid(client, waypointCenter(waypoint, player.y), "Recovery")) return
		val parkourAligned = abs(yawDiff) < 18f
		setMovement(client, yawDiff, parkour, parkourAligned)

		val needJump = ((parkour && parkourAligned) || waypoint.y > player.blockPos.y) && player.isOnGround
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
		lookPlanTargetYaw = Float.NaN
		lookPlanTargetPitch = Float.NaN
		lookPlanStep = 0
		lookPlanSteps = 1
		etherWarpTarget = null
		etherWarpScanCooldown = 0
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

	private fun waypointCenter(pos: BlockPos, y: Double): Vec3d =
		Vec3d(pos.x + 0.5, y, pos.z + 0.5)

	private fun horizontalDistance(player: ClientPlayerEntity, target: Vec3d): Double {
		val dx = player.x - target.x
		val dz = player.z - target.z
		return sqrt(dx * dx + dz * dz)
	}

	private fun hasNearbyGround(world: ClientWorld, feet: BlockPos, maxDepth: Int): Boolean {
		for (depth in 1..maxDepth) {
			val below = feet.down(depth)
			if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty) return true
		}
		return false
	}

	private fun tryAspectOfVoid(client: MinecraftClient, target: Vec3d, reason: String): Boolean {
		if (!HelperConfig.useAspectOfVoid || voidUseCooldown > 0) return false
		val player = client.player ?: return false
		val hand = aspectOfVoidHand(player) ?: return false
		val targetYaw = MathHelper.wrapDegrees(Math.toDegrees(atan2(-(target.x - player.x), target.z - player.z)).toFloat())
		player.yaw = targetYaw
		player.pitch = MathHelper.clamp(Math.toDegrees(-atan2(target.y - player.eyeY, sqrt((target.x - player.x) * (target.x - player.x) + (target.z - player.z) * (target.z - player.z)))).toFloat(), -45f, 45f)
		releaseMovement(client)
		client.interactionManager?.interactItem(player, hand)
		voidUseCooldown = VOID_USE_COOLDOWN
		stuckTicks = 0
		ticksSincePath = REPATH_INTERVAL
		status = reason
		return true
	}

	private fun aspectOfVoidHand(player: ClientPlayerEntity): Hand? {
		for (hand in arrayOf(Hand.MAIN_HAND, Hand.OFF_HAND)) {
			val stack = player.getStackInHand(hand)
			if (stack.isEmpty) continue
			val itemId = Registries.ITEM.getId(stack.item).path.lowercase()
			val customName = stack.name.string.lowercase()
			if (itemId.contains("aspect_of_the_void") ||
				customName.contains("aspect of the void") ||
				(customName.contains("aspect") && customName.contains("void"))) return hand
		}
		return null
	}

	private fun lookAtNaturally(player: ClientPlayerEntity, point: Vec3d, precise: Boolean = false) {
		com.github.foragerhelper.rotation.RotationEngine.setTarget(point)
	}

	private fun humanizeLookPoint(point: Vec3d): Vec3d = point

	private fun yawTo(player: ClientPlayerEntity, point: Vec3d): Float =
		MathHelper.wrapDegrees(Math.toDegrees(atan2(-(point.x - player.x), point.z - player.z)).toFloat())

	private fun setMovement(client: MinecraftClient, yawDiff: Float, parkour: Boolean, parkourAligned: Boolean) {
		val options = client.options
		options.forwardKey.setPressed(!parkour || parkourAligned)
		options.backKey.setPressed(false)
		options.leftKey.setPressed(!parkour && yawDiff < -55f)
		options.rightKey.setPressed(!parkour && yawDiff > 55f)
		options.sprintKey.setPressed((parkour && parkourAligned) || (!parkour && abs(yawDiff) < 35f))
	}

	fun lookAtBlock(player: ClientPlayerEntity, block: BlockPos) {
		val target = com.github.foragerhelper.target.BlockTarget(block)
		val focus = target.getFocusPoint(player.entityWorld, player) ?: miningLookPoint(player, block)
		com.github.foragerhelper.rotation.RotationEngine.setTarget(focus)
	}

	/** Small corrective input used while the chop action is settling or stalled. */
	fun nudgeForMining(client: MinecraftClient, target: BlockPos, direction: Int) {
		val player = client.player ?: return
		val dx = target.x + 0.5 - player.x
		val dz = target.z + 0.5 - player.z
		val targetYaw = MathHelper.wrapDegrees(Math.toDegrees(atan2(-dx, dz)).toFloat())
		val yawDiff = MathHelper.wrapDegrees(targetYaw - player.yaw)
		val options = client.options
		options.forwardKey.setPressed(direction == 0 && abs(yawDiff) < 32f)
		options.backKey.setPressed(false)
		options.leftKey.setPressed(direction < 0)
		options.rightKey.setPressed(direction > 0)
		options.sprintKey.setPressed(false)
	}

	private fun miningLookPoint(player: ClientPlayerEntity, block: BlockPos): Vec3d {
		val centerX = block.x + 0.5
		val centerZ = block.z + 0.5
		// A log above the player is mined through its underside. Keeping the
		// point slightly inside the face also avoids aiming at an adjacent block.
		val y = when {
			player.eyeY < block.y -> block.y + 0.04
			player.eyeY > block.y + 1.0 -> block.y + 0.96
			else -> block.y + 0.5
		}
		return humanizeLookPoint(Vec3d(centerX, y, centerZ))
	}
}
