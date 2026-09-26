package foraginghelpermod.client

import foraginghelpermod.client.path.WalkController
import foraginghelpermod.client.path.AStarPathfinder
import foraginghelpermod.client.path.ChopController
import foraginghelpermod.client.scan.TreeCluster
import foraginghelpermod.client.scan.TreeScanner
import foraginghelpermod.client.scan.TreeScorer
import foraginghelpermod.client.ui.HelperOptionsScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.sqrt

/**
 * Client-tick loop: options HUD, tree scan/commit, and A* walk-to-target.
 * Movement uses vanilla keybinds only (no forged packets).
 */
object InputController {
	private const val SCAN_INTERVAL_TICKS = 5
	private const val REACH = 4.5

	val enabled: Boolean
		get() = HelperConfig.enabled

	var nearestTree: TreeCluster? = null
		private set

	var targetLog: BlockPos? = null
		private set

	var treeCount: Int = 0
		private set

	val isLocked: Boolean
		get() = committedPositions.isNotEmpty()

	val walkStatus: String
		get() = com.github.foragerhelper.movement.MovementController.currentStatus

	private var tickCounter: Int = 0
	private var committedPositions: Set<BlockPos> = emptySet()

	fun register() {
		ClientTickEvents.END_CLIENT_TICK.register(::onEndTick)
	}

	fun openOptions() {
		val client = MinecraftClient.getInstance()
		val current = client.currentScreen
		if (current is HelperOptionsScreen) {
			current.requestClose()
			return
		}
		if (current != null) return
		client.setScreen(HelperOptionsScreen())
	}

	private fun onEndTick(client: MinecraftClient) {
		val player = client.player
		val world = client.world
		if (player == null || world == null) {
			HelperConfig.enabled = false
			clearScan(client)
			return
		}

		while (ModKeyBindings.toggleHelper.wasPressed()) {
			openOptions()
		}

		if (client.currentScreen != null) {
			// Never keep synthetic movement active or process movement while any GUI is open (inventory, chat, screens).
			com.github.foragerhelper.movement.MovementController.stop()
			WalkController.stop(client)
			return
		}

		val manualGoal = HelperConfig.manualRouteGoal
		if (manualGoal != null) {
			val active = com.github.foragerhelper.movement.MovementController.activeTarget
			if (active !is com.github.foragerhelper.target.PositionTarget) {
				val posTarget = com.github.foragerhelper.target.PositionTarget(
					Vec3d(manualGoal.x + 0.5, manualGoal.y.toDouble(), manualGoal.z + 0.5)
				)
				com.github.foragerhelper.movement.MovementController.setDestination(posTarget)
			}
			com.github.foragerhelper.movement.MovementController.tick(client)
			if (com.github.foragerhelper.movement.MovementController.state == com.github.foragerhelper.movement.MovementState.COMPLETED) {
				HelperConfig.manualRouteGoal = null
				com.github.foragerhelper.movement.MovementController.stop()
			}
			return
		}

		if (!HelperConfig.enabled) {
			clearScan(client)
			return
		}

		tickCounter++
		if (tickCounter >= SCAN_INTERVAL_TICKS) {
			tickCounter = 0
			val eye = player.eyePos
			val origin = player.blockPos
			val clusters = TreeScanner.scanClusters(world, origin, eye, TreeScanner.DEFAULT_RADIUS)
			treeCount = clusters.size

			val selected = resolveCommittedOrPick(clusters)
			nearestTree = selected
			 targetLog = selected?.let { cluster ->
				TreeScanner.selectTargetLog(cluster, eye, REACH) { log ->
					AStarPathfinder.hasUsableMiningSpot(world, log, REACH) &&
						!AStarPathfinder.hasNonLeafMiningBlocker(world, player.blockPos, log)
				}
			}
		}

		if (targetLog != null && HelperConfig.autoWalk) {
			val blockTarget = com.github.foragerhelper.target.BlockTarget(targetLog!!)
			val current = com.github.foragerhelper.movement.MovementController.activeTarget
			if (current !is com.github.foragerhelper.target.BlockTarget || current.blockPos != targetLog) {
				com.github.foragerhelper.movement.MovementController.setDestination(blockTarget)
			}
			com.github.foragerhelper.movement.MovementController.tick(client)
		} else {
			if (com.github.foragerhelper.movement.MovementController.isNavigating) {
				com.github.foragerhelper.movement.MovementController.stop()
			}
		}

		ChopController.tick(client, targetLog, REACH)
	}

	private fun resolveCommittedOrPick(clusters: List<TreeCluster>): TreeCluster? {
		if (committedPositions.isNotEmpty()) {
			val ongoing = clusters.firstOrNull { cluster ->
				cluster.logs.any { it in committedPositions }
			}
			if (ongoing != null) {
				commit(ongoing)
				return ongoing
			}
			committedPositions = emptySet()
		}

		val best = TreeScorer.pickBest(clusters, HelperConfig.preferSmallTrees)
		if (best != null) commit(best)
		return best
	}

	private fun commit(cluster: TreeCluster) {
		committedPositions = cluster.logs.map { it.toImmutable() }.toHashSet()
	}

	private fun clearScan(client: MinecraftClient) {
		nearestTree = null
		targetLog = null
		treeCount = 0
		tickCounter = 0
		committedPositions = emptySet()
		com.github.foragerhelper.movement.MovementController.stop()
		WalkController.stop(client)
	}

	fun forgetTarget(pos: BlockPos) {
		committedPositions = committedPositions - pos
		if (targetLog == pos) {
			targetLog = null
			com.github.foragerhelper.movement.MovementController.stop()
		}
	}

	fun nearestDistance(): Double? =
		nearestTree?.distanceSq?.let { sqrt(it) }

	fun selectedTreeSize(): Int? =
		nearestTree?.size
}
