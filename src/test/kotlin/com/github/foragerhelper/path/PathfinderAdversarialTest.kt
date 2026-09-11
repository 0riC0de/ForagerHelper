package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Challenger 1 Adversarial Stress Test Suite for Milestone 2:
 * Hitbox-Aware 3D A* Pathfinder, SweptBoxLOS, and NodePenaltyMap.
 *
 * Focus Areas:
 * 1. Diagonal Corner-Cutting Snag Elimination (hitbox boundary checks across all 4 quadrants)
 * 2. 1-Block Doorway Clearance (1.0m width, angled approaches, tunnels, 2.0m ceilings)
 * 3. Slab and Stair Traversal (0.5m step-up without jumping, multi-tier ascent/descent)
 * 4. Low Ceilings During Jump (Y+2 fail/refusal vs Y+3 success, apex clearance)
 * 5. Dynamic Node Penalization (multi-corridor rerouting, smoother protection, diffusion)
 */
class PathfinderAdversarialTest {

    private lateinit var grid: TestWorldGrid
    private lateinit var pathfinder: AStarPathfinder

    @BeforeEach
    fun setUp() {
        grid = TestWorldGrid()
        pathfinder = AStarPathfinder(
            maxExpansions = 6000,
            maxComputeTimeMs = 2000L,
            maxHorizontalRange = 64
        )
    }

    // =========================================================================
    // 1. Diagonal Corner-Cutting Snags
    // =========================================================================

    @Test
    fun testSharpDiagonalCornerSnaggingElimination_AllFourQuadrants() {
        // Test corner snagging in all 4 diagonal quadrants: (+1,+1), (+1,-1), (-1,+1), (-1,-1)
        val directions = listOf(
            Pair(1, 1),
            Pair(1, -1),
            Pair(-1, 1),
            Pair(-1, -1)
        )

        for ((dx, dz) in directions) {
            grid.clear()
            // Platform 10x10 around origin at y = 63
            grid.addFlatPlatform(-5, 5, -5, 5, 63)

            // Place an interior corner pillar at (dx, 64, 0)
            grid.setSolid(dx, 64, 0)
            grid.setSolid(dx, 65, 0)

            // Attempt a diagonal trajectory from (0, 64, 0) to (dx, 64, dz)
            val from = Vec3d(0.5, 64.0, 0.5)
            val to = Vec3d(dx + 0.5, 64.0, dz + 0.5)

            // Direct line of sight must be rejected because bounding box would clip corner
            val hasLOS = SweptBoxLOS.hasLineOfSight(grid, from, to)
            assertFalse(
                hasLOS,
                "SweptBoxLOS must reject diagonal trajectory cutting corner ($dx, 0) towards ($dx, $dz)"
            )

            // Full pathfinder search must find a safe route around the corner
            val result = pathfinder.findPath(grid, from, to, allowedRange = 0.5)
            assertTrue(
                result.success,
                "Pathfinder must find safe non-snagging route around corner ($dx, 0) towards ($dx, $dz)"
            )

            // Verify that no point along any segment of the smoothed path penetrates the obstacle
            // The obstacle box is [dx, 64, 0, dx+1, 66, 1]
            val obsMinX = dx.toDouble()
            val obsMaxX = (dx + 1).toDouble()
            val obsMinZ = 0.0
            val obsMaxZ = 1.0

            for (i in 0 until result.waypoints.lastIndex) {
                val p1 = result.waypoints[i]
                val p2 = result.waypoints[i + 1]
                val dist = p1.distanceTo(p2)
                val subSteps = max(1, (dist / 0.05).toInt())

                for (s in 0..subSteps) {
                    val t = s.toDouble() / subSteps
                    val x = p1.x + (p2.x - p1.x) * t
                    val z = p1.z + (p2.z - p1.z) * t

                    // Player AABB half-width is 0.30m
                    val playerMinX = x - 0.30
                    val playerMaxX = x + 0.30
                    val playerMinZ = z - 0.30
                    val playerMaxZ = z + 0.30

                    // Check if player bounding box penetrates obstacle
                    val overlapsX = playerMinX < obsMaxX && playerMaxX > obsMinX
                    val overlapsZ = playerMinZ < obsMaxZ && playerMaxZ > obsMinZ
                    val penetrates = overlapsX && overlapsZ

                    assertFalse(
                        penetrates,
                        "Trajectory at ($x, $z) in direction ($dx, $dz) penetrates corner block: player=[$playerMinX..$playerMaxX, $playerMinZ..$playerMaxZ]"
                    )
                }
            }
        }
    }

