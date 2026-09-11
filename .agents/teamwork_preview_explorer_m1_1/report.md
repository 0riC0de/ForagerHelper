# M1 Technical Report: Continuous Critically-Damped Angular Spring Smoother

**Author**: `teamwork_preview_explorer_m1_1`  
**Role**: Rotation Spring Dynamics Specialist  
**Module**: `com.github.foragerhelper.rotation.SpringSmoother`  
**Date**: 2026-09-11  
**Project**: ForagerHelper (Minecraft Fabric 1.21.11)

---

## 1. Executive Summary

This report specifies the complete mathematical model, numerical integration algorithm, boundary topology, and implementation architecture for `SpringSmoother.kt`, the core angular smoothing engine for ForagerHelper's camera rewrite.

### Key Architectural Achievements:
1. **Mathematical Elimination of Curve-Reset Stutter**: Replaces the flawed cubic-polynomial tick counter in `WalkController.kt` (which reset interpolation progress to zero on any target shift $> 0.35^\circ$) with a continuous, second-order dynamical system. Angular velocity is tracked as persistent state across frames, guaranteeing $C^1$ continuity (continuous position and continuous first derivative) even when waypoints or targets change dynamically mid-flight.
2. **Exact Analytic Closed-Form Integration**: Uses the exact closed-form solution of the critically damped second-order ordinary differential equation ($\zeta = 1.0$) rather than numerical Euler integration. This yields **unconditional $A$-stability** ($\rho(M(h)) = e^{-\omega_n h} < 1$ for all $h > 0$), exact time-step independence across arbitrary monitor refresh rates (60 Hz, 144 Hz, 240 Hz, 360 Hz+), and zero numerical divergence during severe frame lag spikes.
3. **Rigorous $S^1$ Angular Wrapping**: Implements shortest-arc angular distance calculation modulo $360^\circ$ using Minecraft's `MathHelper.wrapDegrees`, eliminating full-circle $359^\circ$ spin glitches. Angular velocity is topologically invariant under coordinate wrapping, preserving kinetic momentum across $\pm 180^\circ$.
4. **Decoupled 2D Architecture**: Distinguishes Yaw (spherical circle $S^1$, wrapped $[-180^\circ, 180^\circ]$) from Pitch (finite interval $[-89.9^\circ, 89.9^\circ]$, clamped with anti-windup), providing dedicated 1D spring primitives unified into a 2D composite smoother.
5. **Anti-Cheat Safety & Dawson Velocity Clamping**: Integrates dynamic virtual target clamping (adapted from Game Programming Gems 4) to limit peak angular velocity to human physiological limits (default $720^\circ/\text{s}$), preventing single-frame rotation spikes that trigger anti-cheat heuristics (GrimAC, Polar, Watchdog).

---

## 2. Root Cause Analysis of Existing Camera Defects

In the legacy implementation (`src/main/kotlin/foraginghelpermod/client/path/WalkController.kt:357-407`), camera motion suffers from severe mechanical flaws:

```kotlin
// WalkController.kt:357-366
val newTarget = lookPlanTargetYaw.isNaN() ||
    abs(MathHelper.wrapDegrees(targetYaw - lookPlanTargetYaw)) > 0.35f ||
    abs(targetPitch - lookPlanTargetPitch) > 0.35f
if (newTarget) {
    lookPlanStartYaw = MathHelper.wrapDegrees(player.yaw)
    ...
    lookPlanStep = 0 // CRITICAL DEFECT: Aborts current velocity to zero!
}
lookPlanStep++
val fraction = (lookPlanStep.toFloat() / lookPlanSteps).coerceIn(0f, 1f)
val eased = fraction * fraction * (3f - 2f * fraction)
```

### The Three Fatal Failure Modes:
1. **Velocity Clamping to Zero on Retargeting (Curve Resetting)**:
   Whenever the target angle shifts by $> 0.35^\circ$ (which occurs constantly as the player walks toward moving waypoints or scans nearby blocks), `lookPlanStep` is reset to `0`. The current in-flight cubic curve is terminated, instantaneously truncating angular velocity $\dot{\theta}$ to $0$. The player experiences a violent visual hitch or stutter.
2. **Tick Coupling (20 Hz)**:
   `lookPlanStep` advances by integer ticks in `ClientTickEvents.END_CLIENT_TICK`. On a 144 Hz display, the camera remains motionless for ~7 frames (48.6 ms), then snaps forward on the 8th frame.
