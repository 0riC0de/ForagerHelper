package com.github.foragerhelper.rotation

import net.minecraft.util.math.MathHelper
import kotlin.math.abs
import kotlin.math.exp

/**
 * Operating mode for angular spring smoothing.
 */
enum class AngleMode {
    /**
     * Angles wrap on the circle S^1 across [-180, 180] degrees (Yaw).
     * Shortest angular distance is computed via MathHelper.wrapDegrees.
     */
    WRAPPED,

    /**
     * Angles are bounded on a closed linear interval (Pitch).
     * Clamped to [minAngle, maxAngle], velocity zeroed at boundaries (anti-windup).
     */
    CLAMPED
}

/**
 * High-performance 1D critically damped angular spring using exact closed-form
 * analytic integration for unconditional A-stability and time-step independence.
 *
 * Differential Equation:
 *   x''(t) + 2 * omega * x'(t) + omega^2 * x(t) = 0
 *
 * Analytic Solution for step h:
 *   c2 = v0 + omega * x0
 *   x(h) = (x0 + c2 * h) * exp(-omega * h)
 *   v(h) = (v0 - omega * c2 * h) * exp(-omega * h)
 *
 * @property mode Wrapping vs clamping mode.
 * @property omega Natural frequency in rad/s (default 18.0 rad/s).
 * @property maxVelocity Maximum angular speed in degrees/second (default 720.0 deg/s).
 * @property minAngle Minimum angle for CLAMPED mode (default -89.9f).
 * @property maxAngle Maximum angle for CLAMPED mode (default 89.9f).
 */
class AngularSpring1D(
    val mode: AngleMode,
    var omega: Float = 18.0f,
    var maxVelocity: Float = 720.0f,
    var minAngle: Float = -89.9f,
    var maxAngle: Float = 89.9f
) {
    var currentAngle: Float = 0.0f
        private set

    var velocity: Float = 0.0f
        private set

    val isAtRest: Boolean
        get() = abs(velocity) < VELOCITY_REST_THRESHOLD

    /**
     * Initializes or forcibly sets the spring state.
     * Sanitizes inputs to prevent NaN or Infinite values from seeding corrupted state.
     */
    fun reset(initialAngle: Float, initialVelocity: Float = 0.0f) {
        val safeAngle = if (initialAngle.isFinite()) initialAngle else 0.0f
        val safeVelocity = if (initialVelocity.isFinite()) initialVelocity else 0.0f
        currentAngle = sanitizeAngle(safeAngle)
        velocity = safeVelocity
    }

    /**
     * Advances the spring state toward targetAngle over deltaTimeSeconds.
     * Returns the continuous angular delta (in degrees) traversed during this step.
     *
     * Defensive Guarantees:
     * - If deltaTimeSeconds is non-positive, NaN, or Infinite, returns 0.0f with zero state modification.
     * - If targetAngle is NaN or Infinite, returns 0.0f with zero state modification (no latching).
     * - Self-heals corrupted internal state (NaN/Inf in currentAngle/velocity) back to finite values.
     *
     * @param targetAngle The desired goal angle.
     * @param deltaTimeSeconds Frame elapsed time in seconds.
     * @return Angular delta traversed during this frame.
     */
    fun update(targetAngle: Float, deltaTimeSeconds: Float): Float {
        // Defensive input sanitization: reject invalid delta times immediately
        if (deltaTimeSeconds.isNaN() || deltaTimeSeconds.isInfinite() || deltaTimeSeconds <= 0.0f) {
            return 0.0f
        }

        // Defensive input sanitization: reject NaN/Infinity targets without state modification (no latching)
        if (targetAngle.isNaN() || targetAngle.isInfinite()) {
            return 0.0f
        }

        // Self-healing: unlatch if internal state was corrupted prior to call
        if (!currentAngle.isFinite()) currentAngle = 0.0f
        if (!velocity.isFinite()) velocity = 0.0f

        // Guard against massive frame freeze / alt-tab spikes
        val dt = deltaTimeSeconds.coerceAtMost(MAX_INTEGRATION_STEP)

        val target = sanitizeAngle(targetAngle)

        // 1. Calculate shortest displacement x0 = current - target
        var x0 = when (mode) {
            AngleMode.WRAPPED -> MathHelper.wrapDegrees(currentAngle - target)
            AngleMode.CLAMPED -> currentAngle - target
        }

        // 2. Dawson virtual displacement clamp for peak velocity limiting
        if (maxVelocity > 0.0f && omega > 0.0f) {
            val maxDisplacement = maxVelocity / omega
            x0 = x0.coerceIn(-maxDisplacement, maxDisplacement)
        }

        // 3. Settling deadband check
        if (abs(x0) < ANGLE_REST_THRESHOLD && abs(velocity) < VELOCITY_REST_THRESHOLD) {
            val delta = when (mode) {
                AngleMode.WRAPPED -> MathHelper.wrapDegrees(target - currentAngle)
                AngleMode.CLAMPED -> target - currentAngle
            }
            currentAngle = target
            velocity = 0.0f
            return delta
        }

        // 4. Exact analytic integration (unconditionally stable for all dt > 0)
        val c2 = velocity + omega * x0
        val expFactor = exp(-omega * dt)

        val xNew = (x0 + c2 * dt) * expFactor
        var vNew = (velocity - omega * c2 * dt) * expFactor

        // 5. Clamp velocity to physical / anti-cheat limit
        if (maxVelocity > 0.0f) {
            vNew = vNew.coerceIn(-maxVelocity, maxVelocity)
        }

        // 6. Compute frame delta: delta = xNew - x0
        val frameDelta = xNew - x0
        val nextAngle = currentAngle + frameDelta

        // 7. Apply boundary conditions
        when (mode) {
            AngleMode.WRAPPED -> {
                currentAngle = MathHelper.wrapDegrees(nextAngle)
                velocity = vNew
            }
            AngleMode.CLAMPED -> {
                if (nextAngle <= minAngle) {
                    currentAngle = minAngle
                    velocity = if (vNew < 0f) 0f else vNew // Anti-windup
                } else if (nextAngle >= maxAngle) {
                    currentAngle = maxAngle
                    velocity = if (vNew > 0f) 0f else vNew // Anti-windup
                } else {
                    currentAngle = nextAngle
                    velocity = vNew
                }
            }
        }

        return frameDelta
    }

    private fun sanitizeAngle(angle: Float): Float {
        if (!angle.isFinite()) {
            return if (currentAngle.isFinite()) currentAngle else 0.0f
        }
        return when (mode) {
            AngleMode.WRAPPED -> MathHelper.wrapDegrees(angle)
            AngleMode.CLAMPED -> angle.coerceIn(minAngle, maxAngle)
        }
    }

    companion object {
        const val ANGLE_REST_THRESHOLD = 0.001f // 0.001 degrees
        const val VELOCITY_REST_THRESHOLD = 0.01f // 0.01 degrees/sec
        const val MAX_INTEGRATION_STEP = 0.2f // 200 ms max frame delta
    }
}

