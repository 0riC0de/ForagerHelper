# Adversarial Stress Testing Report: SpringSmoother (Milestone M1)

**Agent**: teamwork_preview_challenger_m1_1  
**Target File**: `src/main/kotlin/com/github/foragerhelper/rotation/SpringSmoother.kt`  
**Test Suite**: `src/test/kotlin/com/github/foragerhelper/rotation/SpringSmootherAdversarialTest.kt` (22 test cases)  
**Date**: 2026-09-11  

---

## Challenge Summary

**Overall Risk Assessment**: **MEDIUM to HIGH**  
**Verdict**: **REJECT** (pending NaN / Infinity state-poisoning remediation)

While `SpringSmoother.kt` demonstrates mathematical precision, unconditional A-stability, correct shortest-path circular wrapping ($179.9^\circ \to -179.9^\circ$ traversing $+0.2^\circ$, never $359.8^\circ$), clean multi-revolution handling ($> 3600^\circ$), and continuous deceleration during sudden high-speed reversals, it exhibits a critical numerical state-poisoning flaw:
**When fed `Float.NaN` or `Float.POSITIVE_INFINITY` / `Float.NEGATIVE_INFINITY` (either as `targetAngle` or `deltaTimeSeconds`), `AngularSpring1D` permanently latches into a corrupted NaN state, causing all subsequent frames—even with valid numeric inputs—to return NaN and freeze camera control indefinitely.**

---

## Challenges

### [High] Challenge 1: Permanent NaN / Infinity State Poisoning & Recovery Failure

- **Assumption Challenged**: Implicit assumption that inputs to `AngularSpring1D.update()` will always be finite IEEE 754 floats, or that invalid inputs degrade gracefully without corrupting internal state.
- **Attack Scenario**:
  1. A navigation target, path tangent, or external mod produces a `Float.NaN` (e.g. eye position coincides with waypoint, resulting in $0/0$ or vector length $0$).
  2. `spring.update(targetAngle = Float.NaN, dt)` is called.
  3. In `sanitizeAngle`, `MathHelper.wrapDegrees(Float.NaN)` evaluates to `Float.NaN`.
  4. In step 1-6, `x0`, `c2`, `xNew`, `vNew`, and `frameDelta` become `Float.NaN`.
  5. `currentAngle` and `velocity` are assigned `Float.NaN`.
  6. On all subsequent frames with valid targets (e.g. `targetAngle = 45.0f`), `x0 = currentAngle - target = Float.NaN - 45.0f = Float.NaN`. The spring remains permanently in NaN state and never moves again unless `reset()` is explicitly invoked.
- **Secondary Attack Scenario (NaN DeltaTime)**:
  `if (deltaTimeSeconds <= 0.0f) return 0.0f` does NOT guard against `Float.NaN` because in IEEE 754, `Float.NaN <= 0.0f` evaluates to `false`. `dt` becomes `Float.NaN`, exponent factor becomes `Float.NaN`, and state is poisoned.
- **Tertiary Attack Scenario (Infinite Target)**:
  `targetAngle = Float.POSITIVE_INFINITY` causes `Infinity % 360.0F` in `MathHelper.wrapDegrees` to yield `Float.NaN`, triggering the same permanent latch.
- **Blast Radius**:
  The entire camera orientation engine (`RotationEngine`) permanently freezes in place. In `DefaultRotationEngine.onRenderFrame`, `springSmoother` emits `RotationDelta(NaN, NaN)`, `SensitivityGCD` emits 0 counts and NaN remainder, and the player loses all automated and manual camera control until the mod or client is restarted.
- **Mitigation**:
  Add defensive validation guards at the entry of `AngularSpring1D.update()`:
  ```kotlin
  if (deltaTimeSeconds.isNaN() || deltaTimeSeconds <= 0.0f) return 0.0f
  if (targetAngle.isNaN() || targetAngle.isInfinite()) return 0.0f
  ```
  And in `sanitizeAngle(angle: Float)`:
  ```kotlin
  private fun sanitizeAngle(angle: Float): Float {
      if (angle.isNaN() || angle.isInfinite()) return currentAngle
      return when (mode) {
          AngleMode.WRAPPED -> MathHelper.wrapDegrees(angle)
          AngleMode.CLAMPED -> angle.coerceIn(minAngle, maxAngle)
      }
  }
  ```

---

## Stress Test Results

A dedicated 22-test adversarial suite was executed via Gradle (`gradlew.bat test --tests com.github.foragerhelper.rotation.SpringSmootherAdversarialTest`):