    @Test
    fun testDiagonalPinchZeroWidthOpeningRejected() {
        grid.addFlatPlatform(-3, 5, -3, 5, 63)

        // Two solid blocks touching only at the corner: (1, 64, 0) and (0, 64, 1)
        // This creates a diagonal pinch with 0.0m opening width.
        grid.setSolid(1, 64, 0)
        grid.setSolid(1, 65, 0)
        grid.setSolid(0, 64, 1)
        grid.setSolid(0, 65, 1)

        val from = Vec3d(0.5, 64.0, 0.5)
        val to = Vec3d(1.5, 64.0, 1.5)

        // 1. Direct diagonal line-of-sight must fail
        assertFalse(
            SweptBoxLOS.hasLineOfSight(grid, from, to),
            "Direct diagonal through 0m corner pinch must fail line of sight"
        )

        // 2. Full pathfinder: must NOT take the diagonal pinch
        val result = pathfinder.findPath(grid, from, to, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder should detour around the diagonal pinch")

        // Waypoints must detour around the pinch, not squeeze through (0.5..1.0, 0.5..1.0)
        for (wp in result.waypoints) {
            val throughPinch = wp.x in 0.8..1.2 && wp.z in 0.8..1.2
            assertFalse(throughPinch, "Waypoint must not squeeze through 0-width diagonal pinch: $wp")
        }
    }

    // =========================================================================
    // 2. Doorway Clearance (1-Block Wide Openings)
    // =========================================================================

    @Test
    fun testDoorwayClearance_SingleBlockOpeningSmoothTraversal() {
        grid.addFlatPlatform(-5, 15, -5, 15, 63)

        // Wall at x = 5 from z = -5..5 with a single 1-block doorway at z = 0 (opening [5, 64, 0])
        grid.addWall(5, 64, -5, 5, 65, -1)
        grid.addWall(5, 64, 1, 5, 65, 5)

        // Angled start and goal approaching the doorway diagonally
        val start = Vec3d(2.5, 64.0, 3.5)
        val goal = Vec3d(8.5, 64.0, -2.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder must successfully traverse 1-block doorway from angled approach")

        // Verify that the path crosses the doorway plane (x = 5.0 to 6.0) inside the opening [0.0, 1.0]
        for (wp in result.waypoints) {
            if (wp.x in 4.8..6.2) {
                // Must be safely inside the doorway opening with player radius 0.30m
                assertTrue(
                    wp.z in 0.30..0.70,
                    "Waypoint passing doorway at x=${wp.x} must stay centered in opening (z in 0.30..0.70), was z=${wp.z}"
                )
            }
        }
    }

    @Test
    fun testDoorwayClearance_LongNarrow1BlockTunnel() {
        // 15-block long corridor: width 1.0m (x = 2), walls at x = 1 and x = 3, z = 0..14
        for (z in 0..14) {
            grid.setSolid(2, 63, z) // Floor
            grid.setSolid(1, 64, z) // Left wall (height 2)
            grid.setSolid(1, 65, z)
            grid.setSolid(3, 64, z) // Right wall (height 2)
            grid.setSolid(3, 65, z)
        }

        val start = Vec3d(2.5, 64.0, 0.5)
        val goal = Vec3d(2.5, 64.0, 14.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder must traverse entire 15-block long 1-wide tunnel without false collision")

        for (wp in result.waypoints) {
            assertEquals(2.5, wp.x, 0.15, "Waypoints in 1-wide corridor must stay centered at x = 2.5")
            assertEquals(64.0, wp.y, 0.1, "Waypoints must stay on floor")
        }
    }

    @Test
    fun testDoorwayClearance_CeilingAtExactlyYPlus2Allows1Point8mPlayer() {
        grid.addFlatPlatform(-2, 10, -2, 5, 63)

        // Door frame at x = 3:
        // Left wall: (3, 64, -1) and (3, 65, -1)
        // Right wall: (3, 64, 1) and (3, 65, 1)
        // Door lintel (ceiling) at y = 66: (3, 66, 0)
        // Headroom = 66.0 - 64.0 = 2.0m. Player height = 1.8m.
        grid.setSolid(3, 64, -1)
        grid.setSolid(3, 65, -1)
        grid.setSolid(3, 64, 1)
        grid.setSolid(3, 65, 1)
        grid.setSolid(3, 66, 0) // Ceiling lintel

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(6.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "1.8m player must cleanly traverse 2.0m doorway with ceiling lintel at Y+2")
    }

    // =========================================================================
    // 3. Slab and Stair Traversal
    // =========================================================================

    @Test
    fun testSlabTraversal_MultiStepAscendingStaircaseWithoutJumping() {
        // Multi-tier slab staircase ascending by 0.5m each step:
        // x = 0: Full block at 63 -> stand height 64.0
        // x = 1: Bottom slab at 64 -> stand height 64.5
        // x = 2: Full block at 64 -> stand height 65.0
        // x = 3: Bottom slab at 65 -> stand height 65.5
        // x = 4: Full block at 65 -> stand height 66.0
        grid.setSolid(0, 63, 0)
        grid.setSlab(1, 64, 0, top = false)
        grid.setSolid(2, 64, 0)
        grid.setSlab(3, 65, 0, top = false)
        grid.setSolid(4, 65, 0)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(4.5, 66.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder must traverse multi-step slab staircase")

        // Verify height progression: must reach 66.0 at end
        assertEquals(66.0, result.waypoints.last().y, 0.1, "Final waypoint must be at height 66.0")

        // Verify that intermediate waypoints exist at expected slab and block heights
        val heights = result.waypoints.map { it.y }
        assertTrue(heights.any { abs(it - 64.5) < 0.15 }, "Must have waypoint on slab 1 (64.5)")
        assertTrue(heights.any { abs(it - 65.0) < 0.15 }, "Must have waypoint on block 2 (65.0)")
        assertTrue(heights.any { abs(it - 65.5) < 0.15 }, "Must have waypoint on slab 3 (65.5)")
    }

    @Test
    fun testSlabTraversal_MultiStepDescendingStaircaseSmoothDescent() {
        // Reverse direction: descending from 66.0 to 64.0
        grid.setSolid(0, 65, 0)             // 66.0
        grid.setSlab(1, 65, 0, top = false) // 65.5
        grid.setSolid(2, 64, 0)             // 65.0
        grid.setSlab(3, 64, 0, top = false) // 64.5
        grid.setSolid(4, 63, 0)             // 64.0

        val start = Vec3d(0.5, 66.0, 0.5)
        val goal = Vec3d(4.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder must traverse descending slab staircase")
        assertEquals(64.0, result.waypoints.last().y, 0.1, "Final waypoint must be at height 64.0")
    }

    @Test
    fun testSlabTraversal_LowCeilingRejection() {
        // Takeoff at 64.0, Slab at (1, 64, 0) stand height 64.5
        grid.setSolid(0, 63, 0)
        grid.setSlab(1, 64, 0, top = false)

        // Case A: Low ceiling above slab at y = 66
        // Clearance = 66.0 - 64.5 = 1.5m < 1.8m player height!
        grid.setCeiling(1, 66, 0)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(1.5, 64.5, 0.5)

        val resultBlocked = pathfinder.findPath(grid, start, goal, allowedRange = 0.2)
        assertFalse(resultBlocked.success, "Stepping onto slab with 1.5m headroom (< 1.8m) must fail")

        // Case B: Raise ceiling to y = 67 -> clearance = 2.5m >= 1.8m
        grid.setAir(1, 66, 0)
        grid.setCeiling(1, 67, 0)

        val resultClear = pathfinder.findPath(grid, start, goal, allowedRange = 0.2)
        assertTrue(resultClear.success, "Stepping onto slab with 2.5m headroom must succeed")
    }

    @Test
    fun testStairTraversal_AscendingAndDescendingAllDirections() {
        val testDirs = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

        for (dir in testDirs) {
            grid.clear()
            val dx = dir.vector.x
            val dz = dir.vector.z

            // Flat ground at (0, 63, 0) -> stand 64.0
            grid.setSolid(0, 63, 0)
            // Stairs at (dx, 64, dz) facing dir
            grid.setStairs(dx, 64, dz, dir)
            // Elevated platform at (2*dx, 64, 2*dz) -> stand 65.0
            grid.setSolid(2 * dx, 64, 2 * dz)

            val start = Vec3d(0.5, 64.0, 0.5)
            val goal = Vec3d(2 * dx + 0.5, 65.0, 2 * dz + 0.5)

            // Ascend stairs
            val resultAscend = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
            assertTrue(resultAscend.success, "Stairs ascending in direction $dir must succeed")

            // Descend stairs
            val resultDescend = pathfinder.findPath(grid, goal, start, allowedRange = 0.5)
            assertTrue(resultDescend.success, "Stairs descending in direction $dir must succeed")
        }
    }

    // =========================================================================
    // 4. Low Ceilings During Jump
    // =========================================================================

    @Test
    fun testLowCeilingDuringJump_YPlus2FailsAndYPlus3Succeeds() {
        // Takeoff at (0, 63, 0) -> stand height 64.0
        // Ledge at (1, 64, 0) -> stand height 65.0 (1-block jump up)
        grid.setSolid(0, 63, 0)
        grid.setSolid(1, 64, 0)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(1.5, 65.0, 0.5)

        // Case A: Ceiling at Y+2 relative to takeoff floor (y = 66)
        // Apex head reaches 64.0 + 1.25 + 1.8 = 67.05, bumps ceiling at 66.0!
        grid.setCeiling(0, 66, 0)

        val resultY2 = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(
            resultY2.success,
            "1-block jump up with ceiling at Y+2 (y=66) must FAIL/REFUSE due to apex clearance violation"
        )

        // Case B: Remove ceiling at Y+2, place ceiling at Y+3 (y = 67)
        // Apex headroom box [65.8, 66.5] is clear of y = 67.
        // Landing box at ledge [65.02, 66.8] is also clear of y = 67.
        grid.setAir(0, 66, 0)
        grid.setCeiling(0, 67, 0)
        grid.setCeiling(1, 67, 0) // Ceiling over landing block as well

        val resultY3 = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(
            resultY3.success,
            "1-block jump up with ceiling at Y+3 (y=67) must SUCCEED"
        )
        assertEquals(65.0, resultY3.waypoints.last().y, 0.1, "Landing waypoint must reach 65.0")
    }

    @Test
    fun testLowCeilingOverLandingBlockFailsJump() {
        // Takeoff at (0, 63, 0) -> stand 64.0
        // Ledge at (1, 64, 0) -> stand 65.0
        grid.setSolid(0, 63, 0)
        grid.setSolid(1, 64, 0)

        // Open sky over takeoff (y = 64 has no ceiling)
        // BUT low ceiling over landing block at y = 66 (only 1.0m headroom above ledge)
        grid.setCeiling(1, 66, 0)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(1.5, 65.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(
            result.success,
            "Jump must FAIL when landing block headroom is only 1.0m (< 1.8m player height)"
        )
    }

    @Test
    fun testParkourGap_CeilingAtYPlus2FailsAndYPlus3Succeeds() {
        // Takeoff at (0, 63, 0) -> stand 64.0
        // Gap at (1, 63, 0) -> air
        // Landing at (2, 63, 0) -> stand 64.0
        grid.setSolid(0, 63, 0)
        grid.setSolid(2, 63, 0)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(2.5, 64.0, 0.5)

        // Ceiling at Y+2 (y = 66) over takeoff
        grid.setCeiling(0, 66, 0)
        val resultY2 = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(resultY2.success, "Parkour jump with ceiling at Y+2 must FAIL")

        // Raise ceiling to Y+3 (y = 67)
        grid.setAir(0, 66, 0)
        grid.setCeiling(0, 67, 0)
        grid.setCeiling(1, 67, 0)
        grid.setCeiling(2, 67, 0)

        val resultY3 = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(resultY3.success, "Parkour jump with ceiling at Y+3 must SUCCEED")
    }

    // =========================================================================
    // 5. Dynamic Node Penalization
    // =========================================================================

    @Test
    fun testDynamicPenalization_MultiCorridorRerouting() {
        grid.addFlatPlatform(-5, 20, -5, 20, 63)

        // Create 3 corridors connecting start (0, 64, 0) to goal (10, 64, 0):
        // Corridor A (direct along z = 0, length 10)
        // Corridor B (detour along z = 4, length ~18)
        // Corridor C (wider detour along z = 8, length ~26)
        // Block intermediate spaces:
        grid.addWall(2, 64, 1, 8, 65, 3)  // Wall between A and B
        grid.addWall(2, 64, 5, 8, 65, 7)  // Wall between B and C
        grid.addWall(-5, 64, -5, 20, 65, -1) // Full-width south wall (closes x=0..1 and x=9+ flanks)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(10.5, 64.0, 0.5)

        // 1. Initial path chooses Corridor A (z = 0)
        val path1 = pathfinder.findPath(grid, start, goal, 0.5)
        assertTrue(path1.success, "Initial search must find Corridor A")
        assertTrue(path1.waypoints.all { it.z < 2.0 }, "Path 1 should use Corridor A (z < 2.0)")

        // 2. Penalize Corridor A center block
        pathfinder.penalizeNode(BlockPos(5, 64, 0), penalty = 100.0f)

        // 3. New path must choose Corridor B (z in 3..6)
        val path2 = pathfinder.findPath(grid, start, goal, 0.5)
        assertTrue(path2.success, "Detour search must find Corridor B")
        assertTrue(
            path2.waypoints.any { it.z in 3.0..6.0 },
            "Path 2 must route through Corridor B (z in 3..6)"
        )
        assertTrue(
            path2.waypoints.none { abs(it.x - 5.5) < 0.5 && abs(it.z - 0.5) < 0.5 },
            "Path 2 must NOT pass through penalized node (5, 64, 0)"
        )

        // 4. Penalize Corridor B center block as well
        pathfinder.penalizeNode(BlockPos(5, 64, 4), penalty = 100.0f)

        // 5. New path must choose Corridor C (z in 7..10)
        val path3 = pathfinder.findPath(grid, start, goal, 0.5)
        assertTrue(path3.success, "Second detour search must find Corridor C")
        assertTrue(
            path3.waypoints.any { it.z >= 7.0 },
            "Path 3 must route through Corridor C (z >= 7.0)"
        )

        // 6. Clear penalties -> restores Corridor A immediately
        pathfinder.clearPenalties()
        val path4 = pathfinder.findPath(grid, start, goal, 0.5)
        assertTrue(path4.success, "Restored search must find Corridor A")
        assertTrue(path4.waypoints.all { it.z < 2.0 }, "Path 4 must return to Corridor A (z < 2.0)")
    }

    @Test
    fun testDynamicPenalization_SweptBoxSmootherDoesNotCutThroughPenalizedNode() {
        grid.addFlatPlatform(-5, 20, -5, 15, 63)

        // Open field, but center block (5, 64, 0) is penalized
        pathfinder.penalizeNode(BlockPos(5, 64, 0), penalty = 150.0f)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(10.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, 0.5)
        assertTrue(result.success)

        // The smoothed path must NOT cut straight through (5.5, 64.0, 0.5)
        for (i in 0 until result.waypoints.lastIndex) {
            val p1 = result.waypoints[i]
            val p2 = result.waypoints[i + 1]
            // If segment crosses x = 5.5
            if ((p1.x <= 5.5 && p2.x >= 5.5) || (p2.x <= 5.5 && p1.x >= 5.5)) {
                val t = if (abs(p2.x - p1.x) > 1e-4) (5.5 - p1.x) / (p2.x - p1.x) else 0.5
                val zAt5 = p1.z + (p2.z - p1.z) * t
                // The z coordinate at x = 5.5 must NOT be 0.5 (penalized block)
                assertFalse(
                    abs(zAt5 - 0.5) < 0.2,
                    "Smoother must not shortcut through penalized node at (5, 64, 0), was at z=$zAt5"
                )
            }
        }
    }

    @Test
    fun testDynamicPenalization_SpatialDiffusionImpact() {
        val penaltyMap = NodePenaltyMap(defaultPenalty = 60.0f, diffusionRadius = 1, diffusionFalloff = 0.5f)
        val center = BlockPos(10, 64, 10)
        penaltyMap.penalize(center, penalty = 60.0f)

        // Center penalty
        assertEquals(60.0f, penaltyMap.getPenalty(center), 0.5f)

        // Surrounding 8 horizontal neighbors must receive 30.0f diffused penalty
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val neighbor = center.add(dx, 0, dz)
                assertEquals(
                    30.0f,
                    penaltyMap.getPenalty(neighbor),
                    0.5f,
                    "Neighbor at ($dx, $dz) must receive diffused penalty"
                )
            }
        }

        // Distance 2 blocks must receive 0 penalty
        assertEquals(0.0f, penaltyMap.getPenalty(center.add(2, 0, 0)), 0.01f)
        assertEquals(0.0f, penaltyMap.getPenalty(center.add(0, 0, -2)), 0.01f)
    }

    // =========================================================================
    // 6. Extreme Edge Cases & Complex Scenarios
    // =========================================================================

    @Test
    fun testExtremeZigzagMazeNoCornerClipping() {
        // Labyrinth with 5 sharp 90-degree turns:
        // Corridor 1: (0, 64, 0) to (5, 64, 0)
        // Turn 1 to: (5, 64, 4)
        // Turn 2 to: (1, 64, 4)
        // Turn 3 to: (1, 64, 8)
        // Turn 4 to: (6, 64, 8)
        grid.addFlatPlatform(-2, 10, -2, 12, 63)

        // Place maze dividing walls
        grid.addWall(0, 64, 1, 4, 65, 3)
        grid.addWall(2, 64, 5, 7, 65, 7)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(5.5, 64.0, 8.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder must navigate 5-turn zigzag labyrinth")

        // Verify none of the waypoints or segments clip any of the maze walls
        for (wp in result.waypoints) {
            val inWall1 = wp.x in 0.0..5.0 && wp.z in 1.0..4.0
            val inWall2 = wp.x in 2.0..8.0 && wp.z in 5.0..8.0
            assertFalse(inWall1, "Waypoint must not penetrate Wall 1: $wp")
            assertFalse(inWall2, "Waypoint must not penetrate Wall 2: $wp")
        }
    }

    @Test
    fun testNegativeCoordinatesAndLargeOffsets() {
        // Platform centered at (-1000, 63, -1000)
        grid.addFlatPlatform(-1010, -990, -1010, -990, 63)

        // Pillar at (-1003, 64, -1000)
        grid.setSolid(-1003, 64, -1000)
        grid.setSolid(-1003, 65, -1000)

        val start = Vec3d(-1005.5, 64.0, -1000.5)
        val goal = Vec3d(-998.5, 64.0, -1000.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder must function identically in large negative coordinates")
        assertTrue(result.waypoints.isNotEmpty())

        val last = result.waypoints.last()
        assertEquals(goal.x, last.x, 0.5)
        assertEquals(goal.z, last.z, 0.5)
    }
}