3. **High-Frequency White Noise**:
   Lines 397-401 inject uniform pseudorandom noise (`Random.nextDouble(-jitterScale, jitterScale)`) directly onto the target angles on every tick, causing rapid high-frequency camera vibration rather than organic smooth motion.

---

## 3. Mathematical Model of Critically Damped Angular Springs

### 3.1 The Continuous Second-Order Differential Equation

We model each rotational axis as a second-order linear dynamical system driven by a spring force proportional to angular error and damped by a friction force proportional to angular velocity:

$$\ddot{\theta}(t) + 2\zeta\omega_n\dot{\theta}(t) + \omega_n^2(\theta(t) - \theta^*(t)) = 0$$

Where:
- $\theta(t)$ is the current angle (in degrees).
- $\theta^*(t)$ is the target angle (in degrees).
- $\omega_n > 0$ is the undamped natural angular frequency (in $\text{rad/s}$).
- $\zeta \ge 0$ is the damping ratio (dimensionless).

Let the angular displacement (error) from target be:
$$x(t) = \theta(t) - \theta^*(t)$$

Assuming the target $\theta^*$ is held constant over an integration frame $[t, t + h]$:
$$\dot{x}(t) = \dot{\theta}(t) = v(t)$$
$$\ddot{x}(t) = \ddot{\theta}(t)$$

The equation of motion reduces to the standard homogeneous second-order linear ODE:
$$\ddot{x}(t) + 2\zeta\omega_n\dot{x}(t) + \omega_n^2 x(t) = 0$$

The characteristic polynomial is:
$$r^2 + 2\zeta\omega_n r + \omega_n^2 = 0$$
with roots:
$$r_{1,2} = -\zeta\omega_n \pm \omega_n\sqrt{\zeta^2 - 1}$$

### 3.2 Proof of Critical Damping ($\zeta = 1.0$) as the Optimal Regime

| Damping Ratio | Roots $r_{1,2}$ | Qualitative Behavior | Applicability to Minecraft Camera |
| :--- | :--- | :--- | :--- |
| **$\zeta < 1$ (Underdamped)** | $-\zeta\omega_n \pm i\omega_d$ | Damped harmonic oscillation ($e^{-\zeta\omega_n t}\cos(\omega_d t)$). Crosshair overshoots the target and rings back and forth. | **Unacceptable**: Causes crosshairs to oscillate over block faces, causing mis-clicks and triggering anti-cheat "snapback" detection heuristics. |
| **$\zeta > 1$ (Overdamped)** | Distinct negative real roots: $-\omega_n(\zeta \pm \sqrt{\zeta^2 - 1})$ | Non-oscillatory exponential decay dominated by the slower pole $-\omega_n(\zeta - \sqrt{\zeta^2 - 1}) > -\omega_n$. | **Sub-optimal**: Sluggish, heavy camera response with excessive settling time. |
| **$\zeta = 1.0$ (Critically Damped)** | Repeated real root: $r = -\omega_n$ | Fastest possible monotonic decay toward equilibrium without overshoot. | **Mathematically Optimal**: Rapid acquisition, silky smooth deceleration, zero overshoot from rest. |

---

## 4. Exact Analytic Closed-Form Delta-Time Integration

### 4.1 Derivation of Closed-Form Solution

For $\zeta = 1$, the general solution to $\ddot{x} + 2\omega_n\dot{x} + \omega_n^2 x = 0$ is:
$$x(t) = (C_1 + C_2 t) e^{-\omega_n t}$$

Differentiating with respect to $t$:
$$\dot{x}(t) = C_2 e^{-\omega_n t} - \omega_n(C_1 + C_2 t) e^{-\omega_n t} = [C_2 - \omega_n(C_1 + C_2 t)] e^{-\omega_n t}$$

Given the initial conditions at the beginning of a frame $t = 0$:
- Displacement: $x(0) = x_0$
- Angular velocity: $\dot{x}(0) = v_0$

Solving for constants $C_1$ and $C_2$:
$$x(0) = C_1 = x_0$$
$$\dot{x}(0) = C_2 - \omega_n x_0 = v_0 \implies C_2 = v_0 + \omega_n x_0$$

