# Technical Analysis Report: Defensive Input Sanitization for SpringSmoother.kt

**Author**: teamwork_preview_explorer_m1_rem_2  
**Target File**: `src/main/kotlin/com/github/foragerhelper/rotation/SpringSmoother.kt`  
**Referenced Work**: Challenger 1 Handoff (`.agents/teamwork_preview_challenger_m1_1/handoff.md`), `RotationEngine.kt`  
**Date**: 2026-09-11  

---

## 1. Executive Summary

During adversarial stress testing of the critically damped rotation smoothing engine (`SpringSmoother.kt`), Challenger 1 identified a numerical poisoning vulnerability: passing `NaN` or `Infinity` for either `targetAngle` or `deltaTimeSeconds` permanently corrupts the internal state variables (`currentAngle` and `velocity`) into `NaN`. Due to the nature of IEEE 754 floating-point arithmetic and closed-form analytic spring integration, subsequent calls with 100% valid numerical inputs continue to evaluate to `NaN`. This locks the camera smoother into an unrecoverable "latched" failure state until an explicit `reset()` is invoked.

This investigation formulates the exact defensive input sanitization architecture for `SpringSmoother.kt` and `AngularSpring1D`. The formulation guards against `NaN` and `Infinity` in both `AngularSpring1D.update()` and `SpringSmoother.update()`, guarantees independent axis error isolation, and implements an automatic self-healing unlatching mechanism.

---

## 2. Forensic Analysis & Root Cause

### 2.1 IEEE 754 Comparison & Arithmetic Semantics
In IEEE 754 floating-point standard:
1. **Unordered Comparisons**: Any comparison operator (`<`, `<=`, `>`, `>=`, `==`) between `Float.NaN` and any float (including `NaN` itself) evaluates to `false`.
   - In unpatched `AngularSpring1D.update()`:
     ```kotlin
     if (deltaTimeSeconds <= 0.0f) return 0.0f
     ```
     When `deltaTimeSeconds = Float.NaN`, `Float.NaN <= 0.0f` evaluates to `false`. The non-positive delta time check is bypassed.
2. **Coerce Failure on NaN**:
   - In Kotlin stdlib:
     ```kotlin
     fun Float.coerceAtMost(maximumValue: Float): Float = if (this > maximumValue) maximumValue else this
     ```
     When `this` is `NaN`, `NaN > maximumValue` is `false`, so `dt` remains `Float.NaN`.
3. **Modulo with Infinity**:
   - For `AngleMode.WRAPPED`, `sanitizeAngle` executes `MathHelper.wrapDegrees(angle)`:
     ```java
     float f = degrees % 360.0F;
     ```
     Under IEEE 754, `Infinity % 360.0f` produces `Float.NaN`.
4. **Coerce Failure in Clamped Mode**:
   - For `AngleMode.CLAMPED`, `Float.NaN.coerceIn(minAngle, maxAngle)` returns `Float.NaN` because `NaN < minAngle` is `false` and `NaN > maxAngle` is `false`.

### 2.2 Mechanism of State Latching (Permanent Poisoning)
The spring's state is preserved across frames by two private member variables:
```kotlin
var currentAngle: Float = 0.0f
var velocity: Float = 0.0f
```
When `targetAngle` or `dt` is `NaN`, the closed-form integration formulas execute:
$$x_0 = \text{wrapDegrees}(\text{currentAngle} - \text{target}) = \text{NaN}$$
$$c_2 = \text{velocity} + \omega \cdot x_0 = \text{NaN}$$
$$x_{\text{new}} = (x_0 + c_2 \cdot dt) \cdot e^{-\omega \cdot dt} = \text{NaN}$$
$$v_{\text{new}} = (\text{velocity} - \omega \cdot c_2 \cdot dt) \cdot e^{-\omega \cdot dt} = \text{NaN}$$
$$\text{currentAngle} \leftarrow \text{NaN},\quad \text{velocity} \leftarrow \text{NaN}$$

On the following frame $t+1$, even if a normal, valid target (e.g. `20.0f`) and delta time (e.g. `0.016f`) are passed:
$$x_0 = \text{wrapDegrees}(\text{currentAngle} - \text{target}) = \text{wrapDegrees}(\text{NaN} - 20.0\text{f}) = \text{NaN}$$
The corrupted state feeds back into itself. The spring **latches** into `NaN`, freezing camera rotation permanently.

