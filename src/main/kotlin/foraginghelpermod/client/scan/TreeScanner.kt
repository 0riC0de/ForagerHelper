package foraginghelpermod.client.scan

import net.minecraft.block.BlockState
import net.minecraft.client.world.ClientWorld
import net.minecraft.registry.Registries
import net.minecraft.registry.tag.BlockTags
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import java.util.ArrayDeque

/**
 * Client-side, read-only tree discovery.
 *
 * Scans nearby log-like blocks, flood-fills connected clusters, and picks the nearest tree.
 * Does not break blocks, move the player, or send any packets.
 */
object TreeScanner {
	const val DEFAULT_RADIUS: Int = 12
	const val MAX_CLUSTER_SIZE: Int = 96

	/**
	 * Find every log cluster within [radius] of [origin], scored by distance from [from].
	 */
	fun scanClusters(
		world: ClientWorld,
		origin: BlockPos,
		from: Vec3d,
		radius: Int = DEFAULT_RADIUS,
	): List<TreeCluster> {
		val visited = HashSet<Long>()
		val clusters = ArrayList<TreeCluster>()

		val minX = origin.x - radius
		val maxX = origin.x + radius
		val minY = (origin.y - radius).coerceAtLeast(world.bottomY)
		val maxY = (origin.y + radius).coerceAtMost(world.topYInclusive)
		val minZ = origin.z - radius
		val maxZ = origin.z + radius

		val cursor = BlockPos.Mutable()
		for (x in minX..maxX) {
			for (y in minY..maxY) {
				for (z in minZ..maxZ) {
					cursor.set(x, y, z)
					val key = BlockPos.asLong(x, y, z)
					if (key in visited) continue
					if (!isLogLike(world.getBlockState(cursor))) continue

					val logs = floodFill(world, cursor.toImmutable(), visited, radius, origin)
					if (logs.isEmpty()) continue
					clusters += buildCluster(logs, from)
				}
			}
		}

		return clusters
	}

	/**
	 * Nearest cluster to [from], or null if none found.
	 */
	fun findNearestTree(
		world: ClientWorld,
		origin: BlockPos,
		from: Vec3d,
		radius: Int = DEFAULT_RADIUS,
	): TreeCluster? =
		scanClusters(world, origin, from, radius).minByOrNull { it.distanceSq }

	/**
	 * Best block to chop next inside a cluster: in-reach preferred, then closest, then lowest.
	 * Still read-only — only selects a position.
	 */
	fun selectTargetLog(
		cluster: TreeCluster,
		from: Vec3d,
		reachDistance: Double,
		isUsable: (BlockPos) -> Boolean = { true },
	): BlockPos? {
		val reachSq = reachDistance * reachDistance
		val usable = cluster.logs.filter(isUsable)
		// Never fall back to a covered log. A non-leaf blocker must be dealt with
		// by a different target/path decision instead of making the bot stare at it.
		val candidates = usable
		if (candidates.isEmpty()) return null
		val inReach = candidates.filter { squaredDistance(from, it) <= reachSq }
		val pool = inReach.ifEmpty { candidates }

		return pool.minWith(
			compareBy<BlockPos> { squaredDistance(from, it) }
				.thenBy { it.y }
		)
	}

	fun isLogLike(state: BlockState): Boolean {
		if (state.isAir) return false
		if (state.isIn(BlockTags.LOGS)) return true

		// Fallback for custom server blocks that aren't tagged as vanilla logs.
		val path = Registries.BLOCK.getId(state.block).path
		return path.contains("log") || path.contains("stem") || path.contains("hyphae")
	}

	fun isLeafLike(state: BlockState): Boolean {
		if (state.isIn(BlockTags.LEAVES)) return true
		val path = Registries.BLOCK.getId(state.block).path.lowercase()
		return path.contains("leaves") || path.contains("leaf")
	}

	private fun floodFill(
		world: ClientWorld,
		start: BlockPos,
		visited: MutableSet<Long>,
		scanRadius: Int,
		origin: BlockPos,
	): List<BlockPos> {
		val startKey = start.asLong()
		if (startKey in visited) return emptyList()

		val result = ArrayList<BlockPos>(16)
		val queue = ArrayDeque<BlockPos>()
		queue.add(start)
		visited.add(startKey)

		val mutable = BlockPos.Mutable()
		val maxManhattanFromOrigin = scanRadius + 2

		while (queue.isNotEmpty() && result.size < MAX_CLUSTER_SIZE) {
			val pos = queue.removeFirst()
			if (!isLogLike(world.getBlockState(pos))) continue
			result.add(pos)

			for (dir in Direction.entries) {
				mutable.set(pos, dir)
				val key = mutable.asLong()
				if (key in visited) continue
				if (manhattan(mutable, origin) > maxManhattanFromOrigin) continue
				if (!isLogLike(world.getBlockState(mutable))) continue

				visited.add(key)
				queue.add(mutable.toImmutable())
			}
		}

		return result
	}

	private fun buildCluster(logs: List<BlockPos>, from: Vec3d): TreeCluster {
		var nearest = logs[0]
		var nearestDist = squaredDistance(from, nearest)
		var base = logs[0]

		for (i in 1 until logs.size) {
			val log = logs[i]
			val dist = squaredDistance(from, log)
			if (dist < nearestDist) {
				nearestDist = dist
				nearest = log
			}
			if (log.y < base.y || (log.y == base.y && horizontalDistSq(from, log) < horizontalDistSq(from, base))) {
				base = log
			}
		}

		return TreeCluster(
			logs = logs,
			nearestLog = nearest,
			baseLog = base,
			distanceSq = nearestDist,
		)
	}

	private fun squaredDistance(from: Vec3d, pos: BlockPos): Double {
		val cx = pos.x + 0.5
		val cy = pos.y + 0.5
		val cz = pos.z + 0.5
		val dx = cx - from.x
		val dy = cy - from.y
		val dz = cz - from.z
		return dx * dx + dy * dy + dz * dz
	}

	private fun horizontalDistSq(from: Vec3d, pos: BlockPos): Double {
		val dx = (pos.x + 0.5) - from.x
		val dz = (pos.z + 0.5) - from.z
		return dx * dx + dz * dz
	}

	private fun manhattan(a: BlockPos, b: BlockPos): Int =
		kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y) + kotlin.math.abs(a.z - b.z)
}