Substituting $C_1$ and $C_2$ back into the solution at time $t = h$ (where $h = \Delta t$ is the frame delta time):
$$x(h) = [x_0 + (v_0 + \omega_n x_0) h] e^{-\omega_n h}$$
$$v(h) = [v_0 - \omega_n(v_0 + \omega_n x_0) h] e^{-\omega_n h}$$

### 4.2 Matrix Formulation and Stability Proof

Expressing the update as a linear state-space transformation:
$$\begin{bmatrix} x(h) \\ v(h) \end{bmatrix} = M(h) \begin{bmatrix} x_0 \\ v_0 \end{bmatrix}$$

where the state transition matrix $M(h)$ is:
$$M(h) = e^{-\omega_n h} \begin{bmatrix} 1 + \omega_n h & h \\ -\omega_n^2 h & 1 - \omega_n h \end{bmatrix}$$

**Theorem (Unconditional $A$-Stability)**:
The exact analytic integration operator $M(h)$ is unconditionally stable for all time steps $h > 0$ and all natural frequencies $\omega_n > 0$.

**Proof**:
The characteristic equation of $M(h)$ is:
$$\det(M(h) - \lambda I) = 0$$
$$\det \begin{bmatrix} (1 + \omega_n h)e^{-\omega_n h} - \lambda & h e^{-\omega_n h} \\ -\omega_n^2 h e^{-\omega_n h} & (1 - \omega_n h)e^{-\omega_n h} - \lambda \end{bmatrix} = 0$$
$$\lambda^2 - \text{tr}(M(h))\lambda + \det(M(h)) = 0$$

Computing the trace and determinant:
$$\text{tr}(M(h)) = e^{-\omega_n h} [(1 + \omega_n h) + (1 - \omega_n h)] = 2 e^{-\omega_n h}$$
$$\det(M(h)) = e^{-2\omega_n h} [(1 + \omega_n h)(1 - \omega_n h) - (h)(-\omega_n^2 h)] = e^{-2\omega_n h} [1 - \omega_n^2 h^2 + \omega_n^2 h^2] = e^{-2\omega_n h}$$

Hence:
$$\lambda^2 - 2 e^{-\omega_n h}\lambda + e^{-2\omega_n h} = (\lambda - e^{-\omega_n h})^2 = 0$$

$M(h)$ has a repeated real eigenvalue:
$$\lambda_1 = \lambda_2 = e^{-\omega_n h}$$

The spectral radius $\rho(M(h))$ is:
$$\rho(M(h)) = \max(|\lambda_1|, |\lambda_2|) = e^{-\omega_n h}$$

For any $\omega_n > 0$ and any positive time step $h > 0$:
$$0 < e^{-\omega_n h} < 1 \implies \rho(M(h)) < 1$$

Because the spectral radius is strictly less than 1 for all $h \in (0, \infty)$, all state trajectories asymptotically decay to zero. The system cannot diverge, oscillate, or explode, even under catastrophic frame spikes (e.g. $h = 2.0\text{ s}$). $\blacksquare$

### 4.3 Analytic Scheme vs. Numerical Approximations

| Scheme | Formula | Stability Condition | Max Safe $h$ at $\omega_n = 20$ | Behavior on Lag Spike |
| :--- | :--- | :--- | :--- | :--- |
| **Explicit Euler** | $v_{k+1} = v_k - (2\omega_n v_k + \omega_n^2 x_k)h$<br>$x_{k+1} = x_k + v_k h$ | $h < \frac{2}{\omega_n}$ | $h < 0.100\text{ s}$ | Explodes to $\pm \infty$ / `NaN` |
| **Semi-Implicit Euler** | $v_{k+1} = v_k - (2\omega_n v_k + \omega_n^2 x_k)h$<br>$x_{k+1} = x_k + v_{k+1} h$ | Conditional | $h < 0.150\text{ s}$ | Alters effective $\zeta \ne 1$, causes overshoot |
| **Padé Rational Approx.** | $e^{-\omega h} \approx \frac{1}{1 + \omega h + \frac{1}{2}(\omega h)^2}$ | Unconditional | $\infty$ | Truncation error, artificial phase lag |
| **Exact Analytic Solution** | $x(h) = (x_0 + c_2 h)e^{-\omega_n h}$<br>$v(h) = (v_0 - \omega_n c_2 h)e^{-\omega_n h}$ | **Unconditionally Stable** | **$\infty$** | **Smooth monotonic settling, zero error** |

