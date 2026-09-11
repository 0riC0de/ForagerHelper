package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger 2 Empirical Adversarial Stress Test Suite for Milestone 2:
 * Performance, Limits, and Edge Cases.
 *
 * Focus Areas:
 * 1. Maximum Expansion Budget (6000 nodes) and Compute Deadline Timeout (50ms) on huge/unreachable mazes.
 * 2. Zero-Distance start == goal (centered, non-centered, within allowedRange).
 * 3. Floating islands and deep chasms (verify swept-box LOS rejects shortcuts over empty air).
 * 4. Parkour gap limits (1-block and 2-block gaps, 3-block gap rejection, mid-air obstacles).
 * 5. NodePenaltyMap diffusion, linear decay, capacity bounding, and thread safety.
 */
class PathfinderLimitsAdversarialTest {

    private lateinit var grid: TestWorldGrid
    private lateinit var pathfinder: AStarPathfinder

    @BeforeEach
    fun setUp() {
        grid = TestWorldGrid()
        pathfinder = AStarPathfinder(
            maxExpansions = 6000,
            maxComputeTimeMs = 50L,
            maxHorizontalRange = 64
        )
    }

    @Test
    fun testZeroDistanceStartEqualsGoalNearBlockCorner() {
        grid.addFlatPlatform(0, 2, 0, 2, 63)
        // Player standing near the corner of block (1, 64, 1): (1.05, 64.0, 1.05)
        val pt = Vec3d(1.05, 64.0, 1.05)

        val result = pathfinder.findPath(grid, pt, pt, allowedRange = 0.5)
        assertTrue(result.success, "Start == Goal near block corner must succeed immediately")
        assertEquals(1, result.waypoints.size)
    }

    // =========================================================================
    // 1. Expansion Budget & Timeout on Huge/Unreachable Mazes
    // =========================================================================

    @Test
    fun testExpansionBudgetExceededOnLargeUnreachableGrid() {
        // Flat platform 80x80
        grid.addFlatPlatform(-40, 40, -40, 40, 63)
        // Goal at (35, 64, 35) sealed inside a solid bedrock enclosure
        grid.addWall(34, 64, 34, 36, 66, 36)
        grid.setAir(35, 64, 35)
        grid.setAir(35, 65, 35)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(35.5, 64.0, 35.5)

        // Custom budget pathfinder: maxExpansions = 1000, large compute timeout
        val budgetPathfinder = AStarPathfinder(
            maxExpansions = 1000,
            maxComputeTimeMs = 5000L,
            maxHorizontalRange = 64
        )

        val result = budgetPathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(result.success, "Should fail when goal is unreachable")
        assertEquals("Node budget exceeded (1000)", result.blockedReason)
    }

    @Test
    fun testComputeDeadlineEnforcementOnHugeMaze() {
        grid.addFlatPlatform(-30, 30, -30, 30, 63)
        // Add alternating comb walls to create a deep winding maze
        for (x in -28..28 step 4) {
            grid.addWall(x, 64, -28, x, 65, 20)
        }
        for (x in -26..26 step 4) {
            grid.addWall(x, 64, -20, x, 65, 28)
        }

        val start = Vec3d(-29.5, 64.0, -29.5)
        val goal = Vec3d(29.5, 64.0, 29.5)

        // Strict 20ms deadline with large expansion budget
        val timeoutPathfinder = AStarPathfinder(
            maxExpansions = 50000,
            maxComputeTimeMs = 20L,
            maxHorizontalRange = 64
        )

        val startTime = System.currentTimeMillis()
        val result = timeoutPathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        val elapsed = System.currentTimeMillis() - startTime

        assertFalse(result.success, "Must stop when deadline expires")
        assertTrue(
            result.blockedReason?.contains("Timeout exceeded") == true ||
            result.blockedReason?.contains("budget exceeded") == true,
            "Blocked reason must report timeout or budget, got: ${result.blockedReason}"
        )
        assertTrue(elapsed < 1000L, "Compute timeout must halt quickly, elapsed: ${elapsed}ms")
    }

