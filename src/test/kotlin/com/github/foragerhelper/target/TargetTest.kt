package com.github.foragerhelper.target

import net.minecraft.block.BlockState
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Headless test environment implementing [TargetEnvironment] for offline JUnit testing.
 *
 * Avoids referencing net.minecraft.block.Blocks directly to eliminate registry freeze
 * and class initialization failures in headless JVM environments.
 */
class FakeTargetEnvironment(
    var playerPosVec: Vec3d = Vec3d(0.0, 64.0, 0.0),
    var playerEyePosVec: Vec3d? = null,
    override var reachDistance: Double = 4.5,
    override var movementSpeed: Double = 0.1
) : TargetEnvironment {
    private val targetBlocks = HashSet<BlockPos>()
    private val solidBlocks = HashSet<BlockPos>()
    private val airBlocks = HashSet<BlockPos>()
    private val stepUpBlocks = HashSet<BlockPos>()

    fun setStepUpBlock(pos: BlockPos) {
        stepUpBlocks.add(pos.toImmutable())
    }

    override fun isStepUpBlock(pos: BlockPos): Boolean = stepUpBlocks.contains(pos)

    fun setTargetBlock(pos: BlockPos) {
        val p = pos.toImmutable()
        targetBlocks.add(p)
        solidBlocks.remove(p)
        airBlocks.remove(p)
    }

    fun setSolid(pos: BlockPos) {
        val p = pos.toImmutable()
        solidBlocks.add(p)
        targetBlocks.remove(p)
        airBlocks.remove(p)
    }

    fun setAir(pos: BlockPos) {
        val p = pos.toImmutable()
        airBlocks.add(p)
        targetBlocks.remove(p)
        solidBlocks.remove(p)
    }

    override fun isAir(pos: BlockPos): Boolean =
        airBlocks.contains(pos) || (!targetBlocks.contains(pos) && !solidBlocks.contains(pos))

    override fun isTargetBlock(pos: BlockPos): Boolean =
        targetBlocks.contains(pos)

    override fun getBlockState(pos: BlockPos): BlockState? = null

    override val playerPos: Vec3d get() = playerPosVec
    override val playerEyePos: Vec3d get() = playerEyePosVec ?: Vec3d(playerPosVec.x, playerPosVec.y + 1.62, playerPosVec.z)
}

/**
 * Tier 1–4 test suite for the Universal Target Framework (M3):
 * NavigationTarget hierarchy, TargetScanner implementations, and TargetManager lifecycle.
 *
 * Runs fully headless (no Minecraft client or window required) using [FakeTargetEnvironment].
 */
class TargetTest {

    private lateinit var env: FakeTargetEnvironment

    @BeforeEach
    fun setUp() {
        env = FakeTargetEnvironment()
    }

    // =========================================================================
    // 1. BlockTarget — unit tests
    // =========================================================================

    @Test
    fun blockTarget_getTargetPos_returnsBlockTopCentre() {
        val pos = BlockPos(5, 63, 5)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)