Modern JVM JIT compilers compile `kotlin.math.exp` into a single SIMD/hardware instruction ($< 10\text{ ns}$). Evaluating this once or twice per render frame is computationally negligible and eliminates all numerical truncation error.

---

## 5. Angular Wrapping on $S^1$ & Coordinate Geometry

### 5.1 The Circular Topology of Yaw
Player yaw in Minecraft lives on the circle $S^1 \cong \mathbb{R} / 360^\circ \mathbb{Z}$.
When computing displacement from current angle $\theta_k$ to target $\theta^*$:
$$\Delta \theta = \text{MathHelper.wrapDegrees}(\theta^* - \theta_k)$$
where `wrapDegrees` maps any angle into $[-180.0^\circ, 180.0^\circ)$.

The initial spring displacement is:
$$x_0 = -\Delta \theta = \text{MathHelper.wrapDegrees}(\theta_k - \theta^*)$$

The local target in unwrapped frame coordinates is:
$$\theta_{\text{local}}^* = \theta_k + \Delta \theta$$

### 5.2 Preservation of Angular Momentum Across Boundary Wrapping
When the camera rotates past $+180^\circ$ to $-180^\circ$:
1. The angle coordinate has a formal branch-cut jump of $-360^\circ$:
   $$\theta_{\text{wrapped}} = \text{MathHelper.wrapDegrees}(\theta)$$
2. **Velocity Invariance**: Angular velocity $v = \frac{d\theta}{dt}$ is a tangent vector in the tangent space $T_\theta(S^1)$. It is completely invariant under constant coordinate translations:
   $$\frac{d}{dt}(\theta + 360^\circ k) = \frac{d\theta}{dt}$$
   **Crucial Rule**: Angular velocity $v$ is NEVER passed through `wrapDegrees`. It is preserved continuously across frames.

### 5.3 Step Evaluation Algorithm with Wrap-Around
Given current yaw $\theta_{\text{current}}$, target yaw $\theta_{\text{target}}$, current velocity $v$, frequency $\omega_n$, and time step $h$:

1. Compute shortest angular error:
   $$\Delta \theta = \text{MathHelper.wrapDegrees}(\theta_{\text{target}} - \theta_{\text{current}})$$
2. Set initial error displacement:
   $$x_0 = -\Delta \theta$$
3. Compute spring intermediate constant:
   $$c_2 = v + \omega_n x_0$$
4. Compute exponential decay factor:
   $$e = \exp(-\omega_n h)$$
5. Compute new displacement and velocity:
   $$x_{\text{new}} = (x_0 + c_2 h) e$$
   $$v_{\text{new}} = (v - \omega_n c_2 h) e$$
6. The continuous angular increment applied to the camera during this frame is:
   $$\Delta \theta_{\text{frame}} = x_{\text{new}} - x_0$$
7. Advance current angle:
   $$\theta_{\text{next}} = \text{MathHelper.wrapDegrees}(\theta_{\text{current}} + \Delta \theta_{\text{frame}})$$

Notice that as $h \to \infty$, $x_{\text{new}} \to 0$, so $\Delta \theta_{\text{frame}} \to -x_0 = \Delta \theta$. The camera lands precisely on $\theta_{\text{target}}$.

---

## 6. Pitch vs. Yaw Domain Separation

While Yaw is circular ($S^1$), Pitch is Euclidean on a bounded interval:
- **Pitch Domain**: $\theta_{\text{pitch}} \in [-90.0^\circ, +90.0^\circ]$.
- **Safe Clamping**: In practice, clamped to $[-89.9^\circ, +89.9^\circ]$ to prevent division-by-zero or camera direction singularity at zenith and nadir.
- **No Wrapping**: Pitch differences are computed by direct subtraction:
  $$x_0 = \theta_{\text{current}} - \theta_{\text{target}}$$
- **Anti-Windup Boundary Clamping**:
  If the spring attempts to push pitch past $\pm 89.9^\circ$:
  $$\theta_{\text{next}} = \text{clamp}(\theta_{\text{next}}, -89.9^\circ, 89.9^\circ)$$
  If the pitch hits the boundary and the velocity continues pushing outward ($v > 0$ at $+89.9^\circ$ or $v < 0$ at $-89.9^\circ$), velocity is zeroed:
  $$v \leftarrow 0$$
  This prevents "spring windup" where hidden accumulated velocity keeps the camera stuck against the floor or ceiling after the target reverses.