    @Test
    fun testTinyBudgetLimitHalt() {
        grid.addFlatPlatform(-10, 10, -10, 10, 63)
        val tinyPathfinder = AStarPathfinder(maxExpansions = 5, maxComputeTimeMs = 5000L)

        val result = tinyPathfinder.findPath(grid, Vec3d(0.5, 64.0, 0.5), Vec3d(9.5, 64.0, 9.5), 0.5)
        assertFalse(result.success)
        assertEquals("Node budget exceeded (5)", result.blockedReason)
    }

    // =========================================================================
    // 2. Zero-Distance Start == Goal & Proximity Edge Cases
    // =========================================================================

    @Test
    fun testZeroDistanceStartEqualsGoalCentered() {
        grid.setSolid(5, 63, 5)
        val pt = Vec3d(5.5, 64.0, 5.5)

        val result = pathfinder.findPath(grid, pt, pt, allowedRange = 0.5)
        assertTrue(result.success, "Start == Goal must immediately succeed")
        assertEquals(1, result.waypoints.size)
        assertEquals(pt.x, result.waypoints[0].x, 0.01)
        assertEquals(pt.y, result.waypoints[0].y, 0.01)
        assertEquals(pt.z, result.waypoints[0].z, 0.01)
    }

    @Test
    fun testStartEqualsGoalNonCenteredCoordinates() {
        grid.addFlatPlatform(0, 2, 0, 2, 63)
        // Player standing at an offset coordinate on the block
        val pt = Vec3d(1.2, 64.0, 1.3)

        val result = pathfinder.findPath(grid, pt, pt, allowedRange = 0.5)
        assertTrue(result.success, "Start == Goal at non-center coordinates must succeed")
        assertEquals(1, result.waypoints.size)
    }

