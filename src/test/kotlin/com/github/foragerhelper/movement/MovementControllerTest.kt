package com.github.foragerhelper.movement

import com.github.foragerhelper.path.PathEnvironment
import com.github.foragerhelper.path.PathResult
import com.github.foragerhelper.path.Pathfinder
import com.github.foragerhelper.target.BlockTarget
import com.github.foragerhelper.target.FakeTargetEnvironment
import com.github.foragerhelper.target.PositionTarget
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MovementControllerTest {

    private lateinit var env: FakeTargetEnvironment
    private lateinit var controller: DefaultMovementController

    @BeforeEach
    fun setUp() {
        env = FakeTargetEnvironment()
        controller = DefaultMovementController()
    }

    // =========================================================================
    // 1. Initial State & Stop Tests
    // =========================================================================

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(MovementState.IDLE, controller.state)
        assertFalse(controller.isNavigating)
        assertNull(controller.activeTarget)
        assertTrue(controller.currentWaypoints.isEmpty())
        assertEquals("Idle", controller.currentStatus)
    }

    @Test
    fun testSetDestinationTransitionsToPathing() {
        val target = PositionTarget(Vec3d(10.0, 64.0, 10.0))
        controller.setDestination(target)

        assertEquals(MovementState.PATHING, controller.state)
        assertTrue(controller.isNavigating)
        assertSame(target, controller.activeTarget)
    }

    @Test
    fun testStopResetsStateAndWaypoints() {
        val target = PositionTarget(Vec3d(10.0, 64.0, 10.0))
        controller.setDestination(target)
        controller.stop()

        assertEquals(MovementState.IDLE, controller.state)
        assertFalse(controller.isNavigating)
        assertNull(controller.activeTarget)
        assertTrue(controller.currentWaypoints.isEmpty())
    }

    // =========================================================================
    // 2. Decoupled WASD Vector Projection Tests
    // =========================================================================

    @Test
    fun testDecoupledWASD_ForwardFacingSouth() {
        // Yaw = 0 faces South (+Z). Waypoint is at (0, 64, 5).
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(0.0, 64.0, 5.0), arrivalRadius = 0.5)
        controller.setDestination(target)

        val input = controller.tick(env, playerYaw = 0.0f)
        assertTrue(input.forward, "Should press forward when target is ahead")
        assertFalse(input.back, "Should not press back")
        assertFalse(input.left, "Should not press left")
        assertFalse(input.right, "Should not press right")
    }

    @Test
    fun testDecoupledWASD_ForwardFacingWest() {
        // Yaw = 90 faces West (-X). Waypoint is at (-5, 64, 0).
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(-5.0, 64.0, 0.0), arrivalRadius = 0.5)
        controller.setDestination(target)

        val input = controller.tick(env, playerYaw = 90.0f)
        assertTrue(input.forward, "Should press forward when facing West toward -X target")
        assertFalse(input.back)
        assertFalse(input.left)
        assertFalse(input.right)
    }

    @Test
    fun testDecoupledWASD_StrafeRightFacingSouth() {
        // Yaw = 0 faces South (+Z). Waypoint is at (+5, 64, 0). Right is East (+X).
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(5.0, 64.0, 0.0), arrivalRadius = 0.5)
        controller.setDestination(target)

        val input = controller.tick(env, playerYaw = 0.0f)
        assertTrue(input.right, "Should strafe right when target is to the right")
        assertFalse(input.forward, "Should not press forward")
        assertFalse(input.left)
        assertFalse(input.back)
    }

    @Test
    fun testDecoupledWASD_StrafeLeftFacingSouth() {
        // Yaw = 0 faces South (+Z). Waypoint is at (-5, 64, 0). Left is West (-X).
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(-5.0, 64.0, 0.0), arrivalRadius = 0.5)
        controller.setDestination(target)

        val input = controller.tick(env, playerYaw = 0.0f)
        assertTrue(input.left, "Should strafe left when target is to the left")
        assertFalse(input.right)
        assertFalse(input.forward)
        assertFalse(input.back)
    }

    @Test
    fun testDecoupledWASD_BackwardsFacingSouth() {
        // Yaw = 0 faces South (+Z). Waypoint is at (0, 64, -5). Back is North (-Z).
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(0.0, 64.0, -5.0), arrivalRadius = 0.5)
        controller.setDestination(target)

        val input = controller.tick(env, playerYaw = 0.0f)
        assertTrue(input.back, "Should press back when target is behind")
        assertFalse(input.forward)
        assertFalse(input.left)
        assertFalse(input.right)
    }

    @Test
    fun testDecoupledWASD_DiagonalForwardRight() {
        // Yaw = 0 faces South (+Z). Target is at (+5, 64, +5) (South-East).
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(5.0, 64.0, 5.0), arrivalRadius = 0.5)
        controller.setDestination(target)

        val input = controller.tick(env, playerYaw = 0.0f)
        assertTrue(input.forward, "Should press forward on diagonal")
        assertTrue(input.right, "Should press right on diagonal")
        assertFalse(input.left)
        assertFalse(input.back)
    }

    @Test
    fun testStepUpTriggersJump() {
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(0.0, 65.0, 10.0), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(Vec3d(0.0, 65.0, 2.0), Vec3d(0.0, 65.0, 10.0)))

        val input = controller.tick(env, playerYaw = 0.0f)
        assertTrue(input.jump, "Should jump when climbing 1 block elevation")
    }

    // =========================================================================
    // 3. Waypoint Advancement & State Lifecycle Tests
    // =========================================================================

    @Test
    fun testWaypointProgressionAdvancesIndex() {
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(10.0, 64.0, 0.0), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(Vec3d(2.0, 64.0, 0.0), Vec3d(10.0, 64.0, 0.0)))

        controller.tick(env, playerYaw = -90.0f)
        assertEquals(0, controller.currentWaypointIndex, "Still at waypoint 0 because dist=2.0m > 0.65m")

        // Move player near waypoint 0 (dist < 0.65m)
        env.playerPosVec = Vec3d(1.9, 64.0, 0.0)
        controller.tick(env, playerYaw = -90.0f)
        assertEquals(1, controller.currentWaypointIndex, "Should advance to waypoint index 1")
    }

    @Test
    fun testInReachTransitionsState() {
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        env.playerEyePosVec = Vec3d(0.0, 65.62, 0.0)
        val pos = BlockPos(1, 64, 0)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)
        controller.setDestination(target)

        val input = controller.tick(env, playerYaw = -90.0f)
        assertEquals(MovementState.IN_REACH, controller.state)
        assertFalse(input.forward, "Should not move forward when in reach")
        assertTrue(input.sneak, "Should sneak in reach")
    }

    @Test
    fun testCompletedTransitionsState() {
        env.playerPosVec = Vec3d(5.0, 64.0, 5.0)
        val target = PositionTarget(Vec3d(5.2, 64.0, 5.2), arrivalRadius = 1.0)
        controller.setDestination(target)

        val input = controller.tick(env, playerYaw = 0.0f)
        assertEquals(MovementState.COMPLETED, controller.state)
        assertFalse(input.hasMotion)
    }

    @Test
    fun testInvalidTargetStopsNavigation() {
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val pos = BlockPos(5, 64, 0)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)
        controller.setDestination(target)

        // Destroy target block -> becomes air
        env.setAir(pos)

        val input = controller.tick(env, playerYaw = -90.0f)
        assertEquals(MovementState.IDLE, controller.state)
        assertFalse(controller.isNavigating)
        assertFalse(input.hasMotion)
    }

    // =========================================================================
    // 4. Unstuck Handler Unit Tests
    // =========================================================================

    @Test
    fun testUnstuckHandler_normalMovementDoesNotTrigger() {
        val unstuck = UnstuckHandler(stuckThresholdTicks = 10)
        for (i in 1..20) {
            val input = unstuck.tick(Vec3d(i * 0.2, 64.0, 0.0), isMoving = true)
            assertNull(input, "No recovery input while moving freely")
            assertFalse(unstuck.isRecovering)
        }
    }

    @Test
    fun testUnstuckHandler_stationaryWhenNotMovingDoesNotTrigger() {
        val unstuck = UnstuckHandler(stuckThresholdTicks = 10)
        for (i in 1..20) {
            val input = unstuck.tick(Vec3d(0.0, 64.0, 0.0), isMoving = false)
            assertNull(input, "No recovery input when movement is not commanded")
            assertFalse(unstuck.isRecovering)
        }
    }

    @Test
    fun testUnstuckHandler_stagnationTriggersTier1JumpNudge() {
        val unstuck = UnstuckHandler(stuckThresholdTicks = 5, tier1DurationTicks = 4)
        val pos = Vec3d(0.0, 64.0, 0.0)

        // 5 ticks of no movement while moving
        var triggeredInput: MovementInput? = null
        for (tick in 1..5) {
            triggeredInput = unstuck.tick(pos, isMoving = true)
        }

        assertNotNull(triggeredInput)
        assertEquals(UnstuckTier.TIER_1_JUMP_NUDGE, unstuck.currentTier)
        assertTrue(triggeredInput!!.forward)
        assertTrue(triggeredInput.jump)
        assertTrue(unstuck.isRecovering)
    }

    @Test
    fun testUnstuckHandler_escalatesToTier2ReverseStrafe() {
        val unstuck = UnstuckHandler(stuckThresholdTicks = 2, tier1DurationTicks = 1, tier2DurationTicks = 4)
        val pos = Vec3d(0.0, 64.0, 0.0)

        // Trigger Tier 1
        unstuck.tick(pos, isMoving = true)
        unstuck.tick(pos, isMoving = true)
        assertEquals(UnstuckTier.TIER_1_JUMP_NUDGE, unstuck.currentTier)

        // Complete Tier 1 duration
        unstuck.tick(pos, isMoving = true)
        assertEquals(UnstuckTier.NONE, unstuck.currentTier)

        // Trigger stuck again -> Tier 2
        unstuck.tick(pos, isMoving = true)
        val tier2Input = unstuck.tick(pos, isMoving = true)
        assertEquals(UnstuckTier.TIER_2_REVERSE_STRAFE, unstuck.currentTier)
        assertNotNull(tier2Input)
        assertTrue(tier2Input!!.back, "Tier 2 must reverse")
        assertTrue(tier2Input.left || tier2Input.right, "Tier 2 must strafe")
    }

    @Test
    fun testUnstuckHandler_escalatesToTier3RepathPenalize() {
        val unstuck = UnstuckHandler(
            stuckThresholdTicks = 1,
            tier1DurationTicks = 1,
            tier2DurationTicks = 1
        )
        val pos = Vec3d(5.0, 64.0, 5.0)

        // Init pos
        unstuck.tick(pos, isMoving = true)

        // Cycle through Tier 1
        unstuck.tick(pos, isMoving = true) // triggers Tier 1
        unstuck.tick(pos, isMoving = true) // finishes Tier 1

        // Cycle through Tier 2
        unstuck.tick(pos, isMoving = true) // triggers Tier 2
        unstuck.tick(pos, isMoving = true) // finishes Tier 2

        // Escalate to Tier 3
        var repathCalled = false
        var penalizedPos: BlockPos? = null
        val mockPathfinder = object : Pathfinder {
            override fun findPath(world: World, start: Vec3d, goal: Vec3d, allowedRange: Double): PathResult =
                PathResult(true, listOf(start, goal))
            override fun findPath(env: PathEnvironment, start: Vec3d, goal: Vec3d, allowedRange: Double): PathResult =
                PathResult(true, listOf(start, goal))
            override fun penalizeNode(pos: BlockPos, penalty: Float) {
                penalizedPos = pos
            }
            override fun clearPenalties() {}
        }

        unstuck.tick(
            pos,
            isMoving = true,
            pathfinder = mockPathfinder,
            onRepathRequested = { repathCalled = true }
        )

        assertTrue(repathCalled, "Tier 3 must invoke onRepathRequested")
        assertNotNull(penalizedPos, "Tier 3 must penalize current node in pathfinder")
    }
}
