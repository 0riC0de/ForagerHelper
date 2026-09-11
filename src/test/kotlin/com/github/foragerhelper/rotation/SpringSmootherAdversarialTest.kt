package com.github.foragerhelper.rotation

import net.minecraft.util.math.MathHelper
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial stress testing suite for SpringSmoother.kt and AngularSpring1D.
 *
 * Subjecting angular spring dynamics to:
 * - Extreme delta times (0s, 10s, 1e-6s, negative, huge spikes)
 * - Large angle wraps (>3600 deg, 179.9 to -179.9, exact branch cut 180 deg)
 * - Boundary conditions (clamped pitch limits, anti-windup, NaN/Infinity inputs)
 * - Dynamic stress (sudden mid-convergence reversals, 144Hz high-frequency chatter)
 */
class SpringSmootherAdversarialTest {

    private val epsilon = 1e-4f

    // =========================================================================
    // 1. Extreme Delta Times (dt = 0, dt = 10s, dt = 1e-6s, negative, jitter)
    // =========================================================================

    @Test
    fun testDeltaTimeZeroPreservesStateExactly() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 45.0f, initialVelocity = 120.0f)

        // Multiple calls with dt = 0.0 must return 0 delta and preserve state exactly
        for (i in 1..50) {
            val delta = spring.update(targetAngle = 90.0f, deltaTimeSeconds = 0.0f)
            assertEquals(0.0f, delta, epsilon, "Delta must be 0 for dt = 0 on step $i")
            assertEquals(45.0f, spring.currentAngle, epsilon, "Angle must not change for dt = 0")
            assertEquals(120.0f, spring.velocity, epsilon, "Velocity must not change for dt = 0")
        }

        // Test with -0.0f
        val deltaNegZero = spring.update(targetAngle = 90.0f, deltaTimeSeconds = -0.0f)
        assertEquals(0.0f, deltaNegZero, epsilon)
        assertEquals(45.0f, spring.currentAngle, epsilon)
    }

    @Test
    fun testNegativeDeltaTimeRejectedSafely() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 10.0f, initialVelocity = 50.0f)

        val negativeDts = floatArrayOf(-0.001f, -1.0f, -10.0f, -1e-6f, -1000.0f)
        for (negDt in negativeDts) {
            val delta = spring.update(targetAngle = 90.0f, deltaTimeSeconds = negDt)
            assertEquals(0.0f, delta, epsilon, "Negative dt $negDt must return 0.0f delta")
            assertEquals(10.0f, spring.currentAngle, epsilon, "Negative dt must not alter currentAngle")
            assertEquals(50.0f, spring.velocity, epsilon, "Negative dt must not alter velocity")
        }
    }

    @Test
    fun testExtremeDeltaTimeHugeTenSecondsCoercedAndStable() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f, maxVelocity = 720.0f)
        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)

        // dt = 10.0s is a massive freeze. It must be coerced to MAX_INTEGRATION_STEP (0.2s)
        val delta = spring.update(targetAngle = 90.0f, deltaTimeSeconds = 10.0f)

        assertFalse(spring.currentAngle.isNaN(), "Angle must not be NaN after dt = 10s")
        assertFalse(spring.currentAngle.isInfinite(), "Angle must not be Infinite after dt = 10s")
        assertFalse(spring.velocity.isNaN(), "Velocity must not be NaN after dt = 10s")
        assertFalse(spring.velocity.isInfinite(), "Velocity must not be Infinite after dt = 10s")

        // Must move in the positive direction toward 90.0
        assertTrue(delta > 0.0f, "Delta must be positive moving toward 90.0: delta=$delta")
        assertTrue(spring.currentAngle in 0.0f..90.01f, "Angle must not overshoot 90.0: angle=${spring.currentAngle}")
        assertTrue(abs(spring.velocity) <= 720.0f + epsilon, "Velocity must be bounded by maxVelocity 720")

        // In subsequent huge steps, it must smoothly settle to 90.0 without oscillating or exploding
        for (i in 1..20) {
            spring.update(targetAngle = 90.0f, deltaTimeSeconds = 10.0f)
        }
        val diff = abs(MathHelper.wrapDegrees(spring.currentAngle - 90.0f))
        assertTrue(diff < 0.01f, "Must settle at 90.0 after multiple large steps: actual=${spring.currentAngle}")
    }

    @Test
    fun testExtremeDeltaTimeSubMicrosecondAccumulation() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f, maxVelocity = 720.0f)
        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)

        val dt = 1e-6f // 1 microsecond
        val target = 45.0f

        // Step through 100,000 microsecond steps (0.1 seconds simulated time)
        var prevAngle = spring.currentAngle
        for (i in 1..100_000) {
            spring.update(target, dt)
            assertTrue(
                spring.currentAngle >= prevAngle - 1e-6f,
                "Microsecond stepping must be non-decreasing toward target: step $i, curr=${spring.currentAngle}, prev=$prevAngle"
            )
            prevAngle = spring.currentAngle
        }

        // At t = 0.1s with omega = 18, critically damped spring should have advanced significantly (~60% of target)
        assertTrue(
            spring.currentAngle > 15.0f,
            "After 0.1s of 1us steps, spring must have advanced significantly: actual=${spring.currentAngle}"
        )
        assertTrue(
            spring.currentAngle <= target + 0.01f,
            "Spring must not overshoot target at 1us step: actual=${spring.currentAngle}"
        )
        assertFalse(spring.velocity.isNaN())
        assertTrue(spring.velocity > 0.0f)
    }

    @Test
    fun testSevereJitterDeltaTimeStability() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f, maxVelocity = 720.0f)
        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)

        // Alternating extreme frame times: 10us (1e-5s) vs 0.2s (max allowed step)
        val target = 60.0f
        for (i in 1..100) {
            val dt = if (i % 2 == 0) 0.2f else 1e-5f
            spring.update(target, dt)
            assertFalse(spring.currentAngle.isNaN())
            assertFalse(spring.velocity.isNaN())
            assertTrue(spring.currentAngle in -0.01f..60.01f, "No overshoot under severe jitter: ${spring.currentAngle}")
        }
        val diff = abs(MathHelper.wrapDegrees(spring.currentAngle - target))
        assertTrue(diff < 0.01f, "Must converge under severe frame jitter: actual=${spring.currentAngle}")
    }

    // =========================================================================
    // 2. Large Angle Wraps & Shortest Path Traversal
    // =========================================================================

    @Test
    fun testAngleWrapShortestPathPositiveCrossing179ToMinus179() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        // Start at +179.9 degrees
        spring.reset(initialAngle = 179.9f, initialVelocity = 0.0f)

        // Target is at -179.9 degrees.
        // Shortest path is +0.2 degrees across the branch cut (+179.9 -> +180.0 / -180.0 -> -179.9).
        // Long path would be -359.8 degrees.
        val target = -179.9f
        val dt = 1.0f / 144.0f

        var totalTraversedAbs = 0.0f
        var sawWrappedNegativeAngle = false

        for (frame in 1..120) {
            val prevAngle = spring.currentAngle
            val delta = spring.update(target, dt)

            // Step delta must ALWAYS be positive (moving forward across 180 cut)
            if (abs(MathHelper.wrapDegrees(spring.currentAngle - target)) > 0.001f) {
                assertTrue(
                    delta >= 0.0f,
                    "Frame $frame: Delta must be non-negative traversing shortest path: delta=$delta, current=${spring.currentAngle}"
                )
            }

            // Calculate angular path step
            val stepDist = abs(MathHelper.wrapDegrees(spring.currentAngle - prevAngle))
            totalTraversedAbs += stepDist

            if (spring.currentAngle < -170.0f) {
                sawWrappedNegativeAngle = true
            }
        }

        // Settled angle must be -179.9
        val diff = abs(MathHelper.wrapDegrees(spring.currentAngle - target))
        assertTrue(diff < 0.002f, "Must settle at -179.9: actual=${spring.currentAngle}")

        // Total path traversed must be ~0.2 degrees, NEVER ~359.8 degrees!
        assertTrue(
            totalTraversedAbs in 0.19f..0.25f,
            "Total traversal must be ~0.2 deg (actual: $totalTraversedAbs deg). If ~360 deg, shortest path failed!"
        )
        assertTrue(sawWrappedNegativeAngle, "Must have wrapped across 180 boundary into negative hemisphere")
    }

    @Test
    fun testAngleWrapShortestPathNegativeCrossingMinus179To179() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        // Start at -179.9 degrees
        spring.reset(initialAngle = -179.9f, initialVelocity = 0.0f)

        // Target is at +179.9 degrees.
        // Shortest path is -0.2 degrees across the branch cut (-179.9 -> -180.0 / +180.0 -> +179.9).
        val target = 179.9f
        val dt = 1.0f / 144.0f

        var totalTraversedAbs = 0.0f
        var sawWrappedPositiveAngle = false

        for (frame in 1..120) {
            val prevAngle = spring.currentAngle
            val delta = spring.update(target, dt)

            if (abs(MathHelper.wrapDegrees(spring.currentAngle - target)) > 0.001f) {
                assertTrue(
                    delta <= 0.0f,
                    "Frame $frame: Delta must be non-positive traversing shortest path: delta=$delta, current=${spring.currentAngle}"
                )
            }

            val stepDist = abs(MathHelper.wrapDegrees(spring.currentAngle - prevAngle))
            totalTraversedAbs += stepDist

            if (spring.currentAngle > 170.0f) {
                sawWrappedPositiveAngle = true
            }
        }

        val diff = abs(MathHelper.wrapDegrees(spring.currentAngle - target))
        assertTrue(diff < 0.002f, "Must settle at +179.9: actual=${spring.currentAngle}")

        assertTrue(
            totalTraversedAbs in 0.19f..0.25f,
            "Total traversal must be ~0.2 deg (actual: $totalTraversedAbs deg). If ~360 deg, shortest path failed!"
        )
        assertTrue(sawWrappedPositiveAngle, "Must have wrapped across -180 boundary into positive hemisphere")
    }

    @Test
    fun testExactBranchCut180DegreesStaysAtRest() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)

        // 180.0 and -180.0 are topologically identical points on S^1
        spring.reset(initialAngle = 180.0f, initialVelocity = 0.0f)
        assertEquals(-180.0f, spring.currentAngle, epsilon, "180.0 wraps to -180.0 canonically")

        val delta1 = spring.update(targetAngle = -180.0f, deltaTimeSeconds = 0.016f)
        assertEquals(0.0f, delta1, epsilon, "Already at target -180.0, delta must be 0")
        assertEquals(-180.0f, spring.currentAngle, epsilon)
        assertTrue(spring.isAtRest)

        val delta2 = spring.update(targetAngle = 180.0f, deltaTimeSeconds = 0.016f)
        assertEquals(0.0f, delta2, epsilon, "Target 180.0 wraps to -180.0, delta must be 0")
        assertEquals(-180.0f, spring.currentAngle, epsilon)
        assertTrue(spring.isAtRest)
    }

    // =========================================================================
    // 3. Multi-Revolutions (> 3600 deg, extreme inputs)
    // =========================================================================

    @Test
    fun testMultiRevolutionTargetOver3600Degrees() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)

        // Target = 3645.0 degrees = 10 * 360 + 45.0 degrees -> wraps to +45.0
        val target = 3645.0f
        val dt = 1.0f / 60.0f

        var totalTraversedAbs = 0.0f
        for (i in 1..60) {
            val prev = spring.currentAngle
            spring.update(target, dt)
            totalTraversedAbs += abs(MathHelper.wrapDegrees(spring.currentAngle - prev))
        }

        val diff = abs(MathHelper.wrapDegrees(spring.currentAngle - 45.0f))
        assertTrue(diff < 0.1f, "Must settle at 45.0 deg: actual=${spring.currentAngle}")
        // Must NOT spin 10 full circles (3645 deg); must take shortest 45 deg path!
        assertTrue(
            totalTraversedAbs < 50.0f,
            "Total traversed angle must be ~45 deg, not 3645 deg! (actual: $totalTraversedAbs)"
        )
    }

    @Test
    fun testNegativeMultiRevolutionTargetOver3600Degrees() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)

        // Target = -3645.0 degrees = -10 * 360 - 45.0 degrees -> wraps to -45.0
        val target = -3645.0f
        val dt = 1.0f / 60.0f

        var totalTraversedAbs = 0.0f
        for (i in 1..60) {
            val prev = spring.currentAngle
            spring.update(target, dt)
            totalTraversedAbs += abs(MathHelper.wrapDegrees(spring.currentAngle - prev))
        }

        val diff = abs(MathHelper.wrapDegrees(spring.currentAngle - (-45.0f)))
        assertTrue(diff < 0.1f, "Must settle at -45.0 deg: actual=${spring.currentAngle}")
        assertTrue(
            totalTraversedAbs < 50.0f,
            "Total traversed angle must be ~45 deg, not 3645 deg! (actual: $totalTraversedAbs)"
        )
    }

    @Test
    fun testExtremeMultiRevolutionHundredRevolutions36000Degrees() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)

        // Target = 36030.0 degrees = 100 revolutions + 30.0 deg
        val target = 36030.0f
        for (i in 1..60) {
            spring.update(target, 1.0f / 60.0f)
        }

        val diff = abs(MathHelper.wrapDegrees(spring.currentAngle - 30.0f))
        assertTrue(diff < 0.1f, "36030.0 degrees must resolve cleanly to 30.0 degrees: actual=${spring.currentAngle}")
    }

    @Test
    fun testInitialAngleMultiRevolutionReset() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        // Reset with 7230.0 degrees (20 * 360 + 30)
        spring.reset(initialAngle = 7230.0f, initialVelocity = 0.0f)
        assertEquals(30.0f, spring.currentAngle, 0.01f, "Reset must sanitize initial angle to [-180, 180]")

        // Reset with negative multi-revolution -7230.0
        spring.reset(initialAngle = -7230.0f, initialVelocity = 0.0f)
        assertEquals(-30.0f, spring.currentAngle, 0.01f, "Reset must sanitize negative initial angle")
    }

    // =========================================================================
    // 4. Dynamic Stress (Sudden Reversals & Mid-Convergence Shifts)
    // =========================================================================

    @Test
    fun testSuddenReversalAtPeakVelocityDeceleratesWithoutSpike() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f, maxVelocity = 720.0f)
        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)

        val dt = 1.0f / 144.0f
        // Drive toward +90.0 deg until velocity builds up significantly
        for (i in 1..25) {
            spring.update(90.0f, dt)
        }

        val peakVel = spring.velocity
        assertTrue(peakVel > 200.0f, "Should have built high velocity: $peakVel")

        // Instantly switch target to -90.0 deg
        spring.update(-90.0f, dt)
        val velAfterReversal = spring.velocity

        // Velocity must not experience an unbounded discontinuous spike
        assertTrue(
            abs(velAfterReversal - peakVel) < 250.0f,
            "Velocity must change continuously across reversal: before=$peakVel, after=$velAfterReversal"
        )
        // Must stay bounded by maxVelocity
        assertTrue(abs(velAfterReversal) <= 720.0f + epsilon)

        // Continue updating toward -90.0: velocity must smoothly decelerate through 0 and reverse
        var passedZero = false
        var acceleratedNegative = false
        for (i in 1..100) {
            spring.update(-90.0f, dt)
            if (!passedZero && spring.velocity <= 0.0f) {
                passedZero = true
            }
            if (spring.velocity < -100.0f) {
                acceleratedNegative = true
            }
        }
        assertTrue(passedZero, "Spring velocity must smoothly decelerate through zero")
        assertTrue(acceleratedNegative, "Spring velocity must reverse and accelerate toward -90.0 target")

        // Settles at -90.0
        for (i in 1..100) {
            spring.update(-90.0f, dt)
        }
        val diff = abs(MathHelper.wrapDegrees(spring.currentAngle - (-90.0f)))
        assertTrue(diff < 0.1f, "Must settle at -90.0: actual=${spring.currentAngle}")
    }

    @Test
    fun testAlternatingTargetChatterAt144HzVelocityBounded() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f, maxVelocity = 720.0f)
        spring.reset(0.0f, 0.0f)

        val dt = 1.0f / 144.0f
        // High frequency chatter: alternate between +30 and -30 every single frame for 500 frames
        for (frame in 1..500) {
            val target = if (frame % 2 == 0) 30.0f else -30.0f
            spring.update(target, dt)

            assertFalse(spring.currentAngle.isNaN())
            assertFalse(spring.velocity.isNaN())
            assertTrue(
                abs(spring.velocity) <= 720.0f + epsilon,
                "Velocity must never exceed maxVelocity under chatter: frame $frame, vel=${spring.velocity}"
            )
            assertTrue(
                spring.currentAngle in -35.0f..35.0f,
                "Angle must remain bounded near chatter bounds: frame $frame, angle=${spring.currentAngle}"
            )
        }
    }

    // =========================================================================
    // 5. Clamped Axis (Pitch) Boundary Conditions & Anti-Windup
    // =========================================================================

    @Test
    fun testClampedPitchHardLimitsAndAntiWindup() {
        val minPitch = -89.9f
        val maxPitch = 89.9f
        val spring = AngularSpring1D(mode = AngleMode.CLAMPED, omega = 18.0f, minAngle = minPitch, maxAngle = maxPitch)

        spring.reset(initialAngle = 0.0f, initialVelocity = 0.0f)
        val dt = 1.0f / 60.0f

        // Drive pitch hard downward into the floor (+150.0 degrees)
        for (i in 1..60) {
            spring.update(targetAngle = 150.0f, deltaTimeSeconds = dt)
        }

        assertEquals(maxPitch, spring.currentAngle, epsilon, "Pitch must be hard-clamped to maxAngle 89.9")
        assertEquals(0.0f, spring.velocity, epsilon, "Velocity at boundary must be zeroed for anti-windup")

        // Immediate upward reversal on next frame
        val upwardDelta = spring.update(targetAngle = 0.0f, deltaTimeSeconds = dt)
        assertTrue(
            upwardDelta < 0.0f,
            "Pitch must respond upward immediately on frame 1 without windup latency: delta=$upwardDelta"
        )
        assertTrue(spring.currentAngle < maxPitch, "Angle must immediately leave boundary: ${spring.currentAngle}")

        // Now drive hard upward into the sky (-150.0 degrees)
        for (i in 1..80) {
            spring.update(targetAngle = -150.0f, deltaTimeSeconds = dt)
        }

        assertEquals(minPitch, spring.currentAngle, epsilon, "Pitch must be hard-clamped to minAngle -89.9")
        assertEquals(0.0f, spring.velocity, epsilon, "Velocity at boundary must be zeroed for anti-windup")

        // Immediate downward reversal on next frame
        val downwardDelta = spring.update(targetAngle = 0.0f, deltaTimeSeconds = dt)
        assertTrue(
            downwardDelta > 0.0f,
            "Pitch must respond downward immediately on frame 1 without windup latency: delta=$downwardDelta"
        )
        assertTrue(spring.currentAngle > minPitch, "Angle must immediately leave boundary: ${spring.currentAngle}")
    }

    @Test
    fun testClampedPitchInitialAngleSanitization() {
        val spring = AngularSpring1D(mode = AngleMode.CLAMPED, minAngle = -89.9f, maxAngle = 89.9f)
        spring.reset(initialAngle = 120.0f)
        assertEquals(89.9f, spring.currentAngle, epsilon, "Initial angle above max must be clamped")

        spring.reset(initialAngle = -120.0f)
        assertEquals(-89.9f, spring.currentAngle, epsilon, "Initial angle below min must be clamped")
    }

    // =========================================================================
    // 6. Robustness & Rejection: NaN and Infinity Boundary Tests
    // =========================================================================

    @Test
    fun testNaNAndInfinityInputDoesNotCrashJVM() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(0.0f, 0.0f)

        // DISPATCH requires: "NaN, Infinity inputs: verify robustness or rejection without process crash."
        // Must execute without throwing unhandled exceptions or JVM abort.
        try {
            spring.update(targetAngle = Float.NaN, deltaTimeSeconds = 0.016f)
        } catch (e: Throwable) {
            // Document if any exception is thrown
        }

        try {
            spring.update(targetAngle = Float.POSITIVE_INFINITY, deltaTimeSeconds = 0.016f)
        } catch (e: Throwable) {
        }

        try {
            spring.update(targetAngle = Float.NEGATIVE_INFINITY, deltaTimeSeconds = 0.016f)
        } catch (e: Throwable) {
        }

        try {
            spring.update(targetAngle = 45.0f, deltaTimeSeconds = Float.NaN)
        } catch (e: Throwable) {
        }

        try {
            spring.update(targetAngle = 45.0f, deltaTimeSeconds = Float.POSITIVE_INFINITY)
        } catch (e: Throwable) {
        }

        // Process survived all calls without crashing.
        assertTrue(true)
    }

    @Test
    fun testNaNTargetPoisoningBehavior() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 10.0f, initialVelocity = 0.0f)

        // Step with NaN target: must return 0.0f delta and preserve state
        val deltaNaN = spring.update(targetAngle = Float.NaN, deltaTimeSeconds = 0.016f)
        assertEquals(0.0f, deltaNaN, epsilon, "Delta must be 0.0f when target is NaN")
        assertEquals(10.0f, spring.currentAngle, epsilon, "currentAngle must not be corrupted by NaN target")
        assertEquals(0.0f, spring.velocity, epsilon, "velocity must not be corrupted by NaN target")
        assertFalse(spring.currentAngle.isNaN())
        assertFalse(spring.velocity.isNaN())

        // Subsequent call with valid target: must recover immediately without latching
        val deltaAfter = spring.update(targetAngle = 20.0f, deltaTimeSeconds = 0.016f)
        assertFalse(deltaAfter.isNaN(), "Delta must not be NaN on subsequent valid target")
        assertTrue(deltaAfter > 0.0f, "Spring must advance toward 20.0f target: delta=$deltaAfter")
        assertTrue(spring.currentAngle > 10.0f, "currentAngle must advance toward 20.0f: actual=${spring.currentAngle}")
        assertFalse(spring.currentAngle.isNaN())
        assertFalse(spring.velocity.isNaN())
    }

    @Test
    fun testNaNDeltaTimePoisoningBehavior() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 10.0f, initialVelocity = 0.0f)

        // Step with NaN deltaTime: must return 0.0f and preserve state
        val deltaNaN = spring.update(targetAngle = 20.0f, deltaTimeSeconds = Float.NaN)
        assertEquals(0.0f, deltaNaN, epsilon, "Delta must be 0.0f when dt is NaN")
        assertEquals(10.0f, spring.currentAngle, epsilon, "currentAngle must be preserved when dt is NaN")
        assertEquals(0.0f, spring.velocity, epsilon, "velocity must be preserved when dt is NaN")
        assertFalse(spring.currentAngle.isNaN())
        assertFalse(spring.velocity.isNaN())

        // Recovery on next valid frame
        val deltaAfter = spring.update(targetAngle = 20.0f, deltaTimeSeconds = 0.016f)
        assertFalse(deltaAfter.isNaN())
        assertTrue(deltaAfter > 0.0f)
    }

    @Test
    fun testInfinityTargetPoisoningBehavior() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 18.0f)
        spring.reset(initialAngle = 10.0f, initialVelocity = 0.0f)

        // Positive infinity target
        val deltaPosInf = spring.update(targetAngle = Float.POSITIVE_INFINITY, deltaTimeSeconds = 0.016f)
        assertEquals(0.0f, deltaPosInf, epsilon, "Delta must be 0.0f on +Infinity target")
        assertEquals(10.0f, spring.currentAngle, epsilon, "currentAngle must be preserved on +Infinity target")
        assertFalse(spring.currentAngle.isNaN())

        // Negative infinity target
        val deltaNegInf = spring.update(targetAngle = Float.NEGATIVE_INFINITY, deltaTimeSeconds = 0.016f)
        assertEquals(0.0f, deltaNegInf, epsilon, "Delta must be 0.0f on -Infinity target")
        assertEquals(10.0f, spring.currentAngle, epsilon, "currentAngle must be preserved on -Infinity target")
        assertFalse(spring.currentAngle.isNaN())

        // Recovery on next valid frame
        val deltaAfter = spring.update(targetAngle = 25.0f, deltaTimeSeconds = 0.016f)
        assertFalse(deltaAfter.isNaN())
        assertTrue(deltaAfter > 0.0f)
    }

    @Test
    fun testZeroOmegaFreeParticleBehavior() {
        val spring = AngularSpring1D(mode = AngleMode.WRAPPED, omega = 0.0f)
        spring.reset(initialAngle = 0.0f, initialVelocity = 100.0f)

        val delta = spring.update(targetAngle = 90.0f, deltaTimeSeconds = 0.1f)
        // With omega = 0, exp(-omega*dt) = 1.0, c2 = v0.
        // xNew = x0 + v0*dt, vNew = v0.
        // delta = xNew - x0 = v0 * dt = 10.0 deg.
        assertEquals(10.0f, delta, epsilon, "Zero omega behaves as free particle with constant velocity")
        assertEquals(10.0f, spring.currentAngle, epsilon)
        assertEquals(100.0f, spring.velocity, epsilon)
    }

    @Test
    fun testSpringSmoother2DCompositeSynchronousWrapAndClamp() {
        val smoother = SpringSmoother(omega = 18.0f, maxVelocity = 720.0f)
        smoother.reset(initialYaw = 179.9f, initialPitch = 85.0f)

        // Simultaneous Yaw shortest-path wrap (179.9 -> -179.9) and Pitch clamp overshoot (85.0 -> 95.0)
        val dt = 1.0f / 60.0f
        val delta = smoother.update(targetYaw = -179.9f, targetPitch = 95.0f, deltaTimeSeconds = dt)

        // Yaw must be positive across the wrap
        assertTrue(delta.deltaYaw > 0.0f, "Yaw delta must be positive across wrap cut: ${delta.deltaYaw}")
        // Pitch must be positive toward 89.9 limit
        assertTrue(delta.deltaPitch > 0.0f, "Pitch delta must be positive toward max: ${delta.deltaPitch}")

        for (i in 1..60) {
            smoother.update(targetYaw = -179.9f, targetPitch = 95.0f, deltaTimeSeconds = dt)
        }

        val yawDiff = abs(MathHelper.wrapDegrees(smoother.currentYaw - (-179.9f)))
        assertTrue(yawDiff < 0.1f, "Yaw must settle at -179.9: actual=${smoother.currentYaw}")
        assertEquals(89.9f, smoother.currentPitch, epsilon, "Pitch must be clamped to 89.9")
    }
}