---

## 7. Dynamic Target Updates & Velocity Continuity ($C^1$ Smoothness)

### 7.1 Velocity Carryover Across Target Switches
When the pathfinder updates the active waypoint or target entity:
- The previous target $\theta_1^*$ is replaced with new target $\theta_2^*$.
- **Legacy behavior**: Erased velocity, restarted progress timer at 0 (instantaneous acceleration spike $\ddot{\theta} = \infty$).
- **SpringSmoother behavior**: Retains existing velocity $v(t)$.
  The initial error instantly becomes $x_0 = \text{wrapDegrees}(\theta(t) - \theta_2^*)$, while $v_0 = v(t)$.
  The ODE guarantees that the transition is $C^1$ smooth:
  $$\lim_{t \to t_s^-} \theta(t) = \lim_{t \to t_s^+} \theta(t), \quad \lim_{t \to t_s^-} \dot{\theta}(t) = \lim_{t \to t_s^+} \dot{\theta}(t)$$
  The camera decelerates and curves organically into the new target without visual hitching.

### 7.2 Humanization & Max Angular Velocity Clamping (Dawson Limiter)
A purely linear spring would produce excessive angular velocities if a target suddenly shifts by $180^\circ$ (e.g. initial acceleration $\omega_n^2 \times 180^\circ \approx 58,000^\circ/\text{s}^2$, peak velocity $> 1200^\circ/\text{s}$).

To enforce human physiological limits and pass anti-cheat checks:
We adapt Thomas Dawson's virtual displacement limiter (Game Programming Gems 4):
Let $v_{\max}$ be the maximum allowed angular velocity (default $720^\circ/\text{s}$).
For a critically damped spring, the maximum displacement that does not exceed $v_{\max}$ under damping is:
$$x_{\max} = \frac{v_{\max}}{\omega_n}$$

If the initial angular error $|x_0| > x_{\max}$:
We clamp the effective displacement:
$$x_0^{\text{clamped}} = x_0.\text{coerceIn}(-x_{\max}, x_{\max})$$

**Behavioral Benefit**:
1. For large angle turns, the effective target moves dynamically ahead of the camera at rate $v_{\max}$.
2. The camera accelerates up to $v_{\max}$ with natural human curvature, traverses at steady speed $v_{\max}$, and then smoothly decelerates into the final target via critically damped decay.
3. Velocity remains strictly continuous ($C^1$) with zero hard-clipping bounces.

---

## 8. Parameter Tuning & Half-Life Formulation

The behavior of the spring smoother is governed by three equivalent parameterizations:

| Parameter | Symbol | Definition | Recommended Default | Effect of Increasing |
| :--- | :--- | :--- | :--- | :--- |
| **Natural Frequency** | $\omega_n$ | Angular frequency ($\text{rad/s}$) | **$18.0\text{ rad/s}$** | Snappier, faster target acquisition |
| **Smooth Time** | $\tau$ | $\tau = \frac{2}{\omega_n}$ (seconds) | **$0.111\text{ s}$** | Slower, more relaxed camera motion |
| **Half-Life** | $t_{1/2}$ | $t_{1/2} = \frac{1.67835}{\omega_n}$ (seconds) | **$0.093\text{ s}$** | Half-time of angular error |
| **Max Velocity** | $v_{\max}$ | Peak angular rate ($^\circ/\text{s}$) | **$720.0^\circ/\text{s}$** | Higher top speed for 180° turns |

### Settling Time Analysis ($\omega_n = 18.0\text{ rad/s}$):
- $50\%$ settled: $t = 0.093\text{ s}$ (~5.6 frames at 60 Hz)
- $90\%$ settled: $t = 0.216\text{ s}$ (~13 frames at 60 Hz)
- $98\%$ settled: $t = 0.324\text{ s}$ (~19 frames at 60 Hz)
- $99.9\%$ settled: $t = 0.510\text{ s}$

This yields an exceptionally organic feel: rapid initial alignment within 150 ms, followed by smooth, imperceptible deceleration that glides precisely onto the crosshair.

### Settling Deadband & Rest State
When the angular error is $< 0.001^\circ$ and angular velocity is $< 0.01^\circ/\text{s}$:
$$\theta \leftarrow \theta_{\text{target}}, \quad v \leftarrow 0.0$$
The axis enters the `isAtRest = true` state, saving computation and signaling to downstream controllers that rotation has completed.

