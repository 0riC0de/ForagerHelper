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
	private const val MAX_SAFE_DROP = 3
	private const val MAX_PARKOUR_DISTANCE = 3
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
		exactDestination: Boolean = false,
	): PathResult? {
		val startStand = resolveStandPos(world, start) ?: return null
		val planningGoal = localPlanningGoal(startStand, goal, maxHorizontalRange)
		val goals = if (planningGoal != goal) {
			collectLocalGoals(world, planningGoal)
		} else if (exactDestination) {
			if (canStandAt(world, goal)) setOf(goal.toImmutable()) else emptySet()
		} else {
			collectGoalStands(world, goal, reach, maxHorizontalRange)
		}
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

	fun hasUsableMiningSpot(world: ClientWorld, goal: BlockPos, reach: Double): Boolean {
		if (world.getBlockState(goal).isAir || world.getBlockState(goal).getHardness(world, goal) < 0f) return false
		val radius = (reach + 1.0).toInt()
		for (dx in -radius..radius) {
			for (dz in -radius..radius) {
				for (dy in -3..3) {
					val feet = goal.add(dx, dy, dz)
					if (!canStandAt(world, feet)) continue
					val eye = Vec3d(feet.x + 0.5, feet.y + 1.62, feet.z + 0.5)
					val center = Vec3d(goal.x + 0.5, goal.y + 0.5, goal.z + 0.5)
					if (eye.squaredDistanceTo(center) <= reach * reach && hasMiningLineOfSight(world, feet, goal)) return true
				}
			}
		}
		return false
	}

	/**
	 * Returns true when an edge is a real gap jump, rather than a smoothed walking segment.
	 * The controller uses this to hold jump and sprint for the whole jump.
	 */
	fun isParkourJump(world: ClientWorld, from: BlockPos, to: BlockPos): Boolean {
		val dx = to.x - from.x
		val dz = to.z - from.z
		val distance = abs(dx) + abs(dz)
		if (distance !in 2..MAX_PARKOUR_DISTANCE || (dx != 0 && dz != 0)) return false
		return parkourLanding(world, from, to, dx.coerceIn(-1, 1), dz.coerceIn(-1, 1)) == to
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
		val visibleGoals = HashSet<BlockPos>()
		val coveredGoals = HashSet<BlockPos>()
		val goalCenter = Vec3d(goal.x + 0.5, goal.y + 0.5, goal.z + 0.5)
		val r = minOf(range, (reach + 2).toInt() + 2)

		for (dx in -r..r) {
			for (dz in -r..r) {
					for (dy in -3..3) {
					val feet = BlockPos(goal.x + dx, goal.y + dy, goal.z + dz)
					if (!canStandAt(world, feet)) continue
					val stand = Vec3d(feet.x + 0.5, feet.y + 1.0, feet.z + 0.5)
					if (stand.squaredDistanceTo(goalCenter) <= reachSq && hasMiningLineOfSight(world, feet, goal)) {
						val safeFeet = feet.toImmutable()
						visibleGoals.add(safeFeet)
						if (hasOverheadCover(world, feet)) coveredGoals.add(safeFeet)
					}
				}
			}
		}
		// Keep every visible elevation layer. Selecting only covered positions here
		// could discard the highest reachable layer when the tree has mixed cover.
		return visibleGoals
	}

	private fun hasOverheadCover(world: ClientWorld, feet: BlockPos): Boolean =
		!isAirLike(world, feet.up(2))

	fun hasMiningLineOfSight(world: ClientWorld, feet: BlockPos, goal: BlockPos): Boolean {
		val from = Vec3d(feet.x + 0.5, feet.y + 1.62, feet.z + 0.5)
		val to = Vec3d(goal.x + 0.5, goal.y + 0.5, goal.z + 0.5)
		val steps = maxOf(abs(goal.x - feet.x), abs(goal.y - feet.y), abs(goal.z - feet.z)) * 3
		if (steps <= 0) return true
		for (i in 1..steps) {
			val t = i.toDouble() / steps
			val pos = BlockPos(
				kotlin.math.floor(from.x + (to.x - from.x) * t).toInt(),
				kotlin.math.floor(from.y + (to.y - from.y) * t).toInt(),
				kotlin.math.floor(from.z + (to.z - from.z) * t).toInt(),
			)
			if (pos != goal && !isAirLike(world, pos)) return false
		}
		return true
	}

	/** True when a solid, non-leaf block is between the player and the log. */
	fun hasNonLeafMiningBlocker(world: ClientWorld, feet: BlockPos, goal: BlockPos): Boolean {
		val from = Vec3d(feet.x + 0.5, feet.y + 1.62, feet.z + 0.5)
		val to = Vec3d(goal.x + 0.5, goal.y + 0.5, goal.z + 0.5)
		val steps = maxOf(abs(goal.x - feet.x), abs(goal.y - feet.y), abs(goal.z - feet.z)) * 3
		for (i in 1..steps) {
			val t = i.toDouble() / steps
			val pos = BlockPos(
				kotlin.math.floor(from.x + (to.x - from.x) * t).toInt(),
				kotlin.math.floor(from.y + (to.y - from.y) * t).toInt(),
				kotlin.math.floor(from.z + (to.z - from.z) * t).toInt(),
			)
			if (pos == goal) continue
			val state = world.getBlockState(pos)
			if (!state.getCollisionShape(world, pos).isEmpty && !TreeScanner.isLeafLike(state)) return true
		}
		return false
	}

	private fun localPlanningGoal(start: BlockPos, goal: BlockPos, maxRange: Int): BlockPos {
		val dx = goal.x - start.x
		val dz = goal.z - start.z
		val distance = maxOf(abs(dx), abs(dz))
		if (distance <= maxRange - 8) return goal
		val step = (maxRange - 8).coerceAtLeast(8)
		val scale = step.toDouble() / distance.toDouble()
		return start.add((dx * scale).toInt(), 0, (dz * scale).toInt()).toImmutable()
	}

	private fun collectLocalGoals(world: ClientWorld, center: BlockPos): Set<BlockPos> {
		val goals = HashSet<BlockPos>()
		for (dx in -2..2) {
			for (dz in -2..2) {
				for (dy in -2..2) {
					val feet = center.add(dx, dy, dz)
					if (canStandAt(world, feet)) goals.add(feet.toImmutable())
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
			} else {
				// Baritone treats an ascent, a descent, and a fall as separate moves.
				// Keep the same distinction so the search does not blindly walk into height changes.
				val up = flat.up()
				if (!tooFar(up, origin, maxRange) && canStandAt(world, up) &&
					isAirLike(world, pos.up(2))) {
					result.add(up.toImmutable() to 1.45)
				}

				for (drop in 1..MAX_SAFE_DROP) {
					val down = flat.down(drop)
					if (tooFar(down, origin, maxRange)) continue
					if (canStandAt(world, down) && clearFallColumn(world, flat, down)) {
						result.add(down.toImmutable() to (1.2 + drop * 0.8))
						break
					}
				}
			}

			val jump = flat.add(dir.vector.x * (MAX_PARKOUR_DISTANCE - 1), 0, dir.vector.z * (MAX_PARKOUR_DISTANCE - 1))
			if (!canStandAt(world, flat) && !tooFar(jump, origin, maxRange)) {
				val landing = parkourLanding(world, pos, jump, dir.vector.x, dir.vector.z)
				if (landing != null) {
					val jumpDistance = abs(landing.x - pos.x) + abs(landing.z - pos.z)
					result.add(landing.toImmutable() to (jumpDistance * 1.15 + 1.8))
				}
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

	private fun parkourLanding(world: ClientWorld, from: BlockPos, to: BlockPos, dx: Int, dz: Int): BlockPos? {
		val distance = abs(to.x - from.x) + abs(to.z - from.z)
		if (distance !in 2..MAX_PARKOUR_DISTANCE || (dx != 0 && dz != 0)) return null
		if (!isAirLike(world, from.up(2))) return null

		// The takeoff and every part of the gap need two blocks of headroom.
		for (i in 1 until distance) {
			val gap = from.add(dx * i, 0, dz * i)
			if (!isAirLike(world, gap) || !isAirLike(world, gap.up())) return null
		}

		// A landing may be flat or one block higher, matching Minecraft's normal jump.
		if (canStandAt(world, to)) return to
		if (canStandAt(world, to.up()) && isAirLike(world, to.up(2))) return to.up()
		return null
	}

	private fun clearFallColumn(world: ClientWorld, fromColumn: BlockPos, landing: BlockPos): Boolean {
		for (y in landing.y + 1..fromColumn.y + 1) {
			if (!isAirLike(world, BlockPos(fromColumn.x, y, fromColumn.z))) return false
		}
		return true
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
