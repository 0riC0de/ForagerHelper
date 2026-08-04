package foraginghelpermod.client

import foraginghelpermod.client.scan.TreeCluster
import foraginghelpermod.client.scan.TreeScanner
import foraginghelpermod.client.ui.HelperOptionsScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.BlockPos
import kotlin.math.sqrt

/**
 * Client-tick input loop: opens/closes the options HUD and runs read-only tree scanning.
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

	private var tickCounter: Int = 0

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
		val nearest = clusters.minByOrNull { it.distanceSq }
		nearestTree = nearest
		targetLog = nearest?.let { TreeScanner.selectTargetLog(it, eye, REACH) }
	}

	private fun clearScan() {
		nearestTree = null
		targetLog = null
		treeCount = 0
		tickCounter = 0
	}

	fun nearestDistance(): Double? =
		nearestTree?.distanceSq?.let { sqrt(it) }
}