    @Test
    fun testStartAlreadyWithinAllowedRangeOfGoal() {
        grid.addFlatPlatform(0, 2, 0, 2, 63)
        val start = Vec3d(1.5, 64.0, 1.5)
        val goal = Vec3d(1.7, 64.0, 1.6) // Distance ~ 0.22m, well within allowedRange = 0.5m

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "When already within allowed range, search should immediately succeed")
        assertEquals(1, result.waypoints.size)
    }

    // =========================================================================
    // 3. Floating Islands & Deep Chasms (Swept-Box LOS Support)
    // =========================================================================

    @Test
    fun testFloatingIslandsSeparatedByVoidRejectsShortcuts() {
        // Island A: 3x3 platform at (0..2, 64, 0..2)
        grid.addFlatPlatform(0, 2, 0, 2, 63)
        // Void chasm from x = 3 to 7
        // Island B: 3x3 platform at (8..10, 64, 0..2)
        grid.addFlatPlatform(8, 10, 0, 2, 63)

        val from = Vec3d(2.5, 64.0, 1.5)
        val to = Vec3d(8.5, 64.0, 1.5)

        // 1. Direct swept-box LOS must reject traversing over the void
        assertFalse(
            SweptBoxLOS.hasLineOfSight(grid, from, to, requireGroundSupport = true),
            "Swept-box LOS must reject direct shortcut over void between floating islands"
        )

        // 2. Full pathfinder must fail to find a path across the unbridgeable void
        val result = pathfinder.findPath(grid, from, to, allowedRange = 0.5)
        assertFalse(result.success, "Pathfinder must not cross wide chasm between floating islands")
    }

    @Test
    fun testSweptBoxRejectsSteppedFloatingIslandsWithDropOverAir() {
        // Island A at y = 70
        grid.addFlatPlatform(0, 2, 0, 2, 69)
        // Island B at y = 64 (6 blocks below, separated by 3 blocks of air)
        grid.addFlatPlatform(6, 8, 0, 2, 63)

        val from = Vec3d(2.5, 70.0, 1.5)
        val to = Vec3d(6.5, 64.0, 1.5)

        assertFalse(
            SweptBoxLOS.hasLineOfSight(grid, from, to, requireGroundSupport = true),
            "SweptBoxLOS must reject diagonal drop across empty air"
        )
    }

    @Test
    fun testGroundSupportProbeRejectsLethalHazardBelow() {
        // Platform with a 1-block hole containing lava
        grid.addFlatPlatform(0, 4, 0, 0, 63)
        // Lava at (2, 63, 0)
        grid.setAir(2, 63, 0)
        grid.setHazard(2, 63, 0)

        val overHazard = Vec3d(2.5, 64.0, 0.5)
        assertFalse(
            SweptBoxLOS.hasGroundSupport(grid, overHazard),
            "Ground support probe must reject standing directly over a hazard"
        )
    }

    // =========================================================================
    // 4. Parkour Gap Limits
    // =========================================================================

    @Test
    fun testParkour1BlockGapSuccess() {
        grid.setSolid(0, 63, 0) // Platform 1 at x = 0
        // Gap of 1 block at x = 1 (air)
        grid.setSolid(2, 63, 0) // Platform 2 at x = 2

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(2.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "1-block parkour gap must succeed")
        assertEquals(2, result.waypoints.size, "Should have takeoff and landing waypoints")
        assertEquals(2.5, result.waypoints.last().x, 0.1)
    }

    @Test
    fun testParkour2BlockGapSuccess() {
        grid.setSolid(0, 63, 0) // Platform 1 at x = 0
        // Gap of 2 blocks at x = 1 and x = 2 (air)
        grid.setSolid(3, 63, 0) // Platform 2 at x = 3

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(3.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertTrue(result.success, "2-block parkour gap must succeed")
        assertEquals(3.5, result.waypoints.last().x, 0.1)
    }

    @Test
    fun testParkour3BlockGapRejected() {
        grid.setSolid(0, 63, 0) // Platform 1 at x = 0
        // Gap of 3 blocks at x = 1, x = 2, x = 3 (air)
        grid.setSolid(4, 63, 0) // Platform 2 at x = 4

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(4.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(result.success, "3-block parkour gap must be rejected (beyond default limits)")
    }

    @Test
    fun testParkourGapWithMidAirObstacleRejected() {
        grid.setSolid(0, 63, 0)
        // 2-block gap at x = 1, 2
        grid.setSolid(3, 63, 0)
        // Mid-air obstacle hanging at x = 1, y = 64
        grid.setSolid(1, 64, 0)

        val start = Vec3d(0.5, 64.0, 0.5)
        val goal = Vec3d(3.5, 64.0, 0.5)

        val result = pathfinder.findPath(grid, start, goal, allowedRange = 0.5)
        assertFalse(result.success, "Parkour jump with obstructed flight path must be rejected")
    }

    // =========================================================================
    // 5. NodePenaltyMap Diffusion, Decay & Capacity
    // =========================================================================

    @Test
    fun testPenaltyMapDiffusion3DNeighborhood() {
        val penaltyMap = NodePenaltyMap(defaultPenalty = 60.0f, diffusionRadius = 1, diffusionFalloff = 0.5f)
        val center = BlockPos(10, 64, 10)

        penaltyMap.penalize(center, penalty = 60.0f)

        assertEquals(60.0f, penaltyMap.getPenalty(center), 0.5f)

        // All 26 surrounding neighbors in 3x3x3 cube must receive 30.0f
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val neighbor = center.add(dx, dy, dz)
                    assertEquals(30.0f, penaltyMap.getPenalty(neighbor), 0.5f, "Neighbor ($dx, $dy, $dz) should have diffused penalty")
                }
            }
        }

        // Outside radius (distance 2) must receive 0
        assertEquals(0.0f, penaltyMap.getPenalty(center.add(2, 0, 0)), 1e-3f)
        assertEquals(0.0f, penaltyMap.getPenalty(center.add(0, 2, 0)), 1e-3f)
        assertEquals(0.0f, penaltyMap.getPenalty(center.add(0, 0, 2)), 1e-3f)
    }

    @Test
    fun testPenaltyMapMultiSourceDiffusionMaxResolution() {
        val penaltyMap = NodePenaltyMap(defaultPenalty = 50.0f, diffusionRadius = 1, diffusionFalloff = 0.5f)
        val posA = BlockPos(10, 64, 10)
        val posB = BlockPos(12, 64, 10)

        penaltyMap.penalize(posA, penalty = 80.0f) // diffuses 40.0f to (11, 64, 10)
        penaltyMap.penalize(posB, penalty = 40.0f) // diffuses 20.0f to (11, 64, 10)

        val middle = BlockPos(11, 64, 10)
        assertEquals(40.0f, penaltyMap.getPenalty(middle), 0.5f)
    }

    @Test
    fun testPenaltyMapCapacityBoundProtection() {
        val maxCap = 256
        val penaltyMap = NodePenaltyMap(maxCapacity = maxCap, diffusionRadius = 0)

        for (i in 0 until 500) {
            penaltyMap.penalize(BlockPos(i, 64, 0), penalty = 10.0f, durationMs = 60_000L)
        }

        assertTrue(penaltyMap.size <= maxCap, "Penalty map size (${penaltyMap.size}) must not exceed maxCapacity ($maxCap)")
    }

    @Test
    fun testPenaltyMapConcurrentAccessUnderLoad() {
        val penaltyMap = NodePenaltyMap(maxCapacity = 1024, diffusionRadius = 1)
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        val errorCount = AtomicInteger(0)

        for (threadId in 0 until 4) {
            pool.submit {
                try {
                    for (i in 0 until 100) {
                        val pos = BlockPos(threadId * 100 + i, 64, 0)
                        penaltyMap.penalize(pos, penalty = 50.0f)
                        val p = penaltyMap.getPenalty(pos)
                        if (p < 0.0f) errorCount.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Concurrent operations should complete promptly")
        pool.shutdown()
        assertEquals(0, errorCount.get(), "No exceptions or negative penalties allowed under concurrent load")
    }

    // =========================================================================
    // 6. SweptBoxLOS Diagonal Snagging & Doorway Clearance
    // =========================================================================

    @Test
    fun testDiagonalCornersTouchZeroClearanceRejection() {
        grid.addFlatPlatform(0, 3, 0, 3, 63)
        grid.setSolid(0, 64, 0)
        grid.setSolid(1, 64, 1)

        val from = Vec3d(0.5, 64.0, 1.5)
        val to = Vec3d(1.5, 64.0, 0.5)

        assertFalse(
            SweptBoxLOS.hasLineOfSight(grid, from, to),
            "SweptBoxLOS must reject passing between diagonally touching block corners"
        )
    }

    @Test
    fun testDoorwayWidthExact1BlockPassable() {
        grid.addFlatPlatform(0, 4, 0, 4, 63)
        grid.setSolid(2, 64, 1)
        grid.setSolid(2, 64, 3)

        val from = Vec3d(0.5, 64.0, 2.5)
        val to = Vec3d(3.5, 64.0, 2.5)

        assertTrue(
            SweptBoxLOS.hasLineOfSight(grid, from, to),
            "SweptBoxLOS should cleanly allow centered straight line through 1.0m doorway"
        )
    }

    @Test
    fun testNarrowDoorwayBelowHitboxWidthRejected() {
        grid.addFlatPlatform(0, 4, 0, 4, 63)
        grid.addCustomBox(Box(2.0, 64.0, 0.0, 3.0, 66.0, 2.25))
        grid.addCustomBox(Box(2.0, 64.0, 2.75, 3.0, 66.0, 5.0))

        val from = Vec3d(0.5, 64.0, 2.5)
        val to = Vec3d(3.5, 64.0, 2.5)

        assertFalse(
            SweptBoxLOS.hasLineOfSight(grid, from, to),
            "SweptBoxLOS must reject traversing through 0.50m opening when player width is 0.60m"
        )
    }

    @Test
    fun testCeilingPassageBelow180Rejection() {
        grid.addFlatPlatform(0, 4, 0, 2, 63)
        grid.addCustomBox(Box(1.0, 65.70, 0.0, 3.0, 67.0, 2.0))

        val from = Vec3d(0.5, 64.0, 1.0)
        val to = Vec3d(3.5, 64.0, 1.0)

        assertFalse(
            SweptBoxLOS.hasLineOfSight(grid, from, to),
            "SweptBoxLOS must reject ceiling with only 1.70m clearance"
        )
    }
}
