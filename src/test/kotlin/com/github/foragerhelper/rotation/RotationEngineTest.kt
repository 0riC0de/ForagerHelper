package com.github.foragerhelper.rotation

import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotationEngineTest {

    private val epsilon = 1e-4f
    private val doubleEpsilon = 1e-6

    // =========================================================================
    // Tier 1: Unit & Numerical Invariants
    // =========================================================================

    @Test
    fun testSpringStepResponseMonotonicConvergence() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)

        val target = 45.0f
        var prevAngle = spring.currentAngle
        val dt = 1.0f / 144.0f // 144 Hz

        // Step through 100 frames; angle should monotonically increase toward target
        for (i in 1..100) {
            spring.update(target, dt)
            assertTrue(
                spring.currentAngle >= prevAngle,
                "Spring must increase monotonically toward target: frame $i, current=${spring.currentAngle}, prev=$prevAngle"
            )
            assertTrue(
                spring.currentAngle <= target + 0.01f,
                "Critically damped spring must not overshoot: frame $i, current=${spring.currentAngle}, target=$target"
            )
            prevAngle = spring.currentAngle
        }
        // Should be near target after 100 frames (~0.7s)
        assertTrue(abs(spring.currentAngle - target) < 0.1f)
    }

    @Test
    fun testSpringSettlingTime() {
        val omega = 18.0f
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = omega)
        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)

        // Linear regime without Dawson limiter: target <= maxVelocity / omega = 720 / 18 = 40.0 deg
        val target = 35.0f
        val dt = 0.01f
        var time = 0.0f

        // Theoretical settling time to < 2% of initial error: t_settle ~ 3.9 / omega ~ 0.216s
        while (time < 0.35f) {
            spring.update(target, dt)
            time += dt
        }

        val remainingError = abs(target - spring.currentAngle)
        val errorPercent = remainingError / target
        assertTrue(
            errorPercent < 0.02f,
            "After 0.35s, remaining error should be < 2% (actual: ${errorPercent * 100}%, error=$remainingError)"
        )
    }

    @Test
    fun testSpringZeroDeltaTimePreservesState() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 25.0f, initialVelocity = 10.0f)

        val delta = spring.update(targetAngle = 50.0f, deltaTimeSeconds = 0.0f)
        assertEquals(0.0f, delta, epsilon)
        assertEquals(25.0f, spring.currentAngle, epsilon)
        assertEquals(10.0f, spring.velocity, epsilon)

        val negativeDelta = spring.update(targetAngle = 50.0f, deltaTimeSeconds = -0.01f)
        assertEquals(0.0f, negativeDelta, epsilon)
        assertEquals(25.0f, spring.currentAngle, epsilon)
    }

    @Test
    fun testSpringRestDeadband() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 10.0f, initialVelocity = 0.005f)

        // Target very close (within ANGLE_REST_THRESHOLD = 0.001) and velocity within VELOCITY_REST_THRESHOLD = 0.01
        val target = 10.0005f
        val delta = spring.update(target, 0.01f)

        assertEquals(target, spring.currentAngle, epsilon)
        assertEquals(0.0f, spring.velocity, epsilon)
        assertTrue(spring.isAtRest)
        assertTrue(abs(delta) < 0.001f)
    }

    @Test
    fun testSpringSymmetry() {
        val springPos = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        val springNeg = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)

        springPos.reset(0.0f, 0.0f)
        springNeg.reset(0.0f, 0.0f)

        val dt = 1.0f / 60.0f
        for (i in 1..30) {
            val dPos = springPos.update(+60.0f, dt)
            val dNeg = springNeg.update(-60.0f, dt)

            assertEquals(dPos, -dNeg, 1e-3f, "Step $i deltas should be symmetric: dPos=$dPos, dNeg=$dNeg")
            assertEquals(springPos.currentAngle, -springNeg.currentAngle, 1e-3f)
            assertEquals(springPos.velocity, -springNeg.velocity, 1e-2f)
        }
    }

    @Test
    fun testGCDStepFormulasMatchVanillaConstants() {
        // Vanilla constant tests across sensitivities
        val testSensitivities = doubleArrayOf(0.0, 0.2, 0.5, 0.8, 1.0)

        for (s in testSensitivities) {
            val f = s * 0.6000000238418579 + 0.20000000298023224
            val expectedStep = (f * f * f) * 8.0 * 0.15
            val actualStep = SensitivityGCD.computeStep(s)

            assertEquals(expectedStep, actualStep, doubleEpsilon, "Step mismatch at sensitivity $s")
            val expectedMultiplier = (f * f * f) * 8.0
            val actualMultiplier = SensitivityGCD.computeGcdMultiplier(s)
            assertEquals(expectedMultiplier, actualMultiplier, doubleEpsilon)
        }

        // Default sensitivity s = 0.5: step is approx 0.15 deg
        val defaultStep = SensitivityGCD.computeStep(0.5)
        assertEquals(0.1500000134110452, defaultStep, 1e-7)
    }

    @Test
    fun testGCDRemainderAccumulationZeroDrift() {
        val sensitivityGcd = SensitivityGCD()
        val sensitivity = 0.5
        val step = SensitivityGCD.computeStep(sensitivity) // ~0.15 deg

        // Desired delta is 0.04 degrees per frame (less than 1 mouse count ~ 0.15)
        val smallDelta = 0.04
        val frames = 150
        var totalApplied = 0.0
        val totalDesired = smallDelta * frames // 6.0 degrees

        for (i in 1..frames) {
            val result = sensitivityGcd.quantize(
                desiredDeltaYaw = smallDelta,
                desiredDeltaPitch = 0.0,
                sensitivity = sensitivity
            )
            totalApplied += result.appliedDeltaYaw
            // Sub-step remainder must always be bounded by [-0.5 * step, 0.5 * step]
            assertTrue(
                abs(result.yawRemainder) <= 0.5 * step + doubleEpsilon,
                "Remainder must remain strictly bounded: actual ${result.yawRemainder}, limit ${0.5 * step}"
            )
        }

        // Total cumulative error must be strictly bounded by half a mouse count
        val cumulativeError = abs(totalApplied - totalDesired)
        assertTrue(
            cumulativeError <= 0.5 * step + doubleEpsilon,
            "Total drift across $frames frames must be <= 0.5 step ($cumulativeError vs ${0.5 * step})"
        )
    }

    @Test
    fun testHermiteSmoothstepMathematicalProperties() {
        // Cubic Hermite smoothstep w(u) = 3u^2 - 2u^3
        fun hermite(u: Float): Float = u * u * (3.0f - 2.0f * u)
        fun hermiteDerivative(u: Float): Float = 6.0f * u * (1.0f - u)

        assertEquals(0.0f, hermite(0.0f), epsilon)
        assertEquals(1.0f, hermite(1.0f), epsilon)
        assertEquals(0.5f, hermite(0.5f), epsilon)

        assertEquals(0.0f, hermiteDerivative(0.0f), epsilon, "C1 boundary derivative at 0 must be 0")
        assertEquals(0.0f, hermiteDerivative(1.0f), epsilon, "C1 boundary derivative at 1 must be 0")

        // Strictly monotonic check
        var prev = 0.0f
        for (i in 1..100) {
            val u = i / 100.0f
            val value = hermite(u)
            assertTrue(value >= prev, "Smoothstep must be strictly non-decreasing: u=$u, val=$value, prev=$prev")
            prev = value
        }
    }

    @Test
    fun testAngleCalculationCardinalDirections() {
        val engine = DefaultRotationEngine()
        val eye = Vec3d(0.0, 1.62, 0.0)

        // Looking South (+Z): dx=0, dz=10 -> yaw 0
        val (yawSouth, pitchSouth) = engine.calculateTargetAngles(eye, Vec3d(0.0, 1.62, 10.0))
        assertEquals(0.0f, yawSouth, 0.01f)
        assertEquals(0.0f, pitchSouth, 0.01f)

        // Looking North (-Z): dx=0, dz=-10 -> yaw 180 or -180
        val (yawNorth, pitchNorth) = engine.calculateTargetAngles(eye, Vec3d(0.0, 1.62, -10.0))
        assertEquals(180.0f, abs(yawNorth), 0.01f)
        assertEquals(0.0f, pitchNorth, 0.01f)

        // Looking West (-X): dx=-10, dz=0 -> yaw 90
        val (yawWest, pitchWest) = engine.calculateTargetAngles(eye, Vec3d(-10.0, 1.62, 0.0))
        assertEquals(90.0f, yawWest, 0.01f)
        assertEquals(0.0f, pitchWest, 0.01f)

        // Looking East (+X): dx=+10, dz=0 -> yaw -90
        val (yawEast, pitchEast) = engine.calculateTargetAngles(eye, Vec3d(10.0, 1.62, 0.0))
        assertEquals(-90.0f, yawEast, 0.01f)
        assertEquals(0.0f, pitchEast, 0.01f)

        // Looking straight down: dy = -10 -> pitch +89.9 (clamped)
        val (yawDown, pitchDown) = engine.calculateTargetAngles(eye, Vec3d(0.0, -8.38, 0.0))
        assertEquals(89.9f, pitchDown, 0.01f)

        // Looking straight up: dy = +10 -> pitch -89.9 (clamped)
        val (yawUp, pitchUp) = engine.calculateTargetAngles(eye, Vec3d(0.0, 11.62, 0.0))
        assertEquals(-89.9f, pitchUp, 0.01f)
    }

    // =========================================================================
    // Tier 2: Boundary & Corner Cases
    // =========================================================================

    @Test
    fun testYawWrapBoundaryCrossingShortestPath() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        // Start near +180 boundary
        spring.reset(initialAngle = 179.0f, initialVelocity = 0.0f)

        // Target across boundary at -179.0 degrees. Shortest path is +2.0 degrees, NOT -358.0 degrees!
        val target = -179.0f
        val delta = spring.update(target, deltaTimeSeconds = 1.0f / 60.0f)

        // First step delta must be POSITIVE (rotating across +180 to -179)
        assertTrue(
            delta > 0.0f,
            "Shortest path must rotate positive across the 180 boundary: delta=$delta"
        )

        // Step forward until settled
        for (i in 1..60) {
            spring.update(target, 1.0f / 60.0f)
        }

        // Settled angle must be near -179
        val diff = abs(MathHelper.wrapDegrees(spring.currentAngle - target))
        assertTrue(diff < 0.1f, "Must settle at -179.0: actual=${spring.currentAngle}")
    }

    @Test
    fun testMultiRevolutionAnglesWrapCleanly() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)

        // Target 725.0 degrees = 2 * 360 + 5.0 -> wrapped is +5.0 degrees
        val target = 725.0f
        for (i in 1..60) {
            spring.update(target, 1.0f / 60.0f)
        }

        val diff = abs(MathHelper.wrapDegrees(spring.currentAngle - 5.0f))
        assertTrue(diff < 0.1f, "725.0 degrees must resolve cleanly to 5.0 degrees: actual=${spring.currentAngle}")
    }

    @Test
    fun testSevereLagSpikeStability() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f, maxVelocity = 720.0f)
        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)

        // Simulate massive 2-second lag spike (e.g. world save or alt-tab)
        val delta = spring.update(targetAngle = 90.0f, deltaTimeSeconds = 2.0f)

        assertFalse(spring.currentAngle.isNaN(), "Current angle must not be NaN after lag spike")
        assertFalse(spring.currentAngle.isInfinite(), "Current angle must not be Infinite after lag spike")
        assertFalse(spring.velocity.isNaN(), "Velocity must not be NaN after lag spike")
        assertFalse(spring.velocity.isInfinite(), "Velocity must not be Infinite after lag spike")

        // Max velocity clamp ensures velocity does not exceed 720 deg/s
        assertTrue(abs(spring.velocity) <= 720.0f + 1.0f)
        // Must move toward 90 without overshooting
        assertTrue(spring.currentAngle in 0.0f..90.1f)
    }

    @Test
    fun testPitchBoundaryClampingAndAntiWindup() {
        val spring = AngularSpring1D(mode = AngleMode.CLAMPED, omega = 18.0f, minAngle = -89.9f, maxAngle = 89.9f)
        spring.reset(initialAngle = 80.0f, initialVelocity = 0.0f)

        // Target far below nadir limit (+120 degrees)
        for (i in 1..60) {
            spring.update(120.0f, 1.0f / 60.0f)
        }

        assertEquals(89.9f, spring.currentAngle, epsilon, "Pitch must be clamped to maxAngle 89.9")
        assertEquals(0.0f, spring.velocity, 0.1f, "Velocity at boundary must be zeroed (anti-windup)")

        // Immediate reverse target to 0.0 degrees: must begin moving up on frame 1 without delay
        val reverseDelta = spring.update(0.0f, 1.0f / 60.0f)
        assertTrue(
            reverseDelta < 0.0f,
            "Immediate reversal must produce negative delta immediately: reverseDelta=$reverseDelta"
        )
    }

    @Test
    fun testSensitivityGCDPitchAntiWindup() {
        val gcd = SensitivityGCD()
        val sens = 0.5

        // Player pitch pegged at 90.0 degrees looking straight down
        val resultDown = gcd.quantize(
            desiredDeltaYaw = 0.0,
            desiredDeltaPitch = 5.0,
            sensitivity = sens,
            currentPitch = 90.0f
        )
        // Must produce 0 counts and zero remainder because we cannot look further down
        assertEquals(0, resultDown.countsPitch)
        assertEquals(0.0, gcd.pitchRemainder, doubleEpsilon)

        // Next frame, immediate upward movement requested (-1.5 degrees)
        val resultUp = gcd.quantize(
            desiredDeltaYaw = 0.0,
            desiredDeltaPitch = -1.5,
            sensitivity = sens,
            currentPitch = 90.0f
        )
        // Must immediately produce negative counts (upward rotation) on frame 1
        assertTrue(resultUp.countsPitch < 0, "Pitch must respond upward immediately: ${resultUp.countsPitch}")
    }

    @Test
    fun testDegenerateSensitivitySanitization() {
        val nanSens = SensitivityGCD.sanitizeSensitivity(Double.NaN)
        assertEquals(0.5, nanSens, doubleEpsilon)

        val negSens = SensitivityGCD.sanitizeSensitivity(-0.4)
        assertEquals(0.5, negSens, doubleEpsilon)

        val hugeSens = SensitivityGCD.sanitizeSensitivity(5.0)
        assertEquals(2.0, hugeSens, doubleEpsilon)
    }

    @Test
    fun testPathTangentElevationClamping() {
        val engine = DefaultRotationEngine()
        // Steep upward tangent vector: y = 50.0m, horizontal = ~0.14m
        val steepTangent = Vec3d(0.1, 50.0, 0.1)
        val (yaw, pitch) = engine.calculateTangentAngles(steepTangent)

        assertTrue(
            pitch in -25.0f..25.0f,
            "Path tangent pitch must be clamped to [-25.0, 25.0]: actual=$pitch"
        )
    }

    // =========================================================================
    // Tier 3: Pairwise Combinations & Dynamics
    // =========================================================================

    @Test
    fun testMidFlightTargetSwitchVelocityContinuity() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(0.0f, 0.0f)

        val dt = 1.0f / 144.0f
        // Accelerate toward target 1 (45 deg) for 10 frames
        for (i in 1..10) {
            spring.update(45.0f, dt)
        }

        val velBeforeSwitch = spring.velocity
        assertTrue(velBeforeSwitch > 50.0f, "Spring should have built positive velocity: $velBeforeSwitch")

        // Mid-flight target switch to 90 degrees
        spring.update(90.0f, dt)
        val velAfterSwitch = spring.velocity

        // In C1 continuous system, velocity must NOT jump to zero or experience discontinuous spike
        assertTrue(
            abs(velAfterSwitch - velBeforeSwitch) < 80.0f,
            "Velocity must be continuous across retargeting: before=$velBeforeSwitch, after=$velAfterSwitch"
        )
        assertTrue(velAfterSwitch > 0.0f, "Velocity must remain positive, continuing forward momentum")
    }

    @Test
    fun testImmediateTargetReversalDeceleration() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(0.0f, 0.0f)

        val dt = 1.0f / 144.0f
        // Move right toward +60 deg
        for (i in 1..15) {
            spring.update(+60.0f, dt)
        }
        val highVel = spring.velocity
        assertTrue(highVel > 100.0f)

        // Instantly switch target to -60 deg
        // Velocity should smoothly decelerate, pass through zero, and become negative
        var reachedZero = false
        var becameNegative = false
        for (i in 1..60) {
            spring.update(-60.0f, dt)
            if (!reachedZero && spring.velocity <= 0.0f) {
                reachedZero = true
            }
            if (spring.velocity < -10.0f) {
                becameNegative = true
            }
        }

        assertTrue(reachedZero, "Spring velocity must smoothly decelerate through zero")
        assertTrue(becameNegative, "Spring velocity must reverse to negative toward -60 target")
    }

    @Test
    fun testVariableFrameRateEquivalence() {
        // Run identical target turn at 60Hz, 144Hz, and 240Hz for 0.7s total time
        val totalTime = 0.7f
        val target = 90.0f

        fun runSimulation(fps: Int): Float {
            val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
            spring.reset(0.0f, 0.0f)
            val dt = 1.0f / fps
            val steps = (totalTime * fps).toInt()
            for (i in 1..steps) {
                spring.update(target, dt)
            }
            return spring.currentAngle
        }

        val result60 = runSimulation(60)
        val result144 = runSimulation(144)
        val result240 = runSimulation(240)

        // Exact analytic solution guarantees that all refresh rates converge to target
        assertTrue(abs(result60 - target) < 0.2f, "60Hz must settle near target: $result60")
        assertTrue(abs(result144 - target) < 0.2f, "144Hz must settle near target: $result144")
        assertTrue(abs(result240 - target) < 0.2f, "240Hz must settle near target: $result240")

        // Discrepancy between refresh rates must be tiny (< 0.1 deg)
        assertTrue(abs(result60 - result144) < 0.1f)
        assertTrue(abs(result144 - result240) < 0.1f)
    }

    @Test
    fun testSpyglassSensitivityScaling() {
        val normalGcd = SensitivityGCD.computeStep(0.5, isSpyglass = false)
        val spyglassGcd = SensitivityGCD.computeStep(0.5, isSpyglass = true)

        assertEquals(normalGcd / 8.0, spyglassGcd, doubleEpsilon, "Spyglass step must be exactly 1/8 normal step")

        val gcd = SensitivityGCD()
        val result = gcd.quantize(
            desiredDeltaYaw = spyglassGcd * 2.0,
            desiredDeltaPitch = 0.0,
            sensitivity = 0.5,
            isSpyglass = true
        )
        assertEquals(2, result.countsYaw)
        assertEquals(spyglassGcd, result.step, doubleEpsilon)
    }

    @Test
    fun testHermiteFocusBlendingTransition() {
        val engine = DefaultRotationEngine(reachDistance = 4.5, blendWindowDistance = 2.5)
        // blendStart = 7.0m, reachDistance = 4.5m
        engine.setPathTangent(Vec3d(0.0, 0.0, 1.0)) // Tangent yaw = 0.0 (South)
        engine.setTarget(Vec3d(5.0, 1.62, 0.0)) // Focus point to East (yaw = -90.0)

        // Case 1: Distance = 10.0m (> 7.0m blendStart) -> State PATH_TANGENT, Yaw = 0.0
        engine.setSimulatedState(yaw = 0.0f, pitch = 0.0f, eyePos = Vec3d(5.0, 1.62, -10.0))
        engine.onRenderFrame(0.01f, 1.0f)
        assertEquals(RotationState.PATH_TANGENT, engine.state)
        assertEquals(0.0f, engine.targetYaw, 0.1f)

        // Case 2: Distance = 5.75m (Midway in blending zone: 7.0m to 4.5m, u = 0.5, w = 0.5)
        // Focus is at (5.0, 1.62, 0.0). Place player at (5.0, 1.62, -5.75).
        engine.setSimulatedState(yaw = 0.0f, pitch = 0.0f, eyePos = Vec3d(5.0, 1.62, -5.75))
        engine.onRenderFrame(0.01f, 1.0f)
        assertEquals(RotationState.BLENDING, engine.state)
        // Tangent yaw = 0.0, focus yaw = 0.0 (straight ahead). Let's test with off-angle focus:
        engine.setTarget(Vec3d(10.75, 1.62, -5.75)) // Focus is exactly East (dx=5.0m -> distance=5.0m, yaw=-90)
        // Distance is 5.0m -> in blending zone [4.5, 7.0].
        engine.onRenderFrame(0.01f, 1.0f)
        assertEquals(RotationState.BLENDING, engine.state)
        // Blended angle should be between tangent (0.0) and focus (-90.0)
        assertTrue(
            engine.targetYaw in -89.0f..-1.0f,
            "Blended target yaw must be between 0 and -90: actual=${engine.targetYaw}"
        )

        // Case 3: Distance = 3.0m (<= 4.5m reachDistance) -> State TARGET_FOCUS, Yaw = -90.0
        engine.setSimulatedState(yaw = 0.0f, pitch = 0.0f, eyePos = Vec3d(7.75, 1.62, -5.75)) // Distance to focus is 3.0m
        engine.onRenderFrame(0.01f, 1.0f)
        assertEquals(RotationState.TARGET_FOCUS, engine.state)
        assertEquals(-90.0f, engine.targetYaw, 0.1f)
    }

    // =========================================================================
    // Tier 4: Workload Scenarios & Anti-Cheat Heuristics
    // =========================================================================

    @Test
    fun testGrimACAndPolarGCDComplianceOver1000Frames() {
        val sensitivity = 0.5
        val step = SensitivityGCD.computeStep(sensitivity)
        val gcd = SensitivityGCD()

        // Simulate 1000 rendered frames at 144Hz with varying continuous spring speeds
        val simulatedDeltas = DoubleArray(1000) { i ->
            val t = i * (1.0 / 144.0)
            kotlin.math.sin(t * 5.0) * 0.45 // Varies between -0.45 and +0.45 deg/frame
        }

        var totalNonZeroSteps = 0
        for (i in simulatedDeltas.indices) {
            val desired = simulatedDeltas[i]
            val result = gcd.quantize(
                desiredDeltaYaw = desired,
                desiredDeltaPitch = desired * 0.5,
                sensitivity = sensitivity
            )

            if (result.countsYaw != 0) {
                totalNonZeroSteps++
                val applied = result.appliedDeltaYaw.toDouble()
                // Verify applied delta is an exact integer multiple of step
                val exactRatio = applied / step
                val nearestInt = kotlin.math.round(exactRatio)
                val discrepancy = abs(exactRatio - nearestInt)
                assertTrue(
                    discrepancy < 1e-5,
                    "Frame $i violates GrimAC/Polar GCD quantization: applied=$applied, step=$step, ratio=$exactRatio, disc=$discrepancy"
                )
            }
        }

        assertTrue(totalNonZeroSteps > 500, "Should have significant movement pulses across 1000 frames")
    }

    @Test
    fun testHighRefreshRate240HzContinuousTracking() {
        val engine = DefaultRotationEngine()
        val dt = 1.0f / 240.0f // 240 Hz
        engine.setSimulatedState(yaw = 0.0f, pitch = 0.0f, sensitivity = 0.5)

        // Track a moving S-curve target for 1000 frames (~4.16 seconds)
        var totalYawTraversed = 0.0f
        var lastYaw = 0.0f

        val recordedRotations = mutableListOf<QuantizedRotation>()
        engine.onQuantizedMovement = { recordedRotations.add(it) }

        for (frame in 1..1000) {
            val t = frame * dt
            // Dynamic path tangent oscillating horizontally
            val tangentX = kotlin.math.sin(t * 2.0)
            val tangentZ = kotlin.math.cos(t * 2.0)
            engine.setPathTangent(Vec3d(tangentX, 0.0, tangentZ))

            engine.onRenderFrame(dt, 1.0f)
            totalYawTraversed += abs(MathHelper.wrapDegrees(engine.currentYaw - lastYaw))
            lastYaw = engine.currentYaw
        }

        assertTrue(totalYawTraversed > 180.0f, "Should have smoothly traversed curves: traversed=$totalYawTraversed")
        assertEquals(1000, recordedRotations.size)

        // Verify zero NaN/Inf throughout 1000 frames
        for (rot in recordedRotations) {
            assertFalse(rot.appliedDeltaYaw.isNaN())
            assertFalse(rot.appliedDeltaPitch.isNaN())
        }
    }

    @Test
    fun testSnapModeLifecycle() {
        val engine = DefaultRotationEngine()
        engine.setSimulatedState(yaw = 10.0f, pitch = 15.0f)

        // Snap to manual angles
        engine.setTargetAngles(yaw = 125.0f, pitch = -40.0f, snap = true)
        assertEquals(125.0f, engine.currentYaw, epsilon)
        assertEquals(-40.0f, engine.currentPitch, epsilon)
        assertEquals(125.0f, engine.targetYaw, epsilon)
        assertEquals(-40.0f, engine.targetPitch, epsilon)
        assertTrue(engine.springSmoother.isAtRest)
        assertEquals(0.0, engine.sensitivityGcd.yawRemainder, doubleEpsilon)
        assertEquals(0.0, engine.sensitivityGcd.pitchRemainder, doubleEpsilon)

        // Snap to target focus point
        val targetPoint = Vec3d(10.0, 1.62, 0.0) // East from eye (0, 1.62, 0) -> yaw -90, pitch 0
        engine.setTarget(targetPoint, snap = true)
        assertEquals(-90.0f, engine.currentYaw, epsilon)
        assertEquals(0.0f, engine.currentPitch, epsilon)
        assertTrue(engine.springSmoother.isAtRest)
    }

    @Test
    fun testResetClearsAllState() {
        val engine = DefaultRotationEngine()
        engine.setSimulatedState(yaw = 45.0f, pitch = 20.0f)
        engine.setTarget(Vec3d(10.0, 10.0, 10.0))
        engine.setPathTangent(Vec3d(1.0, 0.0, 0.0))

        engine.reset()

        assertEquals(RotationState.IDLE, engine.state)
        assertTrue(engine.targetFocusPoint == null)
        assertTrue(engine.pathTangentVector == null)
        assertTrue(engine.springSmoother.isAtRest)
        assertEquals(0.0, engine.sensitivityGcd.yawRemainder, doubleEpsilon)
        assertEquals(0.0, engine.sensitivityGcd.pitchRemainder, doubleEpsilon)
    }
}