### 2.3 Blast Radius in `DefaultRotationEngine`
In `DefaultRotationEngine.kt`:
1. `resolveTargetAngles(eyePos)` computes target angles via `atan2(-dx, dz)`. If `eyePos` and `target` coincide (`dx=dz=0`) with degenerate inputs, or if player eye position is momentarily uninitialized/corrupted, `targetYaw` and `targetPitch` become `NaN`.
2. `SpringSmoother.update(targetYaw, targetPitch, dt)` receives these values.
3. Once poisoned, `springSmoother.update` produces `delta = RotationDelta(NaN, NaN)`.
4. `SensitivityGCD.quantize` receives `NaN`, which cascades into player yaw and pitch, completely breaking client camera control.

---

## 3. Formulated Defensive Architecture

To achieve complete mathematical robustness without latching, the fix must address four critical requirements:
1. **Zero-Delta Rejection**: Any step with invalid time step or invalid target must return `0.0f` (or `RotationDelta(0.0f, 0.0f)`) without altering current angle or velocity.
2. **State Immutability on Corruption**: A transient corrupted input in frame $N$ must not alter internal state, ensuring frame $N+1$ proceeds normally.
3. **Independent Axis Decoupling**: In `SpringSmoother.update()`, if `targetYaw` is `NaN` but `targetPitch` is valid, `yawSpring` must hold static (`deltaYaw = 0.0f`) while `pitchSpring` smoothly tracks `targetPitch`.
4. **Self-Healing Unlatching**: If `currentAngle` or `velocity` ever becomes non-finite (e.g. via reflective modification, uninitialized memory, or an invalid call to `reset()`), `update()` must automatically unlatch and sanitize the state back to finite values rather than propagating `NaN`.

### 3.1 Hardened Code Changes

#### A. In `AngularSpring1D.reset`:
```kotlin
fun reset(initialAngle: Float, initialVelocity: Float = 0.0f) {
    val safeAngle = if (initialAngle.isFinite()) initialAngle else 0.0f
    val safeVelocity = if (initialVelocity.isFinite()) initialVelocity else 0.0f
    currentAngle = sanitizeAngle(safeAngle)
    velocity = safeVelocity
}
```
*Rationale*: Prevents seeding the spring with `NaN` or `Infinity` upon initialization or reset.

#### B. In `AngularSpring1D.update`:
```kotlin
fun update(targetAngle: Float, deltaTimeSeconds: Float): Float {
    // 1. Guard delta time against non-positive, NaN, and Infinite values
    if (deltaTimeSeconds.isNaN() || deltaTimeSeconds.isInfinite() || deltaTimeSeconds <= 0.0f) {
        return 0.0f
    }

    // 2. Guard target angle against NaN and Infinity (reject without state modification)
    if (targetAngle.isNaN() || targetAngle.isInfinite()) {
        return 0.0f
    }

    // 3. Self-healing unlatching: restore finite state if corrupted externally
    if (!currentAngle.isFinite()) currentAngle = 0.0f
    if (!velocity.isFinite()) velocity = 0.0f

    // 4. Guard against massive frame freeze / alt-tab spikes
    val dt = deltaTimeSeconds.coerceAtMost(MAX_INTEGRATION_STEP)

    val target = sanitizeAngle(targetAngle)
    // ... remainder of exact integration remains identical ...
```
*Rationale*:
- If `deltaTimeSeconds` is invalid, zero time elapsed: return `0.0f`, retain state.
- If `targetAngle` is invalid: return `0.0f`, retain state.
- If `currentAngle` or `velocity` was corrupted, self-healing restores `0.0f`, allowing immediate resumption without latching.

#### C. In `AngularSpring1D.sanitizeAngle`:
```kotlin
private fun sanitizeAngle(angle: Float): Float {
    if (!angle.isFinite()) {
        return if (currentAngle.isFinite()) currentAngle else 0.0f
    }
    return when (mode) {
        AngleMode.WRAPPED -> MathHelper.wrapDegrees(angle)
        AngleMode.CLAMPED -> angle.coerceIn(minAngle, maxAngle)
    }
}
```
*Rationale*: Protects `sanitizeAngle` if invoked with non-finite values. Unlike Challenger 1's snippet (which unconditionally returned `currentAngle` even if `currentAngle` was `NaN`), this checks `currentAngle.isFinite()` and falls back to `0.0f`, eliminating circular NaN retention.

