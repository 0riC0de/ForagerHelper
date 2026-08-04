package foraginghelpermod.client.scan

import net.minecraft.util.math.BlockPos

/**
 * A connected group of log-like blocks discovered by flood-fill.
 * Read-only data — never mutates the world or sends packets.
 */
data class TreeCluster(
	val logs: List<BlockPos>,
	/** Closest log to the player when this cluster was scored. */
	val nearestLog: BlockPos,
	/** Lowest log (then closest XZ) — typical chop starting point. */
	val baseLog: BlockPos,
	val distanceSq: Double,
) {
	val size: Int get() = logs.size
}
