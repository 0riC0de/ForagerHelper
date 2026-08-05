package foraginghelpermod.client.path

import foraginghelpermod.client.scan.TreeScanner
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Client-side A* over walkable block columns.
 * Read-only world queries only — does not send packets.
 */
object AStarPathfinder {
	private const val MAX_NODES = 6000
	private val CARDINALS = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

	data class PathResult(val waypoints: List<BlockPos>)

	/**
	 * Path from [start] (player feet block) toward standing spots within [reach] of [goal].
	 */
	fun findPath(
		world: ClientWorld,
		start: BlockPos,
		goal: BlockPos,
		reach: Double,
		maxHorizontalRange: Int = 28,
	): PathResult? {
		val startStand = resolveStandPos(world, start) ?: return null
		val goals = collectGoalStands(world, goal, reach, maxHorizontalRange)
		if (goals.isEmpty()) return null
		if (startStand in goals) return PathResult(listOf(startStand))

		val open = PriorityQueue<Node>(compareBy { it.f })
		val bestG = HashMap<Long, Double>()
		val startNode = Node(startStand, 0.0, heuristic(startStand, goals), null)
		open.add(startNode)
		bestG[startStand.asLong()] = 0.0

		var expansions = 0
		while (open.isNotEmpty() && expansions < MAX_NODES) {
			val current = open.poll()
			expansions++

			if (current.pos in goals) {
				return PathResult(smoothPath(world, reconstruct(current)))
			}

			val currentKey = current.pos.asLong()
			val knownG = bestG[currentKey] ?: continue
			if (current.g > knownG + 1e-6) continue

			for ((neighbor, stepCost) in neighbors(world, current.pos, startStand, maxHorizontalRange)) {
				val g = current.g + stepCost
				val key = neighbor.asLong()
				val prev = bestG[key]
				if (prev != null && g >= prev) continue

				bestG[key] = g
				open.add(Node(neighbor, g, heuristic(neighbor, goals), current))
			}
		}

		return null
	}

	fun canStandAt(world: ClientWorld, feet: BlockPos): Boolean {
		if (!isAirLike(world, feet) || !isAirLike(world, feet.up())) return false
		if (!world.getFluidState(feet).isEmpty || !world.getFluidState(feet.up()).isEmpty) return false
		val ground = feet.down()
		val groundState = world.getBlockState(ground)
		if (groundState.isAir) return false
		if (TreeScanner.isLogLike(groundState)) return false
		return !groundState.getCollisionShape(world, ground).isEmpty
	}

	private fun isAirLike(world: ClientWorld, pos: BlockPos): Boolean {
		val state = world.getBlockState(pos)
		if (TreeScanner.isLogLike(state)) return false
		return state.getCollisionShape(world, pos).isEmpty
	}

	private fun resolveStandPos(world: ClientWorld, approx: BlockPos): BlockPos? {
		if (canStandAt(world, approx)) return approx.toImmutable()
		for (dy in intArrayOf(-1, 1, -2, 2)) {
			val p = approx.up(dy)
			if (canStandAt(world, p)) return p.toImmutable()
		}
		return null
	}

	private fun collectGoalStands(
		world: ClientWorld,
		goal: BlockPos,
		reach: Double,
		range: Int,
	): Set<BlockPos> {
		val reachSq = reach * reach
		val goals = HashSet<BlockPos>()
		val goalCenter = Vec3d(goal.x + 0.5, goal.y + 0.5, goal.z + 0.5)
		val r = minOf(range, (reach + 2).toInt() + 2)

		for (dx in -r..r) {
			for (dz in -r..r) {
				for (dy in -2..2) {
					val feet = BlockPos(goal.x + dx, goal.y + dy, goal.z + dz)
					if (!canStandAt(world, feet)) continue
					val stand = Vec3d(feet.x + 0.5, feet.y + 1.0, feet.z + 0.5)
					if (stand.squaredDistanceTo(goalCenter) <= reachSq) {
						goals.add(feet.toImmutable())
					}
				}
			}
		}
		return goals
	}

