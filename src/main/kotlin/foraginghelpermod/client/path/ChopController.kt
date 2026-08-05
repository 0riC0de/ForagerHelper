package foraginghelpermod.client.path

import foraginghelpermod.client.InputController
import foraginghelpermod.client.scan.TreeScanner
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.Hand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.abs

/** Holds the attack action and verifies that the server actually removed the log. */
object ChopController {
	private const val VERIFY_TIMEOUT = 12
	private const val MISSING_CONFIRM_TICKS = 2
	private const val AIM_SETTLE_TICKS = 4

	private var current: BlockPos? = null
	private var missingTicks = 0
	private var verifyTicks = 0
	private var aimTicks = 0

	fun tick(client: MinecraftClient, target: BlockPos?, reach: Double) {
		val player = client.player
		val world = client.world
		if (player == null || world == null || target == null) {
			reset()
			return
		}

		if (current != target) {
			current = target.toImmutable()
			missingTicks = 0
			verifyTicks = 0
			aimTicks = 0
		}

		val state = world.getBlockState(target)
		if (state.isAir || !TreeScanner.isLogLike(state)) {
			missingTicks++
			if (missingTicks >= MISSING_CONFIRM_TICKS) {
				InputController.forgetTarget(target)
				reset()
			}
			return
		}
		missingTicks = 0

		val targetCenter = Vec3d(target.x + 0.5, target.y + 0.5, target.z + 0.5)
		if (player.eyePos.squaredDistanceTo(targetCenter) > reach * reach) return
		if (AStarPathfinder.hasNonLeafMiningBlocker(world, player.blockPos, target)) return

		// Aim at the nearest useful face. In particular, use the underside when
		// the player is below the log instead of aiming through its middle.
		WalkController.lookAtBlock(player, target)
		aimTicks++
		if (aimTicks < AIM_SETTLE_TICKS) {
			WalkController.nudgeForMining(client, target, 0)
			return
		}
		val crosshair = player.raycast(reach, 1.0f, false)
		if (crosshair !is BlockHitResult || crosshair.blockPos != target) {
			if (aimTicks % 6 == 0) WalkController.nudgeForMining(client, target, if (aimTicks % 12 == 0) -1 else 1)
			return
		}
		val face = attackFace(player, target)
		client.interactionManager?.attackBlock(target, face)
		client.player?.swingHand(Hand.MAIN_HAND)
		verifyTicks++
		if (verifyTicks >= 8) {
			WalkController.nudgeForMining(client, target, if (verifyTicks % 16 < 8) -1 else 1)
		}
		if (verifyTicks > VERIFY_TIMEOUT) {
			// Re-checking keeps stale client targets from causing an endless attack loop.
			verifyTicks = 0
		}
	}

	private fun attackFace(player: ClientPlayerEntity, target: BlockPos): Direction {
		val eye = player.eyePos
		return when {
			eye.y < target.y -> Direction.DOWN
			eye.y > target.y + 1.0 -> Direction.UP
			abs(eye.x - (target.x + 0.5)) > abs(eye.z - (target.z + 0.5)) ->
				if (eye.x < target.x + 0.5) Direction.WEST else Direction.EAST
			else -> if (eye.z < target.z + 0.5) Direction.NORTH else Direction.SOUTH
		}
	}

	private fun reset() {
		current = null
		missingTicks = 0
		verifyTicks = 0
		aimTicks = 0
	}
}
