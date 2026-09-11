# Milestone M1 Code Quality & Interface Conformance Review Report

**Reviewer**: `teamwork_preview_reviewer_m1_1`  
**Working Directory**: `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_reviewer_m1_1`  
**Date**: 2026-09-11  
**Target Milestone**: M1 (R1 - Standalone Humanized Rotation Engine)  
**Target Code**: `src/main/kotlin/com/github/foragerhelper/rotation/`  

---

## 1. Review Summary

**Verdict**: **REQUEST_CHANGES**

The M1 Rotation Engine demonstrates excellent foundational architecture, mathematical modeling, and contract adherence. The critically-damped spring formulation in `SpringSmoother.kt` is unconditionally stable, the cubic Hermite focus blending in `RotationEngine.kt` is smooth and well-behaved, and the Fabric `WorldRenderEvents.START_MAIN` hook cleanly eliminates tick-fighting by incrementing `yaw` and `lastYaw` synchronously.

However, independent test execution of the full test suite (`gradlew.bat test`) **FAILS with 2 failing tests** out of 33 (`33 tests completed, 2 failed`). A critical logic bug in `SensitivityGCD.kt` causes the fractional pitch remainder to explode when approaching the pitch limits ($\pm 90^\circ$), resulting in severe integrator windup and camera stall when attempting to reverse direction. In accordance with the Reviewer Protocol, this failure requires a verdict of `REQUEST_CHANGES`.

---

## 2. Findings

### [Critical] Finding 1: Pitch Boundary Remainder Explosion & Integrator Windup
- **Location**: `src/main/kotlin/com/github/foragerhelper/rotation/SensitivityGCD.kt:98-122`
- **What**: When the camera approaches vertical pitch boundaries ($\pm 90^\circ$), the fractional remainder accumulator in `SensitivityGCD` explodes to values far exceeding the theoretical bound ($|R| \le 0.5 \times \text{step}$). In tests, the remainder reached $0.55^\circ$, which is over $7\times$ the maximum allowed remainder of $0.075^\circ$.
- **Why**:
  In lines 99-111, when `countsPitch` would push pitch past $\pm 90^\circ$, `countsPitch` is truncated to `(allowedDelta / step).toInt()` and line 105 sets `pitchRemainder = 0.0`.
  However, in line 119:
  ```kotlin
  if (currentPitch == null || (currentPitch in -89.99f..89.99f)) {
      pitchRemainder = totalPitch - (countsPitch.toDouble() * step)
  }
  ```
  When `currentPitch` is near the boundary (e.g. $89.5^\circ$), `89.5f in -89.99f..89.99f` evaluates to `true`. Line 120 immediately executes and overwrites `pitchRemainder` with `totalPitch - countsPitch * step`. Because `countsPitch` was truncated to prevent over-rotation, the discarded rotation that should have been eliminated is stuffed into `pitchRemainder`!
- **Impact**: Fails `SensitivityGCDAdversarialTest.testPitchBoundaryRemainderExplosionStressTest`.

---

### [Critical] Finding 2: Camera Upward Reversal Stall at Pitch Boundary
- **Location**: `src/main/kotlin/com/github/foragerhelper/rotation/SensitivityGCD.kt:80-96`
- **What**: When pitch is near the downward limit (e.g. $89.89995^\circ$) and the user or pathfinder immediately requests an upward rotation (`desiredDeltaPitch = -1.0^\circ`), the engine emits `countsPitch = 0`, failing to move upward on frame 1.
- **Why**: Because of Finding 1, `pitchRemainder` has accumulated a large positive value ($+0.55^\circ$ or higher). When `desiredDeltaPitch = -1.0` or `-0.5` arrives, `totalPitch = desiredDeltaPitch + pitchRemainder` cancels out to a near-zero or positive value (e.g., $-0.5 + 0.55 = +0.05$). `nearestCount(+0.05, 0.15)` produces `0`. The camera stalls against the boundary until the positive remainder is slowly depleted over multiple frames.
- **Impact**: Fails `SensitivityGCDAdversarialTest.testPitchLimitsNeverExceededWhenApproachingBoundaries`. Violates acceptance criteria requiring responsive, fluid head orientation.

---

### [Minor] Finding 3: Missing NaN / Infinite Guards in `SpringSmoother.update`
- **Location**: `src/main/kotlin/com/github/foragerhelper/rotation/SpringSmoother.kt:74-80`
- **What**: If `deltaTimeSeconds` or `targetAngle` is `Float.NaN`, `deltaTimeSeconds <= 0.0f` evaluates to `false`. In Kotlin, `Float.NaN.coerceAtMost(0.2f)` returns `NaN`. This propagates `NaN` into `exp(-omega * dt)`, permanently poisoning `currentAngle` and `velocity` with `NaN`.
- **Suggestion**: Add explicit NaN/Infinite checks:
  ```kotlin
  if (deltaTimeSeconds.isNaN() || deltaTimeSeconds <= 0.0f) return 0.0f
  if (targetAngle.isNaN() || targetAngle.isInfinite()) return 0.0f
  ```