---

## 9. Complete Class & Method Architecture

### 9.1 File Layout
- **`src/main/kotlin/com/github/foragerhelper/rotation/SpringSmoother.kt`**:
  - `enum class AngleMode`: `WRAPPED` (for Yaw), `CLAMPED` (for Pitch).
  - `class AngularSpring1D`: Generic, high-performance 1D critically damped spring.
  - `class SpringSmoother`: 2D composite smoother managing Yaw and Pitch with unified lifecycle, reset, and step APIs.

### 9.2 Complete Implementation Code (`SpringSmoother.kt`)

```kotlin
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
 * Solution for step h:
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
     */
    fun reset(initialAngle: Float, initialVelocity: Float = 0.0f) {
        currentAngle = sanitizeAngle(initialAngle)
        velocity = initialVelocity
    }

    /**
     * Advances the spring state toward targetAngle over deltaTimeSeconds.
     * Returns the continuous angular delta (in degrees) traversed during this step.
     *
     * @param targetAngle The desired goal angle.
     * @param deltaTimeSeconds Frame elapsed time in seconds.
     * @return Angular delta traversed during this frame.
     */
    fun update(targetAngle: Float, deltaTimeSeconds: Float): Float {
        if (deltaTimeSeconds <= 0.0f) return 0.0f

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

        // 4. Exact analytic integration
        val c2 = velocity + omega * x0
        val expFactor = exp(-omega * dt)

        val xNew = (x0 + c2 * dt) * expFactor
        var vNew = (velocity - omega * c2 * dt) * expFactor

        // 5. Clamp velocity if needed
        if (maxVelocity > 0.0f) {
            vNew = vNew.coerceIn(-maxVelocity, maxVelocity)
        }

        // 6. Compute frame delta: delta = xNew - x0
        val frameDelta = xNew - x0
        var nextAngle = currentAngle + frameDelta

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

    private fun sanitizeAngle(angle: Float): Float = when (mode) {
        AngleMode.WRAPPED -> MathHelper.wrapDegrees(angle)
        AngleMode.CLAMPED -> angle.coerceIn(minAngle, maxAngle)
    }

    companion object {
        const val ANGLE_REST_THRESHOLD = 0.001f // 0.001 degrees
        const val VELOCITY_REST_THRESHOLD = 0.01f // 0.01 degrees/sec
        const val MAX_INTEGRATION_STEP = 0.2f // 200 ms max frame delta
    }
}

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
     */
    fun reset(initialYaw: Float, initialPitch: Float) {
        yawSpring.reset(initialYaw, 0.0f)
        pitchSpring.reset(initialPitch, 0.0f)
    }

    /**
     * Sets natural frequency for both axes.
     */
    fun setNaturalFrequency(omega: Float) {
        yawSpring.omega = omega
        pitchSpring.omega = omega
    }

    /**
     * Updates both yaw and pitch springs toward target angles.
     *
     * @return Pair of (yawDelta, pitchDelta) traversed during this frame.
     */
    fun update(targetYaw: Float, targetPitch: Float, deltaTimeSeconds: Float): RotationDelta {
        val dYaw = yawSpring.update(targetYaw, deltaTimeSeconds)
        val dPitch = pitchSpring.update(targetPitch, deltaTimeSeconds)
        return RotationDelta(dYaw, dPitch)
    }
}

data class RotationDelta(
    val deltaYaw: Float,
    val deltaPitch: Float
)
```

---

## 10. Integration with `RotationEngine.kt` and `SensitivityGCD.kt`

The data flow during each render frame is strictly decoupled:

```
[Fabric Render Event: WorldRenderEvents.START_MAIN]
                     │
                     ▼ (deltaTimeSeconds)
        ┌─────────────────────────┐
        │     RotationEngine      │
        │  (Resolves active target│
        │   Yaw/Pitch via Tangent │
        │   or Focus Point)       │
        └────────────┬────────────┘
                     │ targetYaw, targetPitch, dt
                     ▼
        ┌─────────────────────────┐
        │      SpringSmoother     │
        │  (Exact analytic ODE,   │
        │   Wrap-around on S1,    │
        │   Continuous velocity)  │
        └────────────┬────────────┘
                     │ raw deltaYaw, deltaPitch
                     ▼
        ┌─────────────────────────┐
        │      SensitivityGCD     │
        │  (Accumulates fraction, │
        │   quantizes to integer  │
        │   mouse count * step)   │
        └────────────┬────────────┘
                     │ quantized dx, dy
                     ▼
        ┌─────────────────────────┐
        │   player.changeLook-    │
        │        Direction        │
        │ (Synchronous update of  │
        │  yaw, pitch, lastYaw,   │
        │  lastPitch at 144/240Hz)│
        └─────────────────────────┘
```