#### D. In `SpringSmoother.reset` and `setNaturalFrequency`:
```kotlin
fun reset(initialYaw: Float, initialPitch: Float) {
    val safeYaw = if (initialYaw.isFinite()) initialYaw else 0.0f
    val safePitch = if (initialPitch.isFinite()) initialPitch else 0.0f
    yawSpring.reset(safeYaw, 0.0f)
    pitchSpring.reset(safePitch, 0.0f)
}

fun setNaturalFrequency(omega: Float) {
    if (omega.isFinite() && omega >= 0.0f) {
        yawSpring.omega = omega
        pitchSpring.omega = omega
    }
}
```

#### E. In `SpringSmoother.update`:
```kotlin
fun update(targetYaw: Float, targetPitch: Float, deltaTimeSeconds: Float): RotationDelta {
    if (deltaTimeSeconds.isNaN() || deltaTimeSeconds.isInfinite() || deltaTimeSeconds <= 0.0f) {
        return RotationDelta(0.0f, 0.0f)
    }
    val dYaw = yawSpring.update(targetYaw, deltaTimeSeconds)
    val dPitch = pitchSpring.update(targetPitch, deltaTimeSeconds)
    return RotationDelta(dYaw, dPitch)
}
```
*Rationale*: Short-circuits invalid delta times immediately. Delegates `targetYaw` and `targetPitch` to their respective springs, ensuring decoupled per-axis handling: a corrupted yaw target will not prevent a valid pitch target from updating.

---

## 4. Verification Plan & Test Suite Alignment

In `src/test/kotlin/com/github/foragerhelper/rotation/SpringSmootherAdversarialTest.kt`, Challenger 1 implemented tests that assert the *presence* of the NaN poisoning bug (e.g. `assertTrue(deltaNaN.isNaN())`).

Once Worker M1 applies the defensive sanitization patch, those tests must be updated from bug-demonstration tests to defensive-assertion tests:

### 4.1 Updated Test Cases

```kotlin
@Test
fun testNaNTargetSafelyRejectedWithoutLatching() {
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
fun testNaNDeltaTimeSafelyRejectedWithoutLatching() {
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
fun testInfinityTargetSafelyRejectedWithoutLatching() {
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
fun testSpringSmootherIndependentAxisNaNRejection() {
    val smoother = SpringSmoother(omega = 18.0f)
    smoother.reset(initialYaw = 0.0f, initialPitch = 0.0f)

    // Frame 1: targetYaw is NaN, targetPitch is valid (20.0f)
    val delta1 = smoother.update(targetYaw = Float.NaN, targetPitch = 20.0f, deltaTimeSeconds = 0.016f)
    assertEquals(0.0f, delta1.deltaYaw, epsilon, "Yaw delta must be 0.0f when targetYaw is NaN")
    assertTrue(delta1.deltaPitch > 0.0f, "Pitch delta must advance toward 20.0f")
    assertEquals(0.0f, smoother.currentYaw, epsilon, "Yaw must remain unchanged")
    assertTrue(smoother.currentPitch > 0.0f, "Pitch must have advanced")

    // Frame 2: targetYaw is valid (30.0f), targetPitch is NaN
    val prevPitch = smoother.currentPitch
    val delta2 = smoother.update(targetYaw = 30.0f, targetPitch = Float.NaN, deltaTimeSeconds = 0.016f)
    assertTrue(delta2.deltaYaw > 0.0f, "Yaw delta must advance toward 30.0f")
    assertEquals(0.0f, delta2.deltaPitch, epsilon, "Pitch delta must be 0.0f when targetPitch is NaN")
    assertTrue(smoother.currentYaw > 0.0f, "Yaw must have advanced")
    assertEquals(prevPitch, smoother.currentPitch, epsilon, "Pitch must remain unchanged")

    // Frame 3: deltaTime is NaN
    val delta3 = smoother.update(targetYaw = 50.0f, targetPitch = 50.0f, deltaTimeSeconds = Float.NaN)
    assertEquals(0.0f, delta3.deltaYaw, epsilon, "Yaw delta must be 0.0f when dt is NaN")
    assertEquals(0.0f, delta3.deltaPitch, epsilon, "Pitch delta must be 0.0f when dt is NaN")
}
```

---

## 5. Artifact Summary

The complete proposed replacement and unified patch are placed in this agent's folder:
- Proposed implementation: `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m1_rem_2\proposed_SpringSmoother.kt`
- Git patch: `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m1_rem_2\SpringSmoother_input_sanitization.patch`
- Test cases and logic: detailed in this report and `handoff.md`.
