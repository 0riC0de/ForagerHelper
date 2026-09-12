package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Interface contract for 3D A* pathfinding matching PROJECT.md.
 */
interface Pathfinder {
    /**
     * Computes a hitbox-aware, smoothed 3D trajectory from [start] to [goal].
     */
    fun findPath(world: World, start: Vec3d, goal: Vec3d, allowedRange: Double = 1.0): PathResult

    /**
     * Executes 3D A* pathfinding using a decoupled [PathEnvironment].
     */
    fun findPath(env: PathEnvironment, start: Vec3d, goal: Vec3d, allowedRange: Double = 1.0): PathResult

    /**
     * Registers a temporary dynamic traversal penalty at [pos] to break stuck loops.
     */
    fun penalizeNode(pos: BlockPos, penalty: Float = 50.0f)

    /**
     * Clears all dynamic node penalties.
     */
    fun clearPenalties()
}

/**
 * Result of a pathfinding search.
 */
data class PathResult(
    val success: Boolean,
    val waypoints: List<Vec3d>,
    val blockedReason: String? = null
)

/**
 * Movement action descriptor for edge transitions.
 */
enum class MoveAction {
    START,
    WALK,
    WALK_DIAGONAL,
    STEP_UP,
    STEP_DOWN,
    JUMP_UP,
    DROP,
    PARKOUR;

    val isAnchor: Boolean
        get() = this == JUMP_UP || this == DROP || this == PARKOUR
}

/**
 * Discrete search state in the 3D A* priority queue.
 */
class PathNode(
    val pos: BlockPos,
    val posVec: Vec3d,
    var g: Double,
    val h: Double,
    var parent: PathNode?,
    val action: MoveAction,
    val dirX: Int = 0,
    val dirZ: Int = 0
) : Comparable<PathNode> {

    val f: Double get() = g + h

    override fun compareTo(other: PathNode): Int {
        val cmp = this.f.compareTo(other.f)
        if (cmp != 0) return cmp
        return this.h.compareTo(other.h)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathNode) return false
        return this.pos == other.pos && abs(this.posVec.y - other.posVec.y) < 0.1
    }

    override fun hashCode(): Int = pos.hashCode() * 31 + (posVec.y * 10).toInt()
}

data class NeighborEdge(
    val node: PathNode,
    val cost: Double,
    val dirX: Int,
    val dirZ: Int
)

/**
 * Hitbox-Aware 3D A* Pathfinder with swept-box line-of-sight smoothing.
 */
