package com.github.foragerhelper.rotation

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitivityGCDAdversarialTest {

    private val doubleEpsilon = 1e-9

    // =========================================================================
    // Challenge 1: Applied Delta Exact Multiple of GCD Step
    // =========================================================================

    @Test
    fun testAppliedDeltaIsExactMultipleOfGCDStep() {
        val testSensitivities = doubleArrayOf(0.0, 0.1, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0)
        val spyglassModes = booleanArrayOf(false, true)

        for (sens in testSensitivities) {
            for (isSpyglass in spyglassModes) {
                val gcd = SensitivityGCD()
                val step = SensitivityGCD.computeStep(sens, isSpyglass)
                val random = Random(42)

                for (frame in 1..500) {
                    val desiredYaw = (random.nextDouble() - 0.5) * 15.0
                    val desiredPitch = (random.nextDouble() - 0.5) * 15.0

                    val result = gcd.quantize(
                        desiredDeltaYaw = desiredYaw,
                        desiredDeltaPitch = desiredPitch,
                        sensitivity = sens,
                        isSpyglass = isSpyglass
                    )

                    // Verify Yaw
                    if (result.countsYaw != 0) {
                        val expectedDelta = SensitivityGCD.countsToDelta(result.countsYaw, sens, isSpyglass)
                        assertEquals(
                            expectedDelta,
                            result.appliedDeltaYaw,
                            "appliedDeltaYaw must match vanilla countsToDelta exactly"
                        )
                        val ratio = result.appliedDeltaYaw.toDouble() / step
                        val nearestInt = round(ratio)
                        val ulpError = abs(ratio - nearestInt)
                        assertTrue(
                            ulpError < 1e-3,
                            "Applied yaw delta must be integer multiple of step within float ULP: ratio=$ratio, diff=$ulpError, sens=$sens, spyglass=$isSpyglass"
                        )
                    } else {
                        assertEquals(0.0f, result.appliedDeltaYaw)
                    }

                    // Verify Pitch
                    if (result.countsPitch != 0) {
                        val expectedDelta = SensitivityGCD.countsToDelta(result.countsPitch, sens, isSpyglass)
                        assertEquals(
                            expectedDelta,
                            result.appliedDeltaPitch,
                            "appliedDeltaPitch must match vanilla countsToDelta exactly"
                        )
                        val ratio = result.appliedDeltaPitch.toDouble() / step
                        val nearestInt = round(ratio)
                        val ulpError = abs(ratio - nearestInt)
                        assertTrue(
                            ulpError < 1e-3,
                            "Applied pitch delta must be integer multiple of step within float ULP: ratio=$ratio, diff=$ulpError, sens=$sens, spyglass=$isSpyglass"
                        )
                    } else {
                        assertEquals(0.0f, result.appliedDeltaPitch)
                    }
                }
            }
        }
    }

    // =========================================================================
    // Challenge 2: Sensitivity Extreme Range [0.0, 0.5, 1.0, 2.0] & Degenerate Inputs
    // =========================================================================

    @Test
    fun testSensitivityExtremeRange() {
        val cases = listOf(
            0.0 to "Minimum vanilla sensitivity",
            0.5 to "Default sensitivity",
            1.0 to "Maximum standard sensitivity",
            2.0 to "Hyper-speed / cinematic max sensitivity"
        )

        for ((sens, description) in cases) {
            val gcd = SensitivityGCD()
            val stepNormal = SensitivityGCD.computeStep(sens, isSpyglass = false)
            val stepSpyglass = SensitivityGCD.computeStep(sens, isSpyglass = true)

            assertTrue(stepNormal > 0.0, "Step must be strictly positive for $description (sens=$sens): step=$stepNormal")
            assertTrue(stepSpyglass > 0.0, "Spyglass step must be strictly positive for $description (sens=$sens)")
            assertEquals(stepNormal / 8.0, stepSpyglass, 1e-7, "Spyglass step must be exactly 1/8 normal step")

            // Test quantization at this sensitivity
            val delta = stepNormal * 3.5
            val result = gcd.quantize(delta, delta, sens)
            assertEquals(4, result.countsYaw, "Nearest count for 3.5 steps must be 4: sens=$sens")
            assertEquals(4, result.countsPitch, "Nearest count for 3.5 steps must be 4: sens=$sens")
            assertTrue(abs(result.yawRemainder) <= 0.5 * stepNormal + doubleEpsilon)
            assertTrue(abs(result.pitchRemainder) <= 0.5 * stepNormal + doubleEpsilon)
        }
    }

    @Test
    fun testDegenerateSensitivitiesFallback() {
        val gcd = SensitivityGCD()
        val defaultStep = SensitivityGCD.computeStep(0.5)

        // Negative sensitivity should sanitize to 0.5
        val resNeg = gcd.quantize(1.0, 1.0, -0.5)
        assertEquals(defaultStep, resNeg.step, doubleEpsilon)

        // NaN sensitivity should sanitize to 0.5
        val resNan = gcd.quantize(1.0, 1.0, Double.NaN)
        assertEquals(defaultStep, resNan.step, doubleEpsilon)

        // Greater than 2.0 sensitivity should clamp to 2.0
        val maxStep = SensitivityGCD.computeStep(2.0)
        val resLarge = gcd.quantize(1.0, 1.0, 100.0)
        assertEquals(maxStep, resLarge.step, doubleEpsilon)
    }

    // =========================================================================
    // Challenge 3: 10,000-Frame Remainder Accumulator Stability (No Drift, No Explosion)
    // =========================================================================

    @Test
    fun test10000FrameRemainderAccumulatorConstantSubStep() {
        val testSensitivities = doubleArrayOf(0.0, 0.5, 1.0, 2.0)

        for (sens in testSensitivities) {
            val gcd = SensitivityGCD()
            val step = SensitivityGCD.computeStep(sens)
            val subStepDelta = step * 0.237 // Irrationally offset sub-step delta
            var cumulativeAppliedYaw = 0.0
            var cumulativeAppliedPitch = 0.0

            for (frame in 1..10000) {
                val result = gcd.quantize(
                    desiredDeltaYaw = subStepDelta,
                    desiredDeltaPitch = subStepDelta,
                    sensitivity = sens
                )

                cumulativeAppliedYaw += result.appliedDeltaYaw.toDouble()
                cumulativeAppliedPitch += result.appliedDeltaPitch.toDouble()

                // On EVERY frame, remainder must strictly stay within [-0.5 * step, +0.5 * step]
                assertTrue(
                    abs(result.yawRemainder) <= 0.5 * step + doubleEpsilon,
                    "Frame $frame: Yaw remainder broke bound [${-0.5 * step}, ${0.5 * step}]: actual=${result.yawRemainder} at sens=$sens"
                )
                assertTrue(
                    abs(result.pitchRemainder) <= 0.5 * step + doubleEpsilon,
                    "Frame $frame: Pitch remainder broke bound [${-0.5 * step}, ${0.5 * step}]: actual=${result.pitchRemainder} at sens=$sens"
                )
            }

            // Total accumulated drift across 10,000 frames must stay <= 0.5 * step
            val totalDesired = subStepDelta * 10000
            val driftYaw = abs(cumulativeAppliedYaw - totalDesired)
            val driftPitch = abs(cumulativeAppliedPitch - totalDesired)

            assertTrue(
                driftYaw <= 0.5 * step + 1e-4,
                "Total 10,000 frame drift on yaw ($driftYaw) exceeded 0.5 * step (${0.5 * step}) at sens=$sens"
            )
            assertTrue(
                driftPitch <= 0.5 * step + 1e-4,
                "Total 10,000 frame drift on pitch ($driftPitch) exceeded 0.5 * step (${0.5 * step}) at sens=$sens"
            )
        }
    }

    @Test
    fun test10000FrameRemainderAccumulatorSinusoidal() {
        val gcd = SensitivityGCD()
        val sens = 0.5
        val step = SensitivityGCD.computeStep(sens)

        for (frame in 1..10000) {
            val t = frame * (1.0 / 144.0)
            val deltaYaw = sin(t * 3.7) * 0.42
            val deltaPitch = sin(t * 5.1) * 0.28

            val result = gcd.quantize(deltaYaw, deltaPitch, sens)

            assertTrue(
                abs(result.yawRemainder) <= 0.5 * step + doubleEpsilon,
                "Sinusoidal test frame $frame: yaw remainder ${result.yawRemainder} exceeded half-step ${0.5 * step}"
            )
            assertTrue(
                abs(result.pitchRemainder) <= 0.5 * step + doubleEpsilon,
                "Sinusoidal test frame $frame: pitch remainder ${result.pitchRemainder} exceeded half-step ${0.5 * step}"
            )
        }
    }

    @Test
    fun test10000FrameRemainderAccumulatorRandomNoise() {
        val gcd = SensitivityGCD()
        val sens = 1.0
        val step = SensitivityGCD.computeStep(sens)
        val random = Random(12345)

        for (frame in 1..10000) {
            val deltaYaw = (random.nextDouble() - 0.5) * 2.0
            val deltaPitch = (random.nextDouble() - 0.5) * 2.0

            val result = gcd.quantize(deltaYaw, deltaPitch, sens)

            assertTrue(
                abs(result.yawRemainder) <= 0.5 * step + doubleEpsilon,
                "Random noise test frame $frame: yaw remainder ${result.yawRemainder} exceeded half-step ${0.5 * step}"
            )
            assertTrue(
                abs(result.pitchRemainder) <= 0.5 * step + doubleEpsilon,
                "Random noise test frame $frame: pitch remainder ${result.pitchRemainder} exceeded half-step ${0.5 * step}"
            )
        }
    }

    // =========================================================================
    // Challenge 4: Pitch Limits [-90, +90] & Anti-Windup Boundary Integrity
    // =========================================================================

    @Test
    fun testPitchLimitsNeverExceededWhenApproachingBoundaries() {
        val testSensitivities = doubleArrayOf(0.0, 0.5, 1.0, 2.0)

        for (sens in testSensitivities) {
            val gcd = SensitivityGCD()
            val step = SensitivityGCD.computeStep(sens)

            // Approach downward pitch limit (+90.0)
            var currentPitch = 80.0f
            for (frame in 1..1000) {
                val desiredDelta = 0.5 // Trying to look further down
                val result = gcd.quantize(
                    desiredDeltaYaw = 0.0,
                    desiredDeltaPitch = desiredDelta,
                    sensitivity = sens,
                    currentPitch = currentPitch
                )

                currentPitch += result.appliedDeltaPitch

                // Pitch must NEVER exceed 90.0
                assertTrue(
                    currentPitch <= 90.0f + 1e-5f,
                    "Downwards pitch exceeded +90 degrees at frame $frame: pitch=$currentPitch, sens=$sens"
                )
                assertTrue(
                    currentPitch >= -90.0f - 1e-5f,
                    "Pitch below -90 degrees at frame $frame: pitch=$currentPitch, sens=$sens"
                )
            }

            // Now immediately request upward rotation (-1.0 deg)
            val reverseResult = gcd.quantize(
                desiredDeltaYaw = 0.0,
                desiredDeltaPitch = -1.0,
                sensitivity = sens,
                currentPitch = currentPitch
            )

            // Anti-windup check: must respond upward immediately without being stalled by accumulated remainder
            assertTrue(
                reverseResult.countsPitch < 0,
                "Camera must reverse upward immediately upon negative desired pitch: countsPitch=${reverseResult.countsPitch}, pitch=$currentPitch, sens=$sens"
            )

            // Approach upward pitch limit (-90.0)
            for (frame in 1..1000) {
                val desiredDelta = -0.5 // Trying to look further up
                val result = gcd.quantize(
                    desiredDeltaYaw = 0.0,
                    desiredDeltaPitch = desiredDelta,
                    sensitivity = sens,
                    currentPitch = currentPitch
                )

                currentPitch += result.appliedDeltaPitch

                assertTrue(
                    currentPitch >= -90.0f - 1e-5f,
                    "Upwards pitch exceeded -90 degrees at frame $frame: pitch=$currentPitch, sens=$sens"
                )
                assertTrue(
                    currentPitch <= 90.0f + 1e-5f,
                    "Pitch above +90 degrees at frame $frame: pitch=$currentPitch, sens=$sens"
                )
            }
        }
    }

    @Test
    fun testPitchBoundaryRemainderExplosionStressTest() {
        val gcd = SensitivityGCD()
        val sens = 0.5
        val step = SensitivityGCD.computeStep(sens)

        // Peg pitch near boundary, say 89.5 degrees
        var currentPitch = 89.5f
        for (frame in 1..1000) {
            val result = gcd.quantize(
                desiredDeltaYaw = 0.0,
                desiredDeltaPitch = 1.0, // Large positive delta pushing into boundary
                sensitivity = sens,
                currentPitch = currentPitch
            )
            currentPitch += result.appliedDeltaPitch

            assertTrue(
                currentPitch <= 90.0f + 1e-5f,
                "Pitch exceeded 90.0: $currentPitch"
            )

            // Check if pitchRemainder exploded or remained bounded
            assertTrue(
                abs(result.pitchRemainder) <= 0.5 * step + doubleEpsilon,
                "Frame $frame: Pitch remainder exploded to ${result.pitchRemainder} while pegged at pitch limit (max allowed: ${0.5 * step})"
            )
        }
    }
}