        val result = target.getTargetPos(env)
        assertNotNull(result)
        assertEquals(5.5, result!!.x, 0.01)
        assertEquals(64.0, result.y, 0.01)
        assertEquals(5.5, result.z, 0.01)
    }

    @Test
    fun blockTarget_getTargetPos_returnsNull_whenAir() {
        val pos = BlockPos(5, 63, 5)
        // Do not set the block -> stays air
        val target = BlockTarget(pos)
        assertNull(target.getTargetPos(env))
    }

    @Test
    fun blockTarget_getFocusPoint_returnsClosestFaceX() {
        val pos = BlockPos(5, 63, 5)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)

        // Player is to the WEST (x < block centre), so closest face is x=5 (west face)
        env.playerEyePosVec = Vec3d(0.0, 63.5, 5.5)
        val focus = target.getFocusPoint(env)
        assertNotNull(focus)
        assertEquals(5.0, focus!!.x, 0.01, "West face x=5")
        assertEquals(63.5, focus.y, 0.01)
        assertEquals(5.5, focus.z, 0.01)
    }

    @Test
    fun blockTarget_getFocusPoint_returnsClosestFaceY() {
        val pos = BlockPos(5, 63, 5)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)

        // Player is BELOW (y < block centre), so closest face is y=63 (bottom)
        env.playerEyePosVec = Vec3d(5.5, 60.0, 5.5)
        val focus = target.getFocusPoint(env)
        assertNotNull(focus)
        assertEquals(63.0, focus!!.y, 0.01, "Bottom face y=63")
    }

    @Test
    fun blockTarget_isInReach_withinReachDistance() {
        val pos = BlockPos(2, 64, 0)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)

        env.playerPosVec = Vec3d(2.5, 64.0, 0.5)
        env.playerEyePosVec = Vec3d(2.5, 65.62, 0.5)
        assertTrue(target.isInReach(env))
    }

    @Test
    fun blockTarget_isInReach_outsideReachDistance() {
        val pos = BlockPos(20, 64, 20)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)
        assertFalse(target.isInReach(env))
    }

    @Test
    fun blockTarget_isValid_false_whenBlockGone() {
        val pos = BlockPos(3, 63, 3)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)
        assertTrue(target.isValid(env))

        env.setAir(pos)
        assertFalse(target.isValid(env))
    }

    @Test
    fun blockTarget_isValid_false_whenPredicateFails() {
        val pos = BlockPos(3, 63, 3)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos, headlessPredicate = { _, _ -> false })
        assertFalse(target.isValid(env), "Predicate that returns false should invalidate target")
    }

    @Test
    fun blockTarget_isCompleted_true_whenInvalidated() {
        val pos = BlockPos(3, 63, 3)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)
        assertFalse(target.isCompleted(env))

        env.setAir(pos)
        assertTrue(target.isCompleted(env))
    }

    @Test
    fun blockTarget_describeStatus_containsCoords() {
        val pos = BlockPos(7, 64, 3)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)
        val status = target.describeStatus(env)
        assertTrue(status.contains("7") && status.contains("64") && status.contains("3"), status)
    }

    // =========================================================================
    // 2. PositionTarget — unit tests
    // =========================================================================

    @Test
    fun positionTarget_getTargetPos_alwaysReturnsPosition() {
        val target = PositionTarget(Vec3d(10.0, 64.0, 10.0))
        val pos = target.getTargetPos(env)
        assertNotNull(pos)
        assertEquals(10.0, pos.x, 0.01)
        assertEquals(64.0, pos.y, 0.01)
        assertEquals(10.0, pos.z, 0.01)
    }

    @Test
    fun positionTarget_isValid_alwaysTrue() {
        val target = PositionTarget(Vec3d(100.0, 64.0, 100.0))
        assertTrue(target.isValid(env))
    }

    @Test
    fun positionTarget_isCompleted_withinArrivalRadius() {
        val pos = Vec3d(1.0, 64.0, 0.0)
        val target = PositionTarget(pos, arrivalRadius = 1.5)
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        assertTrue(target.isCompleted(env))
    }

    @Test
    fun positionTarget_isCompleted_false_outsideArrivalRadius() {
        val pos = Vec3d(10.0, 64.0, 0.0)
        val target = PositionTarget(pos, arrivalRadius = 1.0)
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        assertFalse(target.isCompleted(env))
    }

    @Test
    fun positionTarget_getFocusPoint_hasHeightBias() {
        val target = PositionTarget(Vec3d(5.0, 64.0, 5.0), focusHeightBias = 1.62)
        val focus = target.getFocusPoint(env)
        assertNotNull(focus)
        assertEquals(65.62, focus.y, 0.01)
    }

    @Test
    fun positionTarget_isInReach_withinReach() {
        val pos = Vec3d(1.5, 64.0, 0.0)
        val target = PositionTarget(pos)
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        assertTrue(target.isInReach(env))
    }

    @Test
    fun positionTarget_describeStatus_containsXYZ() {
        val target = PositionTarget(Vec3d(12.3, 64.0, 56.7))
        val status = target.describeStatus(env)
        assertTrue(status.contains("12.3") || status.contains("12"), status)
    }

    // =========================================================================
    // 3. TargetManager lifecycle — unit tests
    // =========================================================================

    @Test
    fun targetManager_initiallyIdle() {
        val manager = TargetManager()
        assertFalse(manager.hasTarget)
        assertNull(manager.activeTarget)
        assertNull(manager.currentGoalPos)
    }

    @Test
    fun targetManager_setTarget_locksTarget() {
        val manager = TargetManager()
        val target = PositionTarget(Vec3d(5.0, 64.0, 5.0))
        manager.setTarget(target)
        assertTrue(manager.hasTarget)
        assertSame(target, manager.activeTarget)
        assertEquals(1, manager.totalTargetsLocked)
    }

    @Test
    fun targetManager_lock_aliasWorks() {
        val manager = TargetManager()
        val target = PositionTarget(Vec3d(1.0, 64.0, 1.0))
        manager.lock(target)
        assertTrue(manager.hasTarget)
        assertEquals(1, manager.totalTargetsLocked)
    }

    @Test
    fun targetManager_clear_transitionsToIdle() {
        val manager = TargetManager()
        manager.setTarget(PositionTarget(Vec3d(5.0, 64.0, 5.0)))
        manager.clear()
        assertFalse(manager.hasTarget)
        assertNull(manager.activeTarget)
        assertNull(manager.currentGoalPos)
    }

    @Test
    fun targetManager_onTargetChanged_calledOnSetAndClear() {
        val manager = TargetManager()
        val events = mutableListOf<NavigationTarget?>()
        manager.onTargetChanged = { events.add(it) }

        val target = PositionTarget(Vec3d(1.0, 64.0, 1.0))
        manager.setTarget(target)
        manager.clear()

        assertEquals(2, events.size)
        assertSame(target, events[0])
        assertNull(events[1])
    }

    @Test
    fun targetManager_tick_updatesGoalAndFocusPos() {
        val manager = TargetManager()
        val target = PositionTarget(Vec3d(8.0, 64.0, 8.0))
        manager.setTarget(target)

        manager.tick(env)

        assertNotNull(manager.currentGoalPos)
        assertEquals(8.0, manager.currentGoalPos!!.x, 0.01)
    }

    @Test
    fun targetManager_tick_dismissesInvalidTarget() {
        val manager = TargetManager()
        val pos = BlockPos(5, 63, 5)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)
        manager.setTarget(target)

        env.setAir(pos)
        val result = manager.tick(env)

        assertNull(result)
        assertFalse(manager.hasTarget)
        assertEquals(1, manager.totalTargetsInvalidated)
    }

    @Test
    fun targetManager_tick_dismissesCompletedTarget() {
        val manager = TargetManager()
        val target = PositionTarget(Vec3d(0.5, 64.0, 0.0), arrivalRadius = 2.0)
        manager.setTarget(target)

        val result = manager.tick(env)

        assertNull(result)
        assertFalse(manager.hasTarget)
        assertEquals(1, manager.totalTargetsCompleted)
    }

    @Test
    fun targetManager_replaceTarget_incrementsCounter() {
        val manager = TargetManager()
        manager.setTarget(PositionTarget(Vec3d(1.0, 64.0, 1.0)))
        manager.setTarget(PositionTarget(Vec3d(2.0, 64.0, 2.0)))
        assertEquals(2, manager.totalTargetsLocked)
    }

    // =========================================================================
    // 4. CustomBlockTargetScanner — unit tests
    // =========================================================================

    @Test
    fun customBlockScanner_findsBlocksWithinRadius() {
        val scanner = CustomBlockTargetScanner(maxResults = 10)

        env.setTargetBlock(BlockPos(3, 64, 0))
        env.setTargetBlock(BlockPos(0, 64, 3))
        env.setTargetBlock(BlockPos(50, 64, 50))

        val results = scanner.scan(env, Vec3d(0.0, 64.0, 0.0), 10.0)
        assertEquals(2, results.size, "Should find 2 target blocks within radius=10")
    }

    @Test
    fun customBlockScanner_sortsByProximity() {
        val scanner = CustomBlockTargetScanner(maxResults = 4)

        env.setTargetBlock(BlockPos(8, 64, 0))
        env.setTargetBlock(BlockPos(2, 64, 0))
        env.setTargetBlock(BlockPos(5, 64, 0))

        val results = scanner.scan(env, Vec3d(0.0, 64.0, 0.0), 16.0)
        assertEquals(3, results.size)
        assertEquals(2, results[0].blockPos.x)
        assertEquals(5, results[1].blockPos.x)
        assertEquals(8, results[2].blockPos.x)
    }

    @Test
    fun customBlockScanner_respectsMaxResults() {
        val scanner = CustomBlockTargetScanner(maxResults = 2)
        for (x in 0..5) {
            env.setTargetBlock(BlockPos(x, 64, 0))
        }
        val results = scanner.scan(env, Vec3d(0.0, 64.0, 0.0), 16.0)
        assertEquals(2, results.size, "maxResults=2 should cap results")
    }

    @Test
    fun customBlockScanner_returnsEmpty_whenNoneFound() {
        val scanner = CustomBlockTargetScanner()
        val results = scanner.scan(env, Vec3d(0.0, 64.0, 0.0), 10.0)
        assertTrue(results.isEmpty())
    }

    @Test
    fun customBlockScanner_excludesBlocksOutsideRadius() {
        val scanner = CustomBlockTargetScanner()
        env.setTargetBlock(BlockPos(15, 64, 0))
        val results = scanner.scan(env, Vec3d(0.0, 64.0, 0.0), 10.0)
        assertTrue(results.isEmpty(), "Block at x=15 is outside radius=10")
    }

    // =========================================================================
    // 5. TreeClusterTargetScanner — unit tests
    // =========================================================================

    @Test
    fun treeScanner_findsLogBlocks() {
        val scanner = TreeClusterTargetScanner(maxResults = 8)
        env.setTargetBlock(BlockPos(2, 64, 0))
        env.setTargetBlock(BlockPos(2, 65, 0))
        env.setTargetBlock(BlockPos(2, 66, 0))

        val results = scanner.scan(env, Vec3d(0.0, 64.0, 0.0), 10.0)
        assertEquals(3, results.size)
    }

    @Test
    fun treeScanner_sortsByProximity() {
        val scanner = TreeClusterTargetScanner()
        env.setTargetBlock(BlockPos(8, 64, 0))
        env.setTargetBlock(BlockPos(2, 64, 0))

        val results = scanner.scan(env, Vec3d(0.0, 64.0, 0.0), 16.0)
        assertEquals(2, results.size)
        assertEquals(2, results[0].blockPos.x, "Closer log first")
    }

    @Test
    fun treeScanner_respectsMaxResults() {
        val scanner = TreeClusterTargetScanner(maxResults = 2)
        for (x in 1..6) env.setTargetBlock(BlockPos(x, 64, 0))
        val results = scanner.scan(env, Vec3d(0.0, 64.0, 0.0), 16.0)
        assertEquals(2, results.size)
    }

    @Test
    fun treeScanner_returnsEmpty_whenNoLogs() {
        val scanner = TreeClusterTargetScanner()
        val results = scanner.scan(env, Vec3d(0.0, 64.0, 0.0), 10.0)
        assertTrue(results.isEmpty())
    }

    // =========================================================================
    // 6. TargetManager scanner acquisition
    // =========================================================================

    @Test
    fun targetManager_acquireFromScanner_locksFirstCandidate() {
        val manager = TargetManager()
        val scanner = CustomBlockTargetScanner(maxResults = 4)
        env.setTargetBlock(BlockPos(3, 64, 0))

        val acquired = manager.acquireFromScanner(scanner, env, Vec3d(0.0, 64.0, 0.0), 10.0)
        assertNotNull(acquired)
        assertTrue(manager.hasTarget)
        assertSame(acquired, manager.activeTarget)
    }

    @Test
    fun targetManager_acquireFromScanner_doesNotReplaceExistingTarget() {
        val manager = TargetManager()
        val existing = PositionTarget(Vec3d(1.0, 64.0, 1.0))
        manager.setTarget(existing)

        val scanner = CustomBlockTargetScanner()
        env.setTargetBlock(BlockPos(3, 64, 0))

        val acquired = manager.acquireFromScanner(scanner, env, Vec3d(0.0, 64.0, 0.0), 10.0)
        assertNull(acquired, "Should not replace existing target")
        assertSame(existing, manager.activeTarget)
    }

    @Test
    fun targetManager_acquireFromScanner_replacesWithForceReplace() {
        val manager = TargetManager()
        manager.setTarget(PositionTarget(Vec3d(1.0, 64.0, 1.0)))

        val scanner = CustomBlockTargetScanner()
        env.setTargetBlock(BlockPos(3, 64, 0))

        val acquired = manager.acquireFromScanner(
            scanner, env, Vec3d(0.0, 64.0, 0.0), 10.0, forceReplace = true
        )
        assertNotNull(acquired)
        assertSame(acquired, manager.activeTarget)
    }

    @Test
    fun targetManager_acquireFromScanner_returnsNull_whenNoCandidates() {
        val manager = TargetManager()
        val scanner = CustomBlockTargetScanner()

        val acquired = manager.acquireFromScanner(scanner, env, Vec3d(0.0, 64.0, 0.0), 10.0)
        assertNull(acquired)
        assertFalse(manager.hasTarget)
    }

    @Test
    fun targetManager_acquireFromScanner_appliesFilter() {
        val manager = TargetManager()
        val scanner = CustomBlockTargetScanner(maxResults = 5)

        env.setTargetBlock(BlockPos(2, 64, 0))
        env.setTargetBlock(BlockPos(4, 64, 0))

        val acquired = manager.acquireFromScanner(
            scanner, env, Vec3d(0.0, 64.0, 0.0), 10.0,
            filter = { it.blockPos.x >= 4 }
        )
        assertNotNull(acquired)
        assertEquals(4, acquired!!.blockPos.x)
    }

    @Test
    fun blockTarget_standOffsetY_shiftsTargetPos() {
        val pos = BlockPos(0, 63, 0)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos, standOffsetY = -0.5)
        val navPos = target.getTargetPos(env)
        assertNotNull(navPos)
        assertEquals(63.5, navPos!!.y, 0.01, "Block top + offset = 64 - 0.5 = 63.5")
    }

    @Test
    fun positionTarget_exactlyAtArrivalRadius_isCompleted() {
        val target = PositionTarget(Vec3d(1.0, 64.0, 0.0), arrivalRadius = 1.0)
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        assertTrue(target.isCompleted(env))
    }

    @Test
    fun positionTarget_justOutsideArrivalRadius_notCompleted() {
        val target = PositionTarget(Vec3d(1.01, 64.0, 0.0), arrivalRadius = 1.0)
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        assertFalse(target.isCompleted(env))
    }

    @Test
    fun targetManager_multipleSetClearCycles_countersAccumulate() {
        val manager = TargetManager()
        repeat(5) {
            manager.setTarget(PositionTarget(Vec3d(1.0, 64.0, 1.0), arrivalRadius = 0.0))
            manager.tick(env)
            manager.clear()
        }
        assertEquals(5, manager.totalTargetsLocked)
    }

    @Test
    fun targetManager_tick_withNoTarget_returnsNull() {
        val manager = TargetManager()
        val result = manager.tick(env)
        assertNull(result)
    }

    @Test
    fun customBlockScanner_name_isSet() {
        val scanner = CustomBlockTargetScanner()
        assertEquals("CustomBlockScanner", scanner.name)
    }

    @Test
    fun treeScanner_name_isSet() {
        val scanner = TreeClusterTargetScanner()
        assertEquals("TreeClusterScanner", scanner.name)
    }

    @Test
    fun mobScanner_name_isSet() {
        val scanner = MobTargetScanner()
        assertEquals("MobTargetScanner", scanner.name)
    }

    @Test
    fun blockTarget_isValid_nullPredicate_acceptsAnyNonAirBlock() {
        val pos = BlockPos(1, 64, 1)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos, expectedBlockTest = null)
        assertTrue(target.isValid(env), "null predicate accepts any non-air block")
    }
}