| # | Stress Scenario | Expected Behavior | Actual Behavior | Result |
|---|-----------------|-------------------|-----------------|:------:|
| 1 | $\Delta t = 0.0\text{s}$ (and $-0.0\text{s}$) | Delta = 0, angle and velocity strictly unchanged | Delta = 0.0, state preserved over 50 iterations | **PASS** |
| 2 | Negative $\Delta t$ ($-1\text{s}, -10^{-6}\text{s}$) | Delta = 0, negative dt rejected | Delta = 0.0, state untouched | **PASS** |
| 3 | Huge $\Delta t = 10.0\text{s}$ (frame freeze / lag) | Coerced to $\le 0.2\text{s}$, stable, zero overshoot | Coerced to 0.2s, velocity $\le 720^\circ/\text{s}$, settled at $90^\circ$ | **PASS** |
| 4 | Microsecond $\Delta t = 10^{-6}\text{s}$ (100k steps) | Smooth numerical accumulation, monotonic progress | Advanced $>15^\circ$ in 0.1s, no underflow stall | **PASS** |
| 5 | Frame time jitter ($10\mu\text{s} \leftrightarrow 0.2\text{s}$) | Zero energy divergence, stable convergence | Converged to $60.0^\circ$ with zero overshoot | **PASS** |
| 6 | Angle wrap: $179.9^\circ \to -179.9^\circ$ | Traverses shortest $+0.2^\circ$, never $-359.8^\circ$ | Traversed $+0.2^\circ$, all frame deltas $>0$ | **PASS** |
| 7 | Angle wrap: $-179.9^\circ \to +179.9^\circ$ | Traverses shortest $-0.2^\circ$, never $+359.8^\circ$ | Traversed $-0.2^\circ$, all frame deltas $<0$ | **PASS** |
| 8 | Exact branch cut $180.0^\circ \to -180.0^\circ$ | Canonical representation, zero delta, at rest | Initial angle canonically wrapped to $-180^\circ$, delta = 0 | **PASS** |
| 9 | Multi-revolution target ($3645.0^\circ$) | Traverses shortest $45^\circ$, no 10-turn spinning | Settled at $45.0^\circ$, total path $<50^\circ$ | **PASS** |
| 10 | Negative multi-revolution ($-3645.0^\circ$) | Traverses shortest $-45^\circ$ | Settled at $-45.0^\circ$, total path $<50^\circ$ | **PASS** |
| 11 | 100 revolutions target ($36030.0^\circ$) | Resolves cleanly to $30.0^\circ$ | Settled at $30.0^\circ$ | **PASS** |
| 12 | Multi-revolution reset ($7230.0^\circ$, $-7230.0^\circ$) | Sanitized to $\pm 30.0^\circ$ in `reset()` | Sanitized to $30.0^\circ$ and $-30.0^\circ$ | **PASS** |
| 13 | Sudden reversal at peak speed ($v \approx 348^\circ/\text{s}$) | Continuous physical deceleration, bounded $v \le 720^\circ/\text{s}$ | Smoothly passed through zero, reversed to $-90^\circ$ | **PASS** |
| 14 | 144Hz high-frequency chatter ($\pm 30^\circ$, 500 frames) | Velocity bounded, no resonance explosion | Velocity strictly bounded $\le 720^\circ/\text{s}$ | **PASS** |
| 15 | Pitch hard clamping ($+150^\circ, -150^\circ$) | Clamped to $\pm 89.9^\circ$, velocity zeroed | Clamped to $\pm 89.9^\circ$, anti-windup active | **PASS** |
| 16 | Immediate pitch reversal from pinned boundary | Upward movement on frame 1 without windup latency | Emitted negative delta immediately on frame 1 | **PASS** |
| 17 | Pitch reset out-of-bounds ($120^\circ, -120^\circ$) | Clamped to $\pm 89.9^\circ$ in `reset()` | Clamped to $89.9^\circ$ and $-89.9^\circ$ | **PASS** |
| 18 | Composite 2D Yaw wrap + Pitch clamp sync | Simultaneous wrap and clamp maintain invariants | Yaw wrapped $+0.2^\circ$, Pitch clamped to $89.9^\circ$ | **PASS** |
| 19 | Process survival under NaN / Infinity inputs | Process survives without JVM crash / unhandled exception | Survived without JVM crash | **PASS** |
| 20 | **TargetAngle NaN state poisoning** | Invalid input rejected without poisoning state | **FAILED**: `currentAngle` and `velocity` become NaN; subsequent valid updates remain permanently NaN | **VULNERABILITY** |
| 21 | **DeltaTime NaN state poisoning** | Invalid dt rejected by non-positive guard | **FAILED**: `NaN <= 0.0f` is false; dt becomes NaN; state permanently poisoned | **VULNERABILITY** |
| 22 | **Infinite Target state poisoning** | `Infinity` sanitized or rejected | **FAILED**: `Infinity % 360 = NaN`; state permanently poisoned | **VULNERABILITY** |

---

## Unchallenged Areas

- **In-Game Rendering & Graphical Pipeline**: Verified offline in headless Gradle unit tests using the simulation harness. Active graphical frame presentation on monitor refresh rates will be verified during milestone M4/M5 integration.
- **Target Determination Logic**: Pathfinder and TargetManager modules are scheduled for milestones M2 and M3.

---

## Final Recommendation & Next Steps

1. **Verdict**: **REJECT** pending implementation of defensive NaN / Infinity guards in `AngularSpring1D`.
2. **Action for Worker M1**:
   - Add NaN / Infinity sanitization to `AngularSpring1D.update()` and `AngularSpring1D.sanitizeAngle()`.
   - Re-run `SpringSmootherAdversarialTest` to confirm all 22 tests pass including graceful rejection of poisoned inputs.
