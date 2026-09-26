package com.github.foragerhelper.movement

import com.github.foragerhelper.path.PathEnvironment
import com.github.foragerhelper.path.PathResult
import com.github.foragerhelper.path.Pathfinder
import com.github.foragerhelper.path.TestWorldGrid
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
        // Yaw = 0 faces South (+Z). Waypoint is at (-5, 64, 0). Right is West (-X).
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(-5.0, 64.0, 0.0), arrivalRadius = 0.5)
        controller.setDestination(target)

        val input = controller.tick(env, playerYaw = 0.0f)
        assertTrue(input.right, "Should strafe right when target is to the right (West)")
        assertFalse(input.forward, "Should not press forward")
        assertFalse(input.left)
        assertFalse(input.back)
    }

    @Test
    fun testDecoupledWASD_StrafeLeftFacingSouth() {
        // Yaw = 0 faces South (+Z). Waypoint is at (+5, 64, 0). Left is East (+X).
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(5.0, 64.0, 0.0), arrivalRadius = 0.5)
        controller.setDestination(target)

        val input = controller.tick(env, playerYaw = 0.0f)
        assertTrue(input.left, "Should strafe left when target is to the left (East)")
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
        // Yaw = 0 faces South (+Z). Target is at (-5, 64, +5) (South-West = Forward + Right).
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(-5.0, 64.0, 5.0), arrivalRadius = 0.5)
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

    // =========================================================================
    // 5. Walking Enhancements: High-Ground, Fallsafe, Parkour & Segmenting
    // =========================================================================

    @Test
    fun testHighGroundDoesNotPrematurelyFreezeInReachWhileNavigating() {
        // Player is elevated on a ledge at Y=68. Target block is below at Y=65.
        // 3D distance is sqrt(2^2 + 3^2) = 3.6m <= 4.5m reach.
        env.playerPosVec = Vec3d(0.0, 68.0, 0.0)
        env.playerEyePosVec = Vec3d(0.0, 69.62, 0.0)
        val pos = BlockPos(2, 65, 0)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)
        controller.setDestination(target)

        // Give controller active multi-node path down to the ground
        controller.setWaypointsForTest(listOf(
            Vec3d(0.0, 68.0, 0.0),
            Vec3d(1.0, 66.0, 0.0),
            Vec3d(2.0, 65.0, 0.0)
        ))

        val input = controller.tick(env, playerYaw = -90.0f)
        assertNotEquals(
            MovementState.IN_REACH, controller.state,
            "Must NOT freeze into IN_REACH while elevated on high ground with a path active"
        )
        assertFalse(input.sneak, "Must NOT hold sneak on high ground ledge during active pathing")
    }

    @Test
    fun testFallsafeDropProgressionAdvancesWaypoint() {
        // Player standing at ledge edge (0, 68, 0). Next waypoint is a safe 2-block drop to (0, 66, 0).
        env.playerPosVec = Vec3d(0.0, 68.0, 0.0)
        env.playerEyePosVec = Vec3d(0.0, 69.62, 0.0)
        val landingPos = BlockPos(0, 66, 0)
        // Solid ground beneath landing
        env.setTargetBlock(landingPos.down())

        val target = PositionTarget(Vec3d(0.0, 66.0, 10.0))
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(
            Vec3d(0.0, 66.0, 0.0), // Drop landing
            Vec3d(0.0, 66.0, 10.0)
        ))

        controller.tick(env, playerYaw = 0.0f)
        assertEquals(
            1, controller.currentWaypointIndex,
            "Fallsafe drop progression must advance past drop waypoint when horizontally aligned"
        )
    }

    @Test
    fun testParkourGapSprintAndJump() {
        // Player is approaching a gap to waypoint at (2.5, 64, 0).
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val gapPos = BlockPos(1, 64, 0)
        env.setAir(gapPos)
        env.setAir(gapPos.down()) // Air gap beneath feet

        val target = PositionTarget(Vec3d(2.5, 64.0, 0.0), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(Vec3d(2.5, 64.0, 0.0)))

        val input = controller.tick(env, playerYaw = -90.0f) // Facing East towards +X
        assertTrue(input.jump, "Must jump when approaching a parkour gap")
        assertTrue(input.sprint, "Must sprint across parkour gap jumps")
    }

    @Test
    fun testSegmentedRoutePlanningForLongDistance() {
        // Far destination 100 blocks away
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        val farGoal = Vec3d(100.0, 64.0, 0.0)
        val target = PositionTarget(farGoal, arrivalRadius = 1.0)
        controller.setDestination(target)

        controller.tick(env, playerYaw = -90.0f)

        assertTrue(controller.currentWaypoints.isNotEmpty(), "Waypoints must not be empty")
        val firstWp = controller.currentWaypoints.first()
        val distToFirst = env.playerPosVec.distanceTo(firstWp)
        assertTrue(
            distToFirst <= 36.0,
            "Long distance routes must be divided into local segments (dist <= 36m): actual=$distToFirst"
        )
    }

    @Test
    fun testHighGroundWithoutPreExistingPathDoesNotFreezeInReach() {
        // Player is elevated on a ledge at Y=68. Target is below at Y=65.
        // 3D distance is sqrt(2^2 + 3^2) = 3.6m <= 4.5m reach.
        // NO pre-existing waypoints: controller must compute path down, not enter IN_REACH.
        env.playerPosVec = Vec3d(0.0, 68.0, 0.0)
        env.playerEyePosVec = Vec3d(0.0, 69.62, 0.0)
        val pos = BlockPos(2, 65, 0)
        env.setTargetBlock(pos)
        val target = BlockTarget(pos)
        controller.setDestination(target)

        val input = controller.tick(env, playerYaw = -90.0f)
        assertNotEquals(
            MovementState.IN_REACH, controller.state,
            "Must NOT freeze into IN_REACH on high ground ledge even without pre-existing path"
        )
        assertFalse(input.sneak, "Must NOT engage sneak while stuck on high ground")
    }

    @Test
    fun testTerminalDropDoesNotPrematurelyAdvancePastEndOfWaypoints() {
        // Only one waypoint in this segment: a safe drop landing at Y=66.
        // Goal target is further ahead at (0, 66, 10).
        // Player is still standing on the ledge at Y=68.
        env.playerPosVec = Vec3d(0.0, 68.0, 0.0)
        env.playerEyePosVec = Vec3d(0.0, 69.62, 0.0)
        val landingPos = BlockPos(0, 66, 0)
        env.setTargetBlock(landingPos.down())

        val target = PositionTarget(Vec3d(0.0, 66.0, 10.0))
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(Vec3d(0.0, 66.0, 0.0))) // Terminal drop waypoint of segment

        controller.tick(env, playerYaw = 0.0f)
        assertEquals(
            0, controller.currentWaypointIndex,
            "Terminal drop waypoint must NOT advance past end while player is still elevated at Y=68"
        )

        // Once player falls and lands at Y=66:
        env.playerPosVec = Vec3d(0.0, 66.0, 0.0)
        env.playerEyePosVec = Vec3d(0.0, 67.62, 0.0)
        controller.tick(env, playerYaw = 0.0f)
        assertTrue(
            controller.currentWaypointIndex == 1 || controller.currentWaypoints.isNotEmpty(),
            "Terminal drop waypoint must complete or transition to next segment once player has landed"
        )
    }

    @Test
    fun testUnsafeCliffDropAbortsAndRepaths() {
        // Waypoint is a dangerous 6-block cliff drop to Y=62 (fall damage hazard)
        env.playerPosVec = Vec3d(0.0, 68.0, 0.0)
        val dangerousLanding = Vec3d(0.0, 62.0, 0.0) // dy = -6.0 < -3.5

        val target = PositionTarget(dangerousLanding)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(dangerousLanding))

        controller.tick(env, playerYaw = 0.0f)
        // Controller must detect unsafe drop and clear waypoints to force repath around
        assertTrue(
            controller.currentWaypoints.isEmpty(),
            "Controller must detect unsafe drop > 3.5m and clear waypoints to trigger repath"
        )
    }

    @Test
    fun testPathPartLookaheadCameraPitchBounded() {
        // Player at (0, 64, 0). Path has sequential waypoints.
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        env.playerEyePosVec = Vec3d(0.0, 65.62, 0.0)

        val target = PositionTarget(Vec3d(50.0, 80.0, 0.0)) // High distant target
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(
            Vec3d(1.0, 64.0, 0.0),
            Vec3d(2.0, 64.0, 0.0),
            Vec3d(3.0, 64.0, 0.0),
            Vec3d(4.0, 64.0, 0.0)
        ))

        controller.tick(env, playerYaw = -90.0f)

        // Pitch of path tangent must be within [-20.0, 20.0]
        val rotEngine = controller.rotationEngine as com.github.foragerhelper.rotation.DefaultRotationEngine
        val tangent = rotEngine.pathTangentVector ?: Vec3d.ZERO
        val (tangentYaw, tangentPitch) = rotEngine.calculateTangentAngles(tangent)
        assertTrue(
            tangentPitch in -20.0f..20.0f,
            "Path tangent pitch must be clamped within [-20, 20]: actual=$tangentPitch"
        )
    }

    // =========================================================================
    // 6. Arrival Deadzone, Overhead Recovery, and Corridor Line-of-Sight Tests
    // =========================================================================

    @Test
    fun testArrivalDeadzonePreventsRapidOscillation() {
        // Player is within 0.15m of waypoint/goal
        env.playerPosVec = Vec3d(5.0, 64.0, 5.0)
        val target = PositionTarget(Vec3d(5.15, 64.0, 5.0), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(Vec3d(5.15, 64.0, 5.0)))

        val input = controller.tick(env, playerYaw = 0.0f)
        assertFalse(input.forward, "Deadzone must suppress forward movement")
        assertFalse(input.back, "Deadzone must suppress backward movement")
        assertFalse(input.left, "Deadzone must suppress strafing")
        assertFalse(input.right, "Deadzone must suppress strafing")
    }

    @Test
    fun testNoMoveBackWhenCloseToWaypoint() {
        // Player is 0.4m past waypoint, facing away (forwardDot < -0.38)
        env.playerPosVec = Vec3d(5.4, 64.0, 5.0)
        val target = PositionTarget(Vec3d(5.0, 64.0, 5.0), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(Vec3d(5.0, 64.0, 5.0)))

        // Facing East (+X), waypoint is behind (-X)
        val input = controller.tick(env, playerYaw = -90.0f)
        assertFalse(input.back, "Must not move backward when within 0.8m of waypoint to prevent flip-flopping oscillation")
    }

    @Test
    fun testUnderLookingBlockCountsAsTraversedAndAdvances() {
        // Intermediate waypoint is overhead at Y=66. Player is at Y=64 directly under it.
        env.playerPosVec = Vec3d(2.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(10.0, 64.0, 0.0))
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(
            Vec3d(2.0, 66.0, 0.0), // Intermediate looking block directly overhead (dy = +2.0)
            Vec3d(10.0, 64.0, 0.0) // Next waypoint
        ))

        controller.tick(env, playerYaw = -90.0f)
        assertEquals(
            1, controller.currentWaypointIndex,
            "When player is directly under an intermediate looking block, it must count as traversed and advance"
        )
    }

    @Test
    fun testUnderDestinationBlockDoesNotJumpAndRepaths() {
        // Player failed parkour and is trapped directly under destination block at Y=67 (dy = +3.0)
        env.playerPosVec = Vec3d(5.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(5.2, 67.0, 0.0), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(Vec3d(5.2, 67.0, 0.0)))

        val input = controller.tick(env, playerYaw = -90.0f)
        assertFalse(
            input.jump,
            "Must NOT jump in place when trapped directly below destination block"
        )

        // RotationEngine should not focus on overhead ceiling
        val rotEngine = controller.rotationEngine as com.github.foragerhelper.rotation.DefaultRotationEngine
        assertNull(
            rotEngine.targetFocusPoint,
            "Must NOT stare straight up at overhead ceiling/destination block"
        )
    }

    @Test
    fun testCorridorCornerLineOfSightDoesNotLookIntoWall() {
        // 1x2 cave corridor along Z (X=0). At Z=4, corridor turns right to X=3.
        // Wall block at (1, 65, 3) blocks direct view to candidate waypoint at (3, 64, 4)
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        env.playerEyePosVec = Vec3d(0.0, 65.62, 0.0)

        // Wall block at (1, 65, 2)
        env.setSolid(BlockPos(1, 65, 2))

        val target = PositionTarget(Vec3d(3.0, 64.0, 4.0))
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(
            Vec3d(0.0, 64.0, 1.0),
            Vec3d(0.0, 64.0, 2.0), // Exit of corridor
            Vec3d(3.0, 64.0, 4.0)  // Around corner (obscured by wall at (1,65,2))
        ))

        controller.tick(env, playerYaw = 0.0f)

        val rotEngine = controller.rotationEngine as com.github.foragerhelper.rotation.DefaultRotationEngine
        // Focus point must not be the wall-blocked waypoint around corner
        assertNotEquals(
            Vec3d(3.0, 65.3, 4.0),
            rotEngine.targetFocusPoint,
            "When target waypoint is obscured by cave wall, controller must not stare into wall block"
        )
        // Camera tangent must point along corridor (+Z, X=0), not into wall (+X)
        val tangent = rotEngine.pathTangentVector
        assertNotNull(tangent)
        assertEquals(0.0, tangent!!.x, 0.01, "Tangent X should point straight along corridor, not into wall")

        // Also test when focus point itself is completely obscured (no visible waypoints)
        controller.setWaypointsForTest(listOf(Vec3d(3.0, 64.0, 4.0)))
        controller.tick(env, playerYaw = 0.0f)
        assertNull(
            rotEngine.targetFocusPoint,
            "When destination is completely obscured behind a wall, must not focus on wall"
        )
    }

    @Test
    fun testParkour2BlockGapApproachAndJump() {
        // 2-block gap: platform at x=0, gap at x=1 and x=2, landing at x=3.5
        env.playerPosVec = Vec3d(0.2, 64.0, 0.0)
        env.setAir(BlockPos(1, 64, 0))
        env.setAir(BlockPos(1, 63, 0))
        env.setAir(BlockPos(2, 64, 0))
        env.setAir(BlockPos(2, 63, 0))

        val target = PositionTarget(Vec3d(3.5, 64.0, 0.0), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(Vec3d(3.5, 64.0, 0.0)))

        val input = controller.tick(env, playerYaw = -90.0f) // Facing East towards +X
        assertTrue(input.sprint, "Must sprint across 2-block parkour gap")
        assertTrue(input.jump, "Must jump when approaching 2-block parkour gap")
    }

    @Test
    fun testStandingOnDestinationBlockCompletesWithoutOscillating() {
        // Player stands on block (5, 64, 5) at (5.1, 65.0, 5.1).
        // Destination target is Vec3d(5.5, 64.0, 5.5).
        env.playerPosVec = Vec3d(5.1, 65.0, 5.1)
        val target = PositionTarget(Vec3d(5.5, 64.0, 5.5), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(Vec3d(5.5, 64.0, 5.5)))

        val input = controller.tick(env, playerYaw = 0.0f)
        assertEquals(
            com.github.foragerhelper.movement.MovementState.COMPLETED,
            controller.state,
            "Standing on destination block must trigger COMPLETED state immediately"
        )
        assertFalse(input.forward, "Completed destination must not move forward")
        assertFalse(input.back, "Completed destination must not move backward")
        assertFalse(input.left, "Completed destination must not strafe")
        assertFalse(input.right, "Completed destination must not strafe")
    }

    @Test
    fun testTrappedUnderDestinationPreservesEscapePath() {
        // Player is at (5.0, 64.0, 0.0) under destination at (5.0, 67.0, 0.0).
        env.playerPosVec = Vec3d(5.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(5.0, 67.0, 0.0), arrivalRadius = 0.5)
        controller.setDestination(target)

        // Setup an escape path leading out and around
        val escapeWaypoints = listOf(
            Vec3d(5.0, 64.0, 2.0),
            Vec3d(7.0, 65.0, 2.0),
            Vec3d(5.0, 67.0, 0.0)
        )
        controller.setWaypointsForTest(escapeWaypoints)

        // Tick once
        val input1 = controller.tick(env, playerYaw = 0.0f)
        assertFalse(input1.jump, "Must not jump in place while under overhead ceiling/goal")

        // Player makes slight progress towards waypoint 0 (still under goal horizontally)
        env.playerPosVec = Vec3d(5.0, 64.0, 0.5)
        val input2 = controller.tick(env, playerYaw = 0.0f)

        // Crucial verification: waypoints must NOT have been wiped or reset!
        assertEquals(
            escapeWaypoints.size,
            controller.currentWaypoints.size,
            "Escape path must be preserved across ticks even while player is under destination block"
        )
        assertFalse(input2.jump, "Must not jump in place on subsequent ticks")
    }

    @Test
    fun testCorridorCornerWaypointsDoNotAdvancePrematurelyIntoWall() {
        // 1x2 cave corridor along Z (X=0). At Z=4, corridor turns right to X=3.
        // Corner wall at (1, 65, 3) blocks line of sight to W2=(3, 64, 4).
        env.playerPosVec = Vec3d(0.0, 64.0, 3.4) // 0.6m from W1 (inside the 0.65m waypoint radius!)
        env.playerEyePosVec = Vec3d(0.0, 65.62, 3.4)
        env.setSolid(BlockPos(1, 65, 3))

        val target = PositionTarget(Vec3d(3.0, 64.0, 4.0))
        controller.setDestination(target)
        val waypoints = listOf(
            Vec3d(0.0, 64.0, 1.0),
            Vec3d(0.0, 64.0, 4.0), // Exit of corridor (index 1)
            Vec3d(3.0, 64.0, 4.0)  // Around corner (index 2, obscured by wall)
        )
        controller.setWaypointsForTest(waypoints, index = 1)

        controller.tick(env, playerYaw = 0.0f)

        assertEquals(
            1,
            controller.currentWaypointIndex,
            "Must NOT advance to next waypoint around corner when obscured by wall; must walk straight out first"
        )

        val rotEngine = controller.rotationEngine as com.github.foragerhelper.rotation.DefaultRotationEngine
        val tangent = rotEngine.pathTangentVector
        assertNotNull(tangent)
        assertEquals(
            0.0, tangent!!.x, 0.01,
            "Path tangent must continue straight along corridor (+Z) and not point into corner wall (+X)"
        )
    }

    // =========================================================================
    // 7. Wall Clearance, Stairs Suppression, Sprint-Jumping, and Island Bridges
    // =========================================================================

    @Test
    fun testStairAndSlabStepUpSuppressesJump() {
        // Waypoint on a slab/stair at dy = +0.5m
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        for (z in 0..2) {
            env.setSolid(BlockPos(0, 63, z))
        }
        val target = PositionTarget(Vec3d(0.0, 64.5, 2.0), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(Vec3d(0.0, 64.5, 2.0)))
        env.setStepUpBlock(BlockPos(0, 64, 2))

        val input = controller.tick(env, playerYaw = 0.0f)
        assertFalse(
            input.jump,
            "Must NOT jump on stairs or slabs: dy=0.5m is stepped up automatically without jumping"
        )
    }

    @Test
    fun testStraightStretchFlatGroundSprintJumps() {
        // Flat straight stretch of waypoints totaling > 3.0m on flat ground
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        for (z in 0..6) {
            env.setSolid(BlockPos(0, 63, z))
        }
        env.movementSpeed = 0.1 // Normal vanilla speed
        val target = PositionTarget(Vec3d(0.0, 64.0, 6.0), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(
            Vec3d(0.0, 64.0, 2.0),
            Vec3d(0.0, 64.0, 4.0),
            Vec3d(0.0, 64.0, 6.0)
        ))

        val input = controller.tick(env, playerYaw = 0.0f) // Facing South (+Z)
        assertTrue(input.forward, "Must move forward along straight stretch")
        assertTrue(input.sprint, "Must sprint along straight stretch")
        assertTrue(input.jump, "Must sprint-jump on straight flat stretches of blocks")
    }

    @Test
    fun testHighSpeedSuppressesSprintJumping() {
        // Exact same flat straight stretch of waypoints totaling > 3.0m
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        for (z in 0..6) {
            env.setSolid(BlockPos(0, 63, z))
        }
        env.movementSpeed = 0.25 // High speed (running faster than jumping)
        val target = PositionTarget(Vec3d(0.0, 64.0, 6.0), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(
            Vec3d(0.0, 64.0, 2.0),
            Vec3d(0.0, 64.0, 4.0),
            Vec3d(0.0, 64.0, 6.0)
        ))

        val input = controller.tick(env, playerYaw = 0.0f) // Facing South (+Z)
        assertTrue(input.forward, "Must move forward along straight stretch")
        assertTrue(input.sprint, "Must sprint along straight stretch")
        assertFalse(
            input.jump,
            "Must NOT jump when speed is high enough that running is faster than jumping"
        )
    }

    @Test
    fun testSideWallObstacleSuppressesStrafeAndWalksStraight() {
        // Player is at (0, 64, 0), facing South (yaw=0, +Z is forward, -X is right).
        // Target is at (-2.0, 64.0, 2.0) (forward + right).
        // Wall block at (-1, 64, 0) right beside the player on the right.
        env.playerPosVec = Vec3d(0.0, 64.0, 0.0)
        env.setSolid(BlockPos(-1, 64, 0))

        val target = PositionTarget(Vec3d(-2.0, 64.0, 2.0), arrivalRadius = 0.5)
        controller.setDestination(target)
        controller.setWaypointsForTest(listOf(Vec3d(-2.0, 64.0, 2.0)))

        val input = controller.tick(env, playerYaw = 0.0f)
        assertFalse(
            input.right,
            "Must NOT strafe right into the wall block beside the player"
        )
        assertTrue(
            input.forward,
            "Must walk straight forward past the corner wall before turning right"
        )

        // Camera lookahead tangent must point forward (+Z), not into the right wall (-X)
        val rotEngine = controller.rotationEngine as com.github.foragerhelper.rotation.DefaultRotationEngine
        val tangent = rotEngine.pathTangentVector
        assertNotNull(tangent)
        assertTrue(
            tangent!!.z > 0.0,
            "Tangent must point forward along the corridor"
        )
    }

    @Test
    fun testOverheadDestinationParkourPreservesClimbingWaypoints() {
        // Destination is overhead at (5.0, 67.0, 0.0). Player is at (5.0, 64.0, 0.0).
        env.playerPosVec = Vec3d(5.0, 64.0, 0.0)
        val target = PositionTarget(Vec3d(5.0, 67.0, 0.0), arrivalRadius = 0.5)
        controller.setDestination(target)

        // Parkour climbing route
        val parkourWaypoints = listOf(
            Vec3d(5.0, 65.0, 1.0), // Step up onto first block
            Vec3d(5.0, 66.0, 2.0), // Parkour block
            Vec3d(5.0, 67.0, 0.0)  // Goal
        )
        controller.setWaypointsForTest(parkourWaypoints)

        val input = controller.tick(env, playerYaw = 0.0f)
        assertEquals(
            0,
            controller.currentWaypointIndex,
            "Must NOT skip overhead waypoints when destination is above player's head"
        )
        assertTrue(
            input.jump,
            "Must jump to step up/climb onto first parkour waypoint"
        )
    }

    @Test
    fun testIslandTerrainBridgeRoutePreferredOverVoid() {
        val grid = TestWorldGrid()
        // Island A: (0..5, 63, 0..5) -> stand height 64.0
        grid.addFlatPlatform(0, 5, 0, 5, 63)
        // Island B: (20..25, 63, 0..5) -> stand height 64.0
        grid.addFlatPlatform(20, 25, 0, 5, 63)
        // Bridge connecting them at Z=8: from x=0 to x=25, z=8
        grid.addFlatPlatform(0, 25, 8, 8, 63)

        // Goal on Island B
        val goal = Vec3d(22.5, 64.0, 2.5)
        val target = PositionTarget(goal, arrivalRadius = 0.5)

        env.playerPosVec = Vec3d(2.5, 64.0, 2.5)
        controller.pathEnvironmentProvider = { grid }
        controller.setDestination(target)

        controller.tick(env, playerYaw = 0.0f)

        assertTrue(
            controller.currentWaypoints.isNotEmpty(),
            "Must find route across the bridge between islands"
        )
        // Crucial check: none of the waypoints may be in the void (x in 6..19 with z in 0..5)
        for (wp in controller.currentWaypoints) {
            val isOverVoid = wp.x in 6.0..19.0 && wp.z in 0.0..5.0
            assertFalse(
                isOverVoid,
                "Waypoint $wp must NOT cross directly over void chasm; must use bridge"
            )
        }
    }
}