---

### [Minor] Finding 4: Windows Hebrew Username Environment Dependency
- **Location**: `build.gradle.kts:44-48` and test execution environment.
- **What**: Spawning Gradle test worker processes under Windows Hebrew codepage (Windows-1255) fails with `ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain` because the Hebrew user path `C:\Users\משתמש` gets garbled when reading worker argsfiles if `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8` is not present in the process environment.
- **Suggestion**: Ensure all documentation and build scripts explicitly enforce `set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8`.

---

## 3. Verified Claims

| Claim from Worker M1 | Verification Method | Status | Details |
|---|---|---|---|
| `SpringSmoother.kt` implements analytic critically-damped ODE | Inspected math in `AngularSpring1D` | **PASS** | Exact closed-form solution $x(h) = (x_0 + c_2 h)e^{-\omega h}$ is A-stable and time-step independent. |
| Dawson peak velocity limiting | Inspected `x0.coerceIn(-maxDisplacement, maxDisplacement)` | **PASS** | Continuous $C^1$ velocity limiting without discontinuous acceleration jumps. |
| Continuous angle wrapping across $\pm 180^\circ$ | Inspected wrap logic and tested $179^\circ \to -179^\circ$ | **PASS** | Uses shortest path arc, velocity is continuous across branch cut. |
| Bit-exact sensitivity GCD constants | Compared with vanilla Minecraft 1.21.11 | **PASS** | Exact match: $f = s \times 0.6000000238418579 + 0.20000000298023224$, step $= f^3 \times 1.2$. |
| Synchronous look direction without tick fighting | Inspected `RotationEngine.kt` render hook and `player.changeLookDirection` | **PASS** | Modifies `yaw` and `lastYaw` by exact same delta on `WorldRenderEvents.START_MAIN`. |
| Hermite cubic focus blending | Inspected `resolveTargetAngles` in `RotationEngine.kt` | **PASS** | Smoothstep $w(u) = 3u^2 - 2u^3$ with zero derivatives at endpoints, shortest-arc angular wrapping. |
| Interface Conformance | Compared with `PROJECT.md:78-93` | **PASS** | Implements all required methods and properties with companion singleton delegation. |
| Test suite passes with 0 failures | Executed `gradlew.bat test` | **FAIL** | 33 tests executed, 2 failed in `SensitivityGCDAdversarialTest`. |

---

## 4. Suggested Worker Fix for Finding 1 & 2

In `SensitivityGCD.kt:98-125`, track whether pitch was clamped to boundary:

```kotlin
        // --- Pitch Boundary Over-Rotation Clamping ---
        var wasPitchBoundaryClamped = false
        if (currentPitch != null && countsPitch != 0) {
            val potentialPitchDelta = countsToDelta(countsPitch, safeSens, isSpyglass)
            val projectedPitch = currentPitch + potentialPitchDelta
            if (projectedPitch > 90.0f) {
                val allowedDelta = (90.0f - currentPitch).toDouble()
                countsPitch = (allowedDelta / step).toInt().coerceAtLeast(0)
                pitchRemainder = 0.0
                wasPitchBoundaryClamped = true
            } else if (projectedPitch < -90.0f) {
                val allowedDelta = (-90.0f - currentPitch).toDouble()
                countsPitch = (allowedDelta / step).toInt().coerceAtMost(0)
                pitchRemainder = 0.0
                wasPitchBoundaryClamped = true
            }
        }

        // --- Compute Exact Applied Deltas (Bit-Exact with Vanilla) ---
        val appliedDeltaYaw = countsToDelta(countsYaw, safeSens, isSpyglass)
        val appliedDeltaPitch = countsToDelta(countsPitch, safeSens, isSpyglass)

        // --- Update Remainders for Next Frame ---
        yawRemainder = totalYaw - (countsYaw.toDouble() * step)
        if (!wasPitchBoundaryClamped) {
            pitchRemainder = totalPitch - (countsPitch.toDouble() * step)
        }
```

---

## 5. Conclusion

Because Gradle test execution fails with 2 test failures in `SensitivityGCDAdversarialTest` due to pitch remainder explosion and upward stall at boundaries, the work cannot be approved in its current state. 

**Verdict**: **REQUEST_CHANGES**