/**
 * Result of a 2D spring smoothing step containing yaw and pitch deltas.
 */
data class RotationDelta(
    val deltaYaw: Float,
    val deltaPitch: Float
)

/**
 * 2D Composite Camera Smoother for Yaw and Pitch.
 */
class SpringSmoother(
    omega: Float = 18.0f,
    maxVelocity: Float = 720.0f
) {
    val yawSpring = AngularSpring1D(
        mode = AngleMode.WRAPPED,
        omega = omega,
        maxVelocity = maxVelocity
    )

    val pitchSpring = AngularSpring1D(
        mode = AngleMode.CLAMPED,
        omega = omega,
        maxVelocity = maxVelocity,
        minAngle = -89.9f,
        maxAngle = 89.9f
    )

    val currentYaw: Float get() = yawSpring.currentAngle
    val currentPitch: Float get() = pitchSpring.currentAngle

    val yawVelocity: Float get() = yawSpring.velocity
    val pitchVelocity: Float get() = pitchSpring.velocity

    val isAtRest: Boolean get() = yawSpring.isAtRest && pitchSpring.isAtRest

    /**
     * Initializes angles and zeroes out angular velocity.
     * Sanitizes inputs to prevent NaN/Infinity poisoning.
     */
    fun reset(initialYaw: Float, initialPitch: Float) {
        val safeYaw = if (initialYaw.isFinite()) initialYaw else 0.0f
        val safePitch = if (initialPitch.isFinite()) initialPitch else 0.0f
        yawSpring.reset(safeYaw, 0.0f)
        pitchSpring.reset(safePitch, 0.0f)
    }

    /**
     * Snaps current angles directly to target, identical to reset.
     */
    fun snapTo(targetYaw: Float, targetPitch: Float) {
        reset(targetYaw, targetPitch)
    }

    /**
     * Sets natural frequency for both axes.
     */
    fun setNaturalFrequency(omega: Float) {
        if (omega.isFinite() && omega >= 0.0f) {
            yawSpring.omega = omega
            pitchSpring.omega = omega
        }
    }

    /**
     * Updates both yaw and pitch springs toward target angles.
     *
     * Defensive Guarantees:
     * - Rejects NaN/Infinite deltaTimeSeconds with RotationDelta(0.0f, 0.0f).
     * - Evaluates axes independently: if targetYaw is NaN but targetPitch is valid,
     *   yaw delta is 0.0f while pitch continues to converge smoothly.
     * - Guarantees zero state latching across transient input corruptions.
     *
     * @return Pair of (yawDelta, pitchDelta) traversed during this frame.
     */
    fun update(targetYaw: Float, targetPitch: Float, deltaTimeSeconds: Float): RotationDelta {
        if (deltaTimeSeconds.isNaN() || deltaTimeSeconds.isInfinite() || deltaTimeSeconds <= 0.0f) {
            return RotationDelta(0.0f, 0.0f)
        }
        val dYaw = yawSpring.update(targetYaw, deltaTimeSeconds)
        val dPitch = pitchSpring.update(targetPitch, deltaTimeSeconds)
        return RotationDelta(dYaw, dPitch)
    }
}