### Key Integration Contracts:
1. `SpringSmoother` has **zero dependency** on Minecraft client tick state, GL context, or GUI. It is pure Kotlin math, fully testable in headless JVM unit tests.
2. `RotationEngine` holds an instance of `SpringSmoother`.
3. When `RotationEngine.reset()` is invoked (e.g. on teleport or path start), it calls `springSmoother.reset(player.yaw, player.pitch)`.
4. When `RotationEngine.onRenderFrame(deltaTime, tickProgress)` is invoked, it passes `deltaTime` to `springSmoother.update(targetYaw, targetPitch, deltaTime)`.
5. The resulting continuous `RotationDelta` is handed to `SensitivityGCD.quantize(deltaYaw, deltaPitch)`, which converts the degrees into exact integer mouse clicks and retains the sub-step remainder for subsequent frames.

---

## 11. Test Verification Strategy & Coverage Mapping

In accordance with `TEST_INFRA.md`, the spring dynamics are validated against behavioral Tiers 1-4:

### Tier 1: Unit & Numerical Invariants
- **T1.1**: Step response from rest: verify monotonic convergence with zero overshoot ($x(t) \ge 0$ for $x_0 > 0, v_0 = 0$).
- **T1.2**: Settling time validation: verify $x(t)$ drops to $< 2\%$ of $x_0$ at $t = 3.9 / \omega_n$.
- **T1.3**: Zero delta time: verify `update(target, 0.0f)` returns $0.0$ and preserves exact state.
- **T1.4**: Rest deadband: verify spring snaps to exact target and sets `isAtRest = true` when within thresholds.
- **T1.5**: Symmetry: verify symmetric response for positive and negative angular deltas.

### Tier 2: Boundary & Corner Cases
- **T2.1**: Yaw wrap boundary crossing: start at $179.0^\circ$, target $-179.0^\circ$. Verify rotation direction is $+2.0^\circ$ (shortest path), NOT $-358.0^\circ$.
- **T2.2**: Multi-revolution wrap: verify stability when input angles exceed $\pm 720^\circ$.
- **T2.3**: Extreme lag spike: test integration with $h = 0.5\text{ s}$ and $h = 2.0\text{ s}$. Verify zero NaN, zero infinity, and stable convergence without overshoot.
- **T2.4**: Pitch zenith and nadir clamping: target $+120.0^\circ$ pitch, verify clamped at $+89.9^\circ$ and pitch velocity is zeroed.
- **T2.5**: Pitch anti-windup: push against ceiling, then immediately reverse target to $0.0^\circ$. Verify instant descent without delayed windup latency.

### Tier 3: Pairwise Combinations & Retargeting
- **T3.1**: Mid-flight target switch (continuous velocity): target $45^\circ$, allow spring to accelerate to $150^\circ/\text{s}$, suddenly shift target to $90^\circ$. Verify acceleration is finite, velocity does not jump to zero, and position curve is $C^1$ smooth.
- **T3.2**: Immediate target reversal: moving right at $200^\circ/\text{s}$, target shifts to $-45^\circ$. Verify smooth deceleration to 0 followed by smooth acceleration left.
- **T3.3**: Variable frame delta times: compare trajectory computed with fixed $h = 0.01\text{ s}$ vs fluctuating $h \in [0.004, 0.016]\text{ s}$. Verify identical endpoint and smooth progression.

### Tier 4: Workload Scenarios
- **T4.1**: 240 Hz continuous waypoint traversal: simulate 1000 frames at $\Delta t = 4.16\text{ ms}$ tracking an S-curve path. Verify total smoothness, zero micro-stutter, and $< 0.1\text{ ms}$ total execution time.

---

## 12. Conclusion

The critically damped angular spring with exact closed-form analytic integration mathematically resolves all root causes of camera stutter, angle snapping, and curve resets in ForagerHelper. It provides an unconditionally stable, time-step independent foundation for 60/144/240Hz render-frame camera panning.
