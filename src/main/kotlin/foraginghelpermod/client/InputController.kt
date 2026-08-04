package foraginghelpermod.client

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
 * Client-tick input loop: opens/closes the options HUD and runs read-only tree scanning.
 * Sticks to the current tree until its logs are gone so bit-by-bit chops don't abort mid-tree.
 * Never sends packets or interacts with blocks/entities.
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
			clearScan()
			return
		}

		while (ModKeyBindings.toggleHelper.wasPressed()) {
			openOptions()
		}

		if (!HelperConfig.enabled) {
			clearScan()
			return
		}
		if (client.currentScreen is HelperOptionsScreen) return

		tickCounter++
		if (tickCounter < SCAN_INTERVAL_TICKS) return
		tickCounter = 0

		val eye = player.eyePos
		val origin = player.blockPos
		val clusters = TreeScanner.scanClusters(world, origin, eye, TreeScanner.DEFAULT_RADIUS)
		treeCount = clusters.size

		val selected = resolveCommittedOrPick(clusters, eye)
		nearestTree = selected
		targetLog = selected?.let { TreeScanner.selectTargetLog(it, eye, REACH) }
	}

	/**
	 * Stay on the overlapping committed cluster if any logs remain;
	 * otherwise clear and score a new best tree.
	 */
	private fun resolveCommittedOrPick(clusters: List<TreeCluster>, eye: Vec3d): TreeCluster? {
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

	private fun clearScan() {
		nearestTree = null
		targetLog = null
		treeCount = 0
		tickCounter = 0
		committedPositions = emptySet()
	}

	fun nearestDistance(): Double? =
		nearestTree?.distanceSq?.let { sqrt(it) }

	fun selectedTreeSize(): Int? =
		nearestTree?.size
}
