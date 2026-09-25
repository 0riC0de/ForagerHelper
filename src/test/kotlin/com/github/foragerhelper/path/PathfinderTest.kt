package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Comprehensive 5-Tier Unit and Integration Test Suite for Milestone 2:
 * Hitbox-Aware 3D A* Pathfinder, SweptBoxLOS, NodePenaltyMap, and PathEnvironment.
 */
class PathfinderTest {

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
    // Tier 1: Functional Invariants & Basic Navigation
    // =========================================================================

    @Test
    fun testFlatCardinalPathExpansion() {
        // Flat platform 20x20 at y = 63
        grid.addFlatPlatform(-5, 15, -5, 15, 63)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(5.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder should find direct cardinal path on flat terrain")
        assertTrue(result.waypoints.isNotEmpty(), "Waypoints must not be empty")

        val lastWaypoint = result.waypoints.last()
        assertEquals(goal.x, lastWaypoint.x, 0.5, "Final waypoint X should match goal")
        assertEquals(64.0, lastWaypoint.y, 0.1, "Final waypoint Y should stay on ground level")
        assertEquals(goal.z, lastWaypoint.z, 0.5, "Final waypoint Z should match goal")
    }

    @Test
    fun testFlatDiagonalPathExpansion() {
        grid.addFlatPlatform(-5, 15, -5, 15, 63)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(4.5, 64.0, 4.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder should find diagonal path on flat terrain")

        val last = result.waypoints.last()
        assertTrue(last.squaredDistanceTo(goal) <= 0.5 * 0.5, "Last waypoint should reach goal")
    }

    @Test
    fun testNarrow1BlockCorridorExpansion() {
        // Floor from z = 0..8 at x = 2
        for (z in 0..8) {
            grid.setSolid(2, 63, z)
        }
        // Walls on both sides: x = 1 and x = 3 (height 2)
        grid.addWall(1, 64, 0, 1, 65, 8)
        grid.addWall(3, 64, 0, 3, 65, 8)

        val start = Vec3d(2.5, 64.0, 0.5)
        val goal = Vec3d(2.5, 64.0, 8.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Player (width 0.6m) should walk down 1.0m corridor without snagging")

        for (wp in result.waypoints) {
            assertEquals(2.5, wp.x, 0.15, "Waypoints should stay centered in corridor")
        }
    }

    @Test
    fun testObstacleAvoidanceAroundCenterPillar() {
        grid.addFlatPlatform(-5, 10, -5, 10, 63)

        // Solid pillar at (2, 64, 0) and (2, 65, 0)
        grid.setSolid(2, 64, 0)
        grid.setSolid(2, 65, 0)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(4.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder should route around center pillar")

        // Confirm none of the waypoints intersect the pillar's 0.6m body box
        for (wp in result.waypoints) {
            val distToPillarCenter = (wp.x - 2.5) * (wp.x - 2.5) + (wp.z - 0.5) * (wp.z - 0.5)
            assertTrue(distToPillarCenter > 0.3 * 0.3, "Waypoint must not collide with pillar")
        }
    }

    @Test
    fun testOneBlockDoorwayClearance() {
        grid.addFlatPlatform(-5, 15, -5, 15, 63)

        // Wall at x = 5 from z = -5..5, with single doorway opening at z = 0
        grid.addWall(5, 64, -5, 5, 65, -1)
        grid.addWall(5, 64, 1, 5, 65, 5)

        val start = Vec3d(2.5, 64.0, 2.5)
        val goal = Vec3d(8.5, 64.0, 2.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder must navigate cleanly through 1-block doorway")

        // Confirm that the path safely crosses through the 1-block doorway at x = 5.5, z = 0.5
        var crossedDoorway = false
        for (i in 0 until result.waypoints.lastIndex) {
            val p1 = result.waypoints[i]
            val p2 = result.waypoints[i + 1]
            if ((p1.x <= 5.5 && p2.x >= 5.5) || (p2.x <= 5.5 && p1.x >= 5.5)) {
                val t = if (abs(p2.x - p1.x) > 1e-4) (5.5 - p1.x) / (p2.x - p1.x) else 0.5
                val zAtDoor = p1.z + (p2.z - p1.z) * t
                if (abs(zAtDoor - 0.5) < 0.35) {
                    crossedDoorway = true
                    break
                }
            }
        }
        assertTrue(crossedDoorway, "Path must cross through the 1-block doorway at x = 5.5, z = 0.5")

        // Confirm no waypoint penetrates the wall blocks
        for (wp in result.waypoints) {
            val inWallZone = wp.x in 4.7..6.3
            if (inWallZone) {
                assertTrue(wp.z in 0.0..1.0, "Waypoint in doorway plane must be inside opening (z in 0..1), was: $wp")
            }
        }
    }

    // =========================================================================
    // Tier 2: Boundary, Geometry, & Vertical Clearance
    // =========================================================================

    @Test
    fun testDiagonalCornerSnaggingElimination() {
        grid.addFlatPlatform(-2, 5, -2, 5, 63)

        // Obstacle at (1, 64, 0)
        grid.setSolid(1, 64, 0)
        grid.setSolid(1, 65, 0)

        // Diagonal trajectory from (0.5, 64.0, -0.5) to (1.5, 64.0, 1.5)
        // A naive 1D ray passes through (1.0, 0.5), which clips corner (1, 0)
        val from = Vec3d(0.0, 64.0, -0.5)
        val to = Vec3d(1.5, 64.0, 1.0)

        val hasLOS = SweptBoxLOS.hasLineOfSight(grid, from, to)
        assertFalse(hasLOS, "SweptBoxLOS must reject diagonal trajectory that clips obstacle corner")

        // Full pathfinder search must not clip corner
        val result = pathfinder.findPath(grid, from, to, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder should find safe non-snagging route around corner")
    }

    @Test
    fun testSweptBoxDirectObstacleCollision() {
        grid.addFlatPlatform(0, 10, 0, 2, 63)
        // Blocker at (3, 64, 0)
        grid.setSolid(3, 64, 0)

        val from = Vec3d(0.5, 64.0, 0.5)
        val to = Vec3d(6.5, 64.0, 0.5)

        assertFalse(SweptBoxLOS.hasLineOfSight(grid, from, to), "Direct blocked path must fail LOS")
    }

    @Test
    fun testBottomSlabSmoothAscentWithoutJump() {
        grid.setSolid(0, 63, 0) // Floor at y = 63, stand height 64.0
        grid.setSlab(1, 64, 0, top = false) // Bottom slab at y = 64, stand height 64.5
        grid.setSolid(2, 64, 0) // Full block at y = 64, stand height 65.0

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(2.5, 65.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder must traverse bottom slab step-up without jumping")

        val slabWp = result.waypoints.find { abs(it.x - 1.5) < 0.3 }
        assertNotNull(slabWp, "Must have waypoint on slab")
        assertEquals(64.5, slabWp!!.y, 0.1, "Waypoint on bottom slab must be at height y = 64.5")
    }

    @Test
    fun testStepDownFromSlab() {
        grid.setSlab(0, 64, 0, top = false) // Slab at 64.5
        grid.setSolid(1, 63, 0) // Full block at 64.0

        val start = Vec3d(0.5, 64.5, 0.5)
        val goal = Vec3d(1.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder must step down 0.5m off slab")
        assertEquals(64.0, result.waypoints.last().y, 0.1, "Final waypoint must be at y = 64.0")
    }

    @Test
    fun testStairAscentInFacingDirection() {
        grid.setSolid(0, 63, 0) // Ground y = 64.0
        grid.setStairs(1, 64, 0, Direction.EAST) // Stairs ascending towards East
        grid.setSolid(2, 64, 0) // Elevated platform y = 65.0

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(2.5, 65.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Stairs should allow smooth ascent onto elevated platform")
    }

    @Test
    fun testJumpUp1BlockWithHeadroom() {
        grid.setSolid(0, 63, 0) // Stand at y = 64.0
        grid.setSolid(1, 64, 0) // Solid block at y = 64, stand at y = 65.0 (1m step up)
        grid.setSolid(2, 64, 0)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(2.5, 65.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "1-block jump up with open sky must succeed")
        assertEquals(65.0, result.waypoints.last().y, 0.1, "Landing waypoint must be at y = 65.0")
    }

    @Test
    fun testJumpUpApexCeilingHeadroomConstraintRejection() {
        grid.setSolid(0, 63, 0) // Stand at y = 64.0
        grid.setSolid(1, 64, 0) // Ledge at y = 65.0

        // Solid ceiling at (0, 66, 0): y = 66 is y_takeoff + 2
        // Player jumps, apex head reaches 64.0 + 1.25 + 1.8 = 67.05, bumps ceiling at 66.0!
        grid.setCeiling(0, 66, 0)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(1.5, 65.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(result.success, "Jump up must be rejected when apex ceiling headroom (< 2.5m) is violated")
    }

    @Test
    fun testSafe1To3BlockDrops() {
        // 1-block drop
        grid.setSolid(0, 64, 0) // Stand at 65.0
        grid.setSolid(1, 63, 0) // Stand at 64.0
        var res = pathfinder.findPath(grid, Vec3d(0.5, 65.0, 0.5), Vec3d(1.5, 64.0, 0.5), 0.5)
        assertTrue(res.success, "1-block drop should succeed")

        // 2-block drop
        grid.clear()
        grid.setSolid(0, 65, 0) // Stand at 66.0
        grid.setSolid(1, 63, 0) // Stand at 64.0
        res = pathfinder.findPath(grid, Vec3d(0.5, 66.0, 0.5), Vec3d(1.5, 64.0, 0.5), 0.5)
        assertTrue(res.success, "2-block drop should succeed")

        // 3-block drop
        grid.clear()
        grid.setSolid(0, 66, 0) // Stand at 67.0
        grid.setSolid(1, 63, 0) // Stand at 64.0
        res = pathfinder.findPath(grid, Vec3d(0.5, 67.0, 0.5), Vec3d(1.5, 64.0, 0.5), 0.5)
        assertTrue(res.success, "3-block drop should succeed")
    }

    @Test
    fun testDropOver3BlocksRejected() {
        grid.setSolid(0, 67, 0) // Stand at 68.0
        grid.setSolid(1, 63, 0) // Stand at 64.0 (4-block drop > 3 blocks)

        val start = Vec3d(0.5, 68.0, 0.5)
        val goal = Vec3d(1.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(result.success, "4-block drop must be rejected as unsafe fall")
    }

    @Test
    fun testParkour1BlockGap() {
        grid.setSolid(0, 63, 0) // Takeoff stand at 64.0
        // (1, 63, 0) is air (1-block gap hole)
        grid.setSolid(2, 63, 0) // Landing stand at 64.0

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(2.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "1-block parkour gap jump must succeed")
        assertEquals(2.5, result.waypoints.last().x, 0.5, "Should land across gap")
    }

    @Test
    fun testParkour2BlockGapFlat() {
        grid.setSolid(0, 63, 0) // Takeoff stand at 64.0
        // (1, 63, 0) and (2, 63, 0) are air (2-block gap)
        grid.setSolid(3, 63, 0) // Landing stand at 64.0

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(3.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "2-block parkour gap jump must succeed")
        assertEquals(3.5, result.waypoints.last().x, 0.5, "Should land across 2-block gap")
    }

    @Test
    fun testParkourGapWithLowCeilingRejected() {
        grid.setSolid(0, 63, 0)
        // Gap at (1, 63, 0)
        grid.setSolid(2, 63, 0)

        // Low ceiling over takeoff block at y = 66
        grid.setCeiling(0, 66, 0)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(2.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(result.success, "Parkour jump must be rejected when takeoff ceiling headroom is violated")
    }

    @Test
    fun testFence15HeightBarrierRejectsJump() {
        grid.setSolid(0, 63, 0) // Stand at 64.0
        grid.setFence(1, 64, 0) // Fence at y = 64 (top is 65.5)
        grid.setSolid(2, 63, 0) // Stand at 64.0

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(2.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(result.success, "1.5m fence cannot be jumped over from flat ground")
    }

    // =========================================================================
    // Tier 3: Dynamic Penalization & Path Smoothing
    // =========================================================================

    @Test
    fun testDynamicPenalizationRerouting() {
        grid.addFlatPlatform(-5, 15, -5, 15, 63)

        // Build two corridors:
        // Corridor A (direct, straight along z = 0, length 6)
        // Corridor B (detour along z = 4, length 12)
        // Block intermediate area:
        grid.addWall(2, 64, 1, 4, 65, 3)
        // Wall on negative side to prevent detour through negative Z:
        grid.addWall(2, 64, -3, 4, 65, -1)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(6.5, 64.0, 0.5)

        // 1. Initial path chooses Corridor A (z = 0)
        val initialPath = pathfinder.findPath(grid, start, goal, 0.5)
        assertTrue(initialPath.success)
        assertTrue(initialPath.waypoints.any { abs(it.z - 0.5) < 0.2 }, "Initial path should use Corridor A")

        // 2. Penalize Corridor A center block
        pathfinder.penalizeNode(BlockPos(3, 64, 0), penalty = 80.0f)

        // 3. New path must detour around Corridor A via Corridor B
        val detourPath = pathfinder.findPath(grid, start, goal, 0.5)
        assertTrue(detourPath.success, "Pathfinder should find alternate route")
        assertTrue(detourPath.waypoints.none { abs(it.x - 3.5) < 0.3 && abs(it.z - 0.5) < 0.3 }, "Detour path must avoid penalized node")
        assertTrue(detourPath.waypoints.any { it.z >= 2.0 }, "Detour path must route through Corridor B")

        // 4. Clear penalties -> restores Corridor A
        pathfinder.clearPenalties()
        val restoredPath = pathfinder.findPath(grid, start, goal, 0.5)
        assertTrue(restoredPath.success)
        assertTrue(restoredPath.waypoints.all { it.z < 2.0 }, "Restored path should use Corridor A again")
    }

    @Test
    fun testDynamicPenaltyTTLDecay() {
        val penaltyMap = NodePenaltyMap(defaultPenalty = 50.0f, defaultDurationMs = 100L)
        val pos = BlockPos(5, 64, 5)

        penaltyMap.penalize(pos, penalty = 50.0f, durationMs = 100L)
        assertTrue(penaltyMap.getPenalty(pos) > 40.0f, "Penalty should be near initial value")

        Thread.sleep(120L)
        assertEquals(0.0f, penaltyMap.getPenalty(pos), 1e-4f, "Penalty must decay to 0 after TTL expiration")
    }

    @Test
    fun testSpatialDiffusionInPenaltyMap() {
        val penaltyMap = NodePenaltyMap(defaultPenalty = 50.0f, diffusionRadius = 1, diffusionFalloff = 0.5f)
        val pos = BlockPos(10, 64, 10)

        penaltyMap.penalize(pos, penalty = 50.0f)
        assertEquals(50.0f, penaltyMap.getPenalty(pos), 0.5f)

        // Adjacent neighbor should receive diffused penalty 25.0f
        val neighbor = BlockPos(11, 64, 10)
        assertEquals(25.0f, penaltyMap.getPenalty(neighbor), 0.5f)

        // Distance 2 should not be affected
        val distant = BlockPos(12, 64, 10)
        assertEquals(0.0f, penaltyMap.getPenalty(distant), 0.01f)
    }

    @Test
    fun testSweptBoxStringPullingSmoothing() {
        grid.addFlatPlatform(-5, 20, -5, 5, 63)

        // Raw collinear path with 10 points
        val rawPath = (0..10).map { Vec3d(it + 0.5, 64.0, 0.5) }
        val smoothed = SweptBoxLOS.smoothPath(grid, rawPath)

        assertEquals(2, smoothed.size, "Straight path in empty space should compress to 2 waypoints (start & goal)")
        assertEquals(0.5, smoothed.first().x, 0.01)
        assertEquals(10.5, smoothed.last().x, 0.01)
    }

    @Test
    fun testSweptBoxChasmShortcutRejection() {
        // Platform A at x = 0..2
        grid.addFlatPlatform(0, 2, 0, 0, 63)
        // Chasm at x = 3..6 (open void)
        // Platform B at x = 7..9
        grid.addFlatPlatform(7, 9, 0, 0, 63)

        val from = Vec3d(1.5, 64.0, 0.5)
        val to = Vec3d(8.5, 64.0, 0.5)

        assertFalse(
            SweptBoxLOS.hasLineOfSight(grid, from, to, requireGroundSupport = true),
            "SweptBoxLOS must reject line-of-sight shortcut across deep chasm"
        )
    }

    @Test
    fun testAnchorNodePreservationDuringSmoothing() {
        grid.addFlatPlatform(0, 10, 0, 0, 63)

        val raw = listOf(
            Vec3d(0.5, 64.0, 0.5),
            Vec3d(1.5, 64.0, 0.5),
            Vec3d(2.5, 64.0, 0.5), // Anchor node
            Vec3d(3.5, 64.0, 0.5),
            Vec3d(4.5, 64.0, 0.5)
        )

        val smoothed = SweptBoxLOS.smoothPath(grid, raw, isAnchorNode = { it == 2 })
        assertTrue(smoothed.any { abs(it.x - 2.5) < 0.01 }, "Anchor node must be preserved in smoothed path")
    }

    // =========================================================================
    // Tier 4: Edge Cases & Safeguards
    // =========================================================================

    @Test
    fun testStartEqualsGoalZeroDistance() {
        grid.setSolid(0, 63, 0)
        val pos = Vec3d(0.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, pos, pos, allowedRange = 0.5)
        assertTrue(result.success, "Start == Goal should immediately return success")
        assertEquals(1, result.waypoints.size, "Should contain exactly 1 waypoint")
    }

    @Test
    fun testUnreachableGoalEnclosedRoomGracefulFailure() {
        grid.addFlatPlatform(-5, 10, -5, 10, 63)

        // Enclosed bedrock cage around (5, 64, 5)
        grid.addWall(4, 64, 4, 6, 66, 6)
        // Hollow interior at (5, 64, 5)
        grid.setAir(5, 64, 5)
        grid.setAir(5, 65, 5)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(5.5, 64.0, 5.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(result.success, "Goal inside solid enclosure must fail gracefully")
        assertNotNull(result.blockedReason, "Blocked reason must be provided")
    }

    @Test
    fun testZeroComputeDeadline() {
        grid.addFlatPlatform(-5, 15, -5, 15, 63)
        val instantPathfinder = AStarPathfinder(maxComputeTimeMs = 0L)

        val result = instantPathfinder.findPath(grid, Vec3d(0.5, 64.0, 0.5), Vec3d(10.5, 64.0, 0.5), 0.5)
        assertFalse(result.success, "Zero deadline should immediately fail with timeout")
        assertTrue(result.blockedReason?.contains("Timeout") == true, "Blocked reason must state timeout")
    }

    @Test
    fun testHazardLavaAvoidance() {
        grid.addFlatPlatform(-2, 10, -2, 10, 63)

        // Lava pit at (2, 63, 0) and (2, 64, 0)
        grid.setAir(2, 63, 0)
        grid.setHazard(2, 63, 0)
        grid.setHazard(2, 64, 0)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(4.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "Pathfinder should navigate around hazard")

        // No waypoint should land on or directly above lava
        for (wp in result.waypoints) {
            val distToLava = (wp.x - 2.5) * (wp.x - 2.5) + (wp.z - 0.5) * (wp.z - 0.5)
            assertTrue(distToLava > 0.25, "Waypoints must not step into lava")
        }
    }

    @Test
    fun testSubBlockCarpetAndCeilingTrapdoorClearance() {
        // Floor at 63. Stand at 64.0
        grid.setSolid(1, 63, 0)
        // Carpet on floor: adds 0.0625 -> standing surface is 64.0625
        grid.addCustomBox(Box(1.0, 64.0, 0.0, 2.0, 64.0625, 1.0))

        // Trapdoor hanging from ceiling at y = 66: collision is [65.8125, 66.0]
        grid.addCustomBox(Box(1.0, 65.8125, 0.0, 2.0, 66.0, 1.0))

        // Effective clearance = 65.8125 - 64.0625 = 1.75m < 1.80m player height!
        val testBox = Box(1.2, 64.0625, 0.2, 1.8, 64.0625 + 1.80, 0.8)
        assertFalse(grid.isPassable(testBox), "Carpet + ceiling trapdoor leaves 1.75m clearance, which must block 1.8m player")
    }

    // =========================================================================
    // Tier 5: Stress & Adversarial Hardening
    // =========================================================================

    @Test
    fun testExtremeDistanceBudgetLimit() {
        // Unreachable target far away without path
        grid.setSolid(0, 63, 0)
        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(500.0, 64.0, 500.0)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(result.success, "Search over void must terminate cleanly")
        assertNotNull(result.blockedReason)
    }

    @Test
    fun testTurnPenaltyFavorsStraightRun() {
        grid.addFlatPlatform(-5, 15, -5, 15, 63)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(8.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success)

        // All waypoints should have z = 0.5 (straight collinear line, zero zigzag)
        for (wp in result.waypoints) {
            assertEquals(0.5, wp.z, 0.01, "Turn penalty must break zigzag symmetry and produce collinear line")
        }
    }
}