	private fun neighbors(
		world: ClientWorld,
		pos: BlockPos,
		origin: BlockPos,
		maxRange: Int,
	): List<Pair<BlockPos, Double>> {
		val result = ArrayList<Pair<BlockPos, Double>>(16)
		for (dir in CARDINALS) {
			val flat = pos.offset(dir)
			if (tooFar(flat, origin, maxRange)) continue
			if (canStandAt(world, flat)) {
				result.add(flat.toImmutable() to 1.0)
				continue
			}
			val up = flat.up()
			if (!tooFar(up, origin, maxRange) && canStandAt(world, up) && isAirLike(world, pos.up())) {
				result.add(up.toImmutable() to 1.4)
			}
			val down = flat.down()
			if (!tooFar(down, origin, maxRange) && canStandAt(world, down) && isAirLike(world, flat)) {
				result.add(down.toImmutable() to 1.4)
			}
		}

		// Diagonals reduce the staircase/zigzag paths produced by cardinal-only A*.
		for ((dx, dz) in arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
			val diagonal = pos.add(dx, 0, dz)
			val sideA = pos.add(dx, 0, 0)
			val sideB = pos.add(0, 0, dz)
			if (!tooFar(diagonal, origin, maxRange) && canStandAt(world, diagonal) &&
				canStandAt(world, sideA) && canStandAt(world, sideB)) {
				result.add(diagonal.toImmutable() to 1.414)
			}
		}
		return result
	}

	/** Keeps the farthest reachable node in each straight section, like MightyMiner's Bresenham smoothing. */
	private fun smoothPath(world: ClientWorld, raw: List<BlockPos>): List<BlockPos> {
		if (raw.size < 3) return raw
		val result = ArrayList<BlockPos>()
		var current = 0
		result.add(raw[0])
		while (current < raw.lastIndex) {
			var next = current + 1
			for (candidate in raw.lastIndex downTo current + 1) {
				if (abs(raw[candidate].y - raw[current].y) <= 1 && hasLineOfSight(world, raw[current], raw[candidate])) {
					next = candidate
					break
				}
			}
			result.add(raw[next])
			current = next
		}
		return result
	}

	private fun hasLineOfSight(world: ClientWorld, from: BlockPos, to: BlockPos): Boolean {
		val steps = maxOf(abs(to.x - from.x), abs(to.y - from.y), abs(to.z - from.z))
		if (steps == 0) return true
		for (i in 1..steps) {
			val t = i.toDouble() / steps
			val pos = BlockPos(
				kotlin.math.round(from.x + (to.x - from.x) * t).toInt(),
				kotlin.math.round(from.y + (to.y - from.y) * t).toInt(),
				kotlin.math.round(from.z + (to.z - from.z) * t).toInt(),
			)
			if (!canStandAt(world, pos)) return false
		}
		return true
	}

	private fun tooFar(pos: BlockPos, origin: BlockPos, maxRange: Int): Boolean =
		abs(pos.x - origin.x) > maxRange || abs(pos.z - origin.z) > maxRange || abs(pos.y - origin.y) > maxRange

	private fun heuristic(pos: BlockPos, goals: Set<BlockPos>): Double {
		var best = Double.MAX_VALUE
		for (g in goals) {
			val dx = (pos.x - g.x).toDouble()
			val dy = (pos.y - g.y).toDouble()
			val dz = (pos.z - g.z).toDouble()
			val d = sqrt(dx * dx + dy * dy + dz * dz)
			if (d < best) best = d
		}
		return best
	}

	private fun reconstruct(node: Node): List<BlockPos> {
		val path = ArrayList<BlockPos>()
		var cur: Node? = node
		while (cur != null) {
			path.add(cur.pos)
			cur = cur.parent
		}
		path.reverse()
		return path
	}

	private data class Node(
		val pos: BlockPos,
		val g: Double,
		val h: Double,
		val parent: Node?,
	) {
		val f: Double get() = g + h
	}
}