class AStarPathfinder(
    val penaltyMap: NodePenaltyMap = NodePenaltyMap(),
    val maxExpansions: Int = 6000,
    val maxComputeTimeMs: Long = 50L,
    val maxHorizontalRange: Int = 64,
    val turnPenaltyWeight: Double = 0.15,
    val tieBreakerWeight: Double = 1e-4
) : Pathfinder {

    private val CARDINALS = arrayOf(
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    )

    private val DIAGONALS = arrayOf(
        Triple(-1, -1, 1.4142),
        Triple(-1, 1, 1.4142),
        Triple(1, -1, 1.4142),
        Triple(1, 1, 1.4142)
    )

    override fun penalizeNode(pos: BlockPos, penalty: Float) {
        penaltyMap.penalize(pos, penalty)
    }

    override fun clearPenalties() {
        penaltyMap.clear()
    }

    override fun findPath(
        world: World,
        start: Vec3d,
        goal: Vec3d,
        allowedRange: Double
    ): PathResult {
        return findPath(WorldPathEnvironment(world), start, goal, allowedRange)
    }

    /**
     * Executes 3D A* pathfinding using a decoupled [PathEnvironment].
     */
    override fun findPath(
        env: PathEnvironment,
        start: Vec3d,
        goal: Vec3d,
        allowedRange: Double
    ): PathResult {
        if (start.x.isNaN() || start.y.isNaN() || start.z.isNaN() ||
            goal.x.isNaN() || goal.y.isNaN() || goal.z.isNaN()) {
            return PathResult(success = false, waypoints = emptyList(), blockedReason = "Invalid coordinates (NaN)")
        }

        if (maxComputeTimeMs <= 0L) {
            return PathResult(success = false, waypoints = emptyList(), blockedReason = "Timeout exceeded (${maxComputeTimeMs}ms)")
        }

        val startPos = BlockPos.ofFloored(start.x, start.y, start.z)
        val standH = env.getStandHeight(startPos)
        val startGroundY = standH ?: if (env.isSolid(startPos.down())) startPos.y.toDouble() else start.y
        val startVec = Vec3d(startPos.x + 0.5, startGroundY, startPos.z + 0.5)

        val allowedRangeSq = allowedRange * allowedRange
        // Use raw start (not block-snapped startVec) so start≈goal is always caught
        if (start.squaredDistanceTo(goal) <= allowedRangeSq) {
            return PathResult(success = true, waypoints = listOf(start))
        }

        val startNode = PathNode(
            pos = startPos,
            posVec = startVec,
            g = 0.0,
            h = computeHeuristic(startVec, goal, startVec),
            parent = null,
            action = MoveAction.START
        )

        val open = PriorityQueue<PathNode>()
        val bestG = HashMap<Long, Double>()

        open.add(startNode)
        bestG[nodeKey(startPos, startGroundY)] = 0.0

        val deadline = System.currentTimeMillis() + maxComputeTimeMs
        var expansions = 0

        while (open.isNotEmpty()) {
            if (expansions >= maxExpansions) {
                return PathResult(success = false, waypoints = emptyList(), blockedReason = "Node budget exceeded ($maxExpansions)")
            }
            if ((expansions and 63) == 0 && System.currentTimeMillis() > deadline) {
                return PathResult(success = false, waypoints = emptyList(), blockedReason = "Timeout exceeded (${maxComputeTimeMs}ms)")
            }

            val current = open.poll()
            expansions++

            if (current.posVec.squaredDistanceTo(goal) <= allowedRangeSq) {
                val rawNodes = reconstruct(current)
                val rawWaypoints = rawNodes.map { it.posVec }
                val isAnchor: (Int) -> Boolean = { idx -> rawNodes[idx].action.isAnchor }
                val smoothed = SweptBoxLOS.smoothPath(env, rawWaypoints, penaltyMap, isAnchor)
                return PathResult(success = true, waypoints = smoothed)
            }

            val currentKey = nodeKey(current.pos, current.posVec.y)
            val recordedG = bestG[currentKey]
            if (recordedG != null && current.g > recordedG + 1e-6) continue

            val neighbors = generateNeighbors(env, current, startPos, goal, startVec)
            for (edge in neighbors) {
                val nextNode = edge.node
                val turnPenalty = calculateTurnPenalty(current.dirX, current.dirZ, edge.dirX, edge.dirZ)
                val dynPenalty = penaltyMap.getPenalty(nextNode.pos).toDouble()
                val tentativeG = current.g + edge.cost + turnPenalty + dynPenalty

                val nextKey = nodeKey(nextNode.pos, nextNode.posVec.y)
                val prevG = bestG[nextKey]
                if (prevG == null || tentativeG < prevG - 1e-6) {
                    bestG[nextKey] = tentativeG
                    nextNode.g = tentativeG
                    nextNode.parent = current
                    open.add(nextNode)
                }
            }
        }

        return PathResult(success = false, waypoints = emptyList(), blockedReason = "No reachable path to goal")
    }

    private fun generateNeighbors(
        env: PathEnvironment,
        current: PathNode,
        origin: BlockPos,
        goal: Vec3d,
        start: Vec3d
    ): List<NeighborEdge> {
        val result = ArrayList<NeighborEdge>(16)
        val pos = current.pos
        val curY = current.posVec.y

        // 1. Cardinal Moves
        for (dir in CARDINALS) {
            val dx = dir.vector.x
            val dz = dir.vector.z
            val targetPos = pos.add(dx, 0, dz)
            if (abs(targetPos.x - origin.x) > maxHorizontalRange || abs(targetPos.z - origin.z) > maxHorizontalRange) continue

            val groundY = env.getStandHeight(targetPos)
            val groundYUp = env.getStandHeight(targetPos.up())

            // 1. Step Up (0.5m slabs and stairs)
            // Case A: Slab at targetPos (e.g. from flat ground 64.0 onto slab 64.5)
            if (groundY != null && (groundY - curY) in 0.35..0.65) {
                val targetBox = Box(targetPos.x + 0.2, groundY + 0.02, targetPos.z + 0.2, targetPos.x + 0.8, groundY + 1.8, targetPos.z + 0.8)
                val takeoffHeadroom = Box(pos.x + 0.2, curY + 1.8, pos.z + 0.2, pos.x + 0.8, groundY + 1.8, pos.z + 0.8)
                if (env.isPassable(targetBox) && env.isPassable(takeoffHeadroom)) {
                    val vec = Vec3d(targetPos.x + 0.5, groundY, targetPos.z + 0.5)
                    val node = PathNode(targetPos, vec, 0.0, computeHeuristic(vec, goal, start), null, MoveAction.STEP_UP, dx, dz)
                    result.add(NeighborEdge(node, 1.10, dx, dz))
                }
                continue
            }
            // Case B: Step up onto full block at targetPos.up() (e.g. from slab 64.5 onto full block 65.0)
            if (groundYUp != null && (groundYUp - curY) in 0.35..0.65) {
                val destPos = targetPos.up()
                val targetBox = Box(destPos.x + 0.2, groundYUp + 0.02, destPos.z + 0.2, destPos.x + 0.8, groundYUp + 1.8, destPos.z + 0.8)
                val takeoffHeadroom = Box(pos.x + 0.2, curY + 1.8, pos.z + 0.2, pos.x + 0.8, groundYUp + 1.8, pos.z + 0.8)
                if (env.isPassable(targetBox) && env.isPassable(takeoffHeadroom)) {
                    val vec = Vec3d(destPos.x + 0.5, groundYUp, destPos.z + 0.5)
                    val node = PathNode(destPos, vec, 0.0, computeHeuristic(vec, goal, start), null, MoveAction.STEP_UP, dx, dz)
                    result.add(NeighborEdge(node, 1.10, dx, dz))
                }
                continue
            }

            // 2. Flat Cardinal Walk
            if (groundY != null && abs(groundY - curY) < 0.1) {
                val box = Box(targetPos.x + 0.2, groundY + 0.02, targetPos.z + 0.2, targetPos.x + 0.8, groundY + 1.8, targetPos.z + 0.8)
                if (env.isPassable(box)) {
                    val vec = Vec3d(targetPos.x + 0.5, groundY, targetPos.z + 0.5)
                    val node = PathNode(targetPos, vec, 0.0, computeHeuristic(vec, goal, start), null, MoveAction.WALK, dx, dz)
                    result.add(NeighborEdge(node, 1.0, dx, dz))
                }
            } else if (groundY != null && (curY - groundY) in 0.35..0.65) {
                // 3. Step Down (0.5m off slab)
                val box = Box(targetPos.x + 0.2, groundY + 0.02, targetPos.z + 0.2, targetPos.x + 0.8, curY + 1.8, targetPos.z + 0.8)
                if (env.isPassable(box)) {
                    val vec = Vec3d(targetPos.x + 0.5, groundY, targetPos.z + 0.5)
                    // Use actual foot-level block pos so subsequent expansions call
                    // pos.add(dx,0,dz) from the right Y column (e.g. y=65 not y=66).
                    val nodePos = BlockPos.ofFloored(vec)
                    val node = PathNode(nodePos, vec, 0.0, computeHeuristic(vec, goal, start), null, MoveAction.STEP_DOWN, dx, dz)
                    result.add(NeighborEdge(node, 1.05, dx, dz))
                }
            } else {
                // 4. 1-Block Jump Up
                if (groundYUp != null && abs(groundYUp - (curY + 1.0)) < 0.2) {
                    val jumpPos = targetPos.up()
                    // Apex headroom check above takeoff: requires 2.5m vertical clearance
                    val takeoffApexBox = Box(pos.x + 0.2, curY + 1.8, pos.z + 0.2, pos.x + 0.8, curY + 2.5, pos.z + 0.8)
                    if (env.isPassable(takeoffApexBox)) {
                        val landingBox = Box(jumpPos.x + 0.2, groundYUp + 0.02, jumpPos.z + 0.2, jumpPos.x + 0.8, groundYUp + 1.8, jumpPos.z + 0.8)
                        if (env.isPassable(landingBox)) {
                            val vec = Vec3d(jumpPos.x + 0.5, groundYUp, jumpPos.z + 0.5)
                            val node = PathNode(jumpPos, vec, 0.0, computeHeuristic(vec, goal, start), null, MoveAction.JUMP_UP, dx, dz)
                            result.add(NeighborEdge(node, 1.50, dx, dz))
                        }
                    }
                }

                // 5. Drop Down 1 to 3 blocks
                for (drop in 1..3) {
                    val dropPos = targetPos.down(drop)
                    if (abs(dropPos.x - origin.x) > maxHorizontalRange || abs(dropPos.z - origin.z) > maxHorizontalRange) continue
                    val dropGround = env.getStandHeight(dropPos)
                    if (dropGround != null && abs(dropGround - (curY - drop)) < 0.2) {
                        val shaftBox = Box(dropPos.x + 0.2, dropGround + 0.02, dropPos.z + 0.2, dropPos.x + 0.8, curY + 1.8, dropPos.z + 0.8)
                        if (env.isPassable(shaftBox)) {
                            val vec = Vec3d(dropPos.x + 0.5, dropGround, dropPos.z + 0.5)
                            val node = PathNode(dropPos, vec, 0.0, computeHeuristic(vec, goal, start), null, MoveAction.DROP, dx, dz)
                            val dropCost = 1.20 + (drop - 1) * 0.45
                            result.add(NeighborEdge(node, dropCost, dx, dz))
                            break
                        }
                    }
                }
            }

            // Parkour Gap Jumps (1 to 2 block gaps)
            for (gapDist in 2..3) {
                val landingPos = pos.add(dx * gapDist, 0, dz * gapDist)
                if (abs(landingPos.x - origin.x) > maxHorizontalRange || abs(landingPos.z - origin.z) > maxHorizontalRange) continue
                val landingGroundY = env.getStandHeight(landingPos)
                if (landingGroundY != null && abs(landingGroundY - curY) < 0.2) {
                    val takeoffApexBox = Box(pos.x + 0.2, curY + 1.8, pos.z + 0.2, pos.x + 0.8, curY + 2.5, pos.z + 0.8)
                    if (!env.isPassable(takeoffApexBox)) continue

                    var gapClear = true
                    var gapPenaltyCost = 0.0
                    for (g in 1 until gapDist) {
                        val gapPos = pos.add(dx * g, 0, dz * g)
                        val gapBox = Box(gapPos.x + 0.1, curY, gapPos.z + 0.1, gapPos.x + 0.9, curY + 2.0, gapPos.z + 0.9)
                        if (!env.isPassable(gapBox)) {
                            gapClear = false
                            break
                        }
                        // Accumulate penalties for gap nodes so parkour doesn't free-skip penalized blocks
                        gapPenaltyCost += penaltyMap.getPenalty(BlockPos.ofFloored(gapPos.x + 0.5, curY, gapPos.z + 0.5)).toDouble()
                    }
                    if (!gapClear) continue

                    val landingBox = Box(landingPos.x + 0.2, landingGroundY + 0.02, landingPos.z + 0.2, landingPos.x + 0.8, landingGroundY + 1.8, landingPos.z + 0.8)
                    if (env.isPassable(landingBox)) {
                        val pCost = if (gapDist == 2) 2.50 else 3.50
                        val vec = Vec3d(landingPos.x + 0.5, landingGroundY, landingPos.z + 0.5)
                        val node = PathNode(landingPos, vec, 0.0, computeHeuristic(vec, goal, start), null, MoveAction.PARKOUR, dx, dz)
                        result.add(NeighborEdge(node, pCost + gapPenaltyCost, dx, dz))
                        break
                    }
                }
            }
        }

        // 2. Diagonal Flat Moves (Corner Snagging Prevention)
        for ((dx, dz, cost) in DIAGONALS) {
            val diagPos = pos.add(dx, 0, dz)
            if (abs(diagPos.x - origin.x) > maxHorizontalRange || abs(diagPos.z - origin.z) > maxHorizontalRange) continue

            // Corner check: BOTH orthogonal side blocks must have full headroom clearance
            val sideA = pos.add(dx, 0, 0)
            val sideB = pos.add(0, 0, dz)
            val sideABox = Box(sideA.x + 0.1, curY + 0.02, sideA.z + 0.1, sideA.x + 0.9, curY + 1.8, sideA.z + 0.9)
            val sideBBox = Box(sideB.x + 0.1, curY + 0.02, sideB.z + 0.1, sideB.x + 0.9, curY + 1.8, sideB.z + 0.9)
            if (!env.isPassable(sideABox) || !env.isPassable(sideBBox)) {
                continue // Corner snagging prevented!
            }

            val diagGroundY = env.getStandHeight(diagPos)
            if (diagGroundY != null && abs(diagGroundY - curY) < 0.1) {
                val box = Box(diagPos.x + 0.2, diagGroundY + 0.02, diagPos.z + 0.2, diagPos.x + 0.8, diagGroundY + 1.8, diagPos.z + 0.8)
                if (env.isPassable(box)) {
                    val vec = Vec3d(diagPos.x + 0.5, diagGroundY, diagPos.z + 0.5)
                    val node = PathNode(diagPos, vec, 0.0, computeHeuristic(vec, goal, start), null, MoveAction.WALK_DIAGONAL, dx, dz)
                    result.add(NeighborEdge(node, cost, dx, dz))
                }
            }
        }

        return result
    }

    private fun calculateTurnPenalty(prevX: Int, prevZ: Int, newX: Int, newZ: Int): Double {
        if (prevX == 0 && prevZ == 0) return 0.0
        if (prevX == newX && prevZ == newZ) return 0.0
        val dot = prevX * newX + prevZ * newZ
        return when (dot) {
            1 -> 0.05
            0 -> 0.15
            -1 -> 0.35
            else -> 0.60
        }
    }

    private fun computeHeuristic(pos: Vec3d, goal: Vec3d, start: Vec3d): Double {
        val dx = pos.x - goal.x
        val dy = pos.y - goal.y
        val dz = pos.z - goal.z
        val baseH = sqrt(dx * dx + dy * dy + dz * dz)
        val scaledH = baseH * (1.0 + tieBreakerWeight)

        // Cross product tie-breaker
        val lx = goal.x - start.x
        val lz = goal.z - start.z
        val px = pos.x - start.x
        val pz = pos.z - start.z
        val cross = abs(lx * pz - lz * px)

        return scaledH + cross * tieBreakerWeight
    }

    private fun nodeKey(pos: BlockPos, floorY: Double): Long {
        val slabBit = if (floorY - pos.y > 0.25) 1L else 0L
        return (pos.asLong() shl 1) or slabBit
    }

    private fun reconstruct(endNode: PathNode): List<PathNode> {
        val list = ArrayList<PathNode>()
        var curr: PathNode? = endNode
        while (curr != null) {
            list.add(curr)
            curr = curr.parent
        }
        list.reverse()
        return list
    }
}
