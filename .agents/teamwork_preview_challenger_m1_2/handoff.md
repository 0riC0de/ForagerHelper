# Handoff Report: Challenger M1-2 (SensitivityGCD Adversarial Verification)

**Agent**: `teamwork_preview_challenger_m1_2`  
**Role**: Adversarial Anti-Cheat & GCD Verifier  
**Target Milestone**: M1 (Standalone Humanized Rotation Engine)  
**Verdict**: **REJECT**  

---

## 1. Observation

1. **Test Execution Command & Failure**:
   Executed Gradle test suite on Windows:
   ```bat
   cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat test"
   ```
   Command completed with exit code 1. Output snippet:
   ```
   > Task :test
   
   SensitivityGCDAdversarialTest > testPitchBoundaryRemainderExplosionStressTest() FAILED
       org.opentest4j.AssertionFailedError at SensitivityGCDAdversarialTest.kt:332
   
   SensitivityGCDAdversarialTest > testPitchLimitsNeverExceededWhenApproachingBoundaries() FAILED
       org.opentest4j.AssertionFailedError at SensitivityGCDAdversarialTest.kt:280
   
   55 tests completed, 2 failed
   ```
2. **Verbatim Failure Messages in `build/test-results/test/TEST-com.github.foragerhelper.rotation.SensitivityGCDAdversarialTest.xml`**:
   - `testPitchBoundaryRemainderExplosionStressTest`:
     ```
     org.opentest4j.AssertionFailedError: Frame 1: Pitch remainder exploded to 0.5499999418854686 while pegged at pitch limit (max allowed: 0.07500000968575524)
     at com.github.foragerhelper.rotation.SensitivityGCDAdversarialTest.testPitchBoundaryRemainderExplosionStressTest(SensitivityGCDAdversarialTest.kt:332)
     ```
   - `testPitchLimitsNeverExceededWhenApproachingBoundaries`:
     ```
     org.opentest4j.AssertionFailedError: Camera must reverse upward immediately upon negative desired pitch: countsPitch=0, pitch=89.89995, sens=0.5
     at com.github.foragerhelper.rotation.SensitivityGCDAdversarialTest.testPitchLimitsNeverExceededWhenApproachingBoundaries(SensitivityGCDAdversarialTest.kt:280)
     ```
3. **Source Code in `src/main/kotlin/com/github/foragerhelper/rotation/SensitivityGCD.kt` lines 98–121**:
   ```kotlin
   // --- Pitch Boundary Over-Rotation Clamping ---
   if (currentPitch != null && countsPitch != 0) {
       val potentialPitchDelta = countsToDelta(countsPitch, safeSens, isSpyglass)
       val projectedPitch = currentPitch + potentialPitchDelta
       if (projectedPitch > 90.0f) {
           val allowedDelta = (90.0f - currentPitch).toDouble()
           countsPitch = (allowedDelta / step).toInt().coerceAtLeast(0)
           pitchRemainder = 0.0
       } else if (projectedPitch < -90.0f) {
           val allowedDelta = (-90.0f - currentPitch).toDouble()
           countsPitch = (allowedDelta / step).toInt().coerceAtMost(0)
           pitchRemainder = 0.0
       }
   }

   // --- Compute Exact Applied Deltas (Bit-Exact with Vanilla) ---
   val appliedDeltaYaw = countsToDelta(countsYaw, safeSens, isSpyglass)
   val appliedDeltaPitch = countsToDelta(countsPitch, safeSens, isSpyglass)

   // --- Update Remainders for Next Frame ---
   yawRemainder = totalYaw - (countsYaw.toDouble() * step)
   if (currentPitch == null || (currentPitch in -89.99f..89.99f)) {
       pitchRemainder = totalPitch - (countsPitch.toDouble() * step)
   }
   ```
4. **Passing Verification Tests**:
   - `testAppliedDeltaIsExactMultipleOfGCDStep`: PASSED across $s \in [0.0, 2.0]$ in both normal and spyglass mode.
   - `testSensitivityExtremeRange`: PASSED ($s=0.0, 0.5, 1.0, 2.0$).
   - `test10000FrameRemainderAccumulatorConstantSubStep`: PASSED for yaw and unconstrained pitch ($|E| \le 0.5 \times \text{step}$).
   - `test10000FrameRemainderAccumulatorSinusoidal`: PASSED ($|E| \le 0.5 \times \text{step}$).
   - `test10000FrameRemainderAccumulatorRandomNoise`: PASSED ($|E| \le 0.5 \times \text{step}$).

---

## 2. Logic Chain

1. From **Observation 3**, lines 102–105 set `countsPitch = (allowedDelta / step).toInt()` and `pitchRemainder = 0.0` whenever `projectedPitch > 90.0f`.
2. However, line 119 checks `if (currentPitch == null || (currentPitch in -89.99f..89.99f))`. When `currentPitch` is in $[-89.99^\circ, 89.99^\circ]$ (such as $89.5^\circ$ or `DefaultRotationEngine`'s maximum clamped pitch of $89.9^\circ$), this condition evaluates to true.
3. Line 120 unconditionally executes: `pitchRemainder = totalPitch - (countsPitch.toDouble() * step)`, overwriting the zero assigned on line 105.
4. Because `countsPitch` was clamped by line 104 to prevent over-rotation, `countsPitch * step` accounts only for the allowed delta, leaving the excess unapplied delta inside `totalPitch`.
5. Consequently, `pitchRemainder` captures the discarded delta ($0.55^\circ$ on Frame 1 as confirmed in **Observation 2**, compared to the allowable upper bound of $0.075^\circ$).
6. Over continuous frames where downward rotation is requested, `pitchRemainder` accumulates unbounded positive degrees, directly violating the requirement that total accumulated remainder stay within $[-0.5 \times \text{step}, +0.5 \times \text{step}]$.
7. When the caller subsequently attempts to rotate upward (`desiredDeltaPitch = -1.0`), line 92 adds `desiredDeltaPitch` to the massive positive `pitchRemainder`. The sum remains overwhelmingly positive, forcing `countsPitch = 0` via boundary clamping (as recorded in **Observation 2**).
8. This freezes the camera from moving upward, violating pitch responsiveness and creating severe integral windup.

---

## 3. Caveats

- **Yaw axis and unconstrained pitch**: The remainder accumulator on yaw and free pitch is mathematically sound, showing zero cumulative drift across 10,000 frames under constant sub-steps, sinusoids, and random noise.
- **GCD byte-exact parity**: Every non-zero delta produced by `countsToDelta` matches vanilla `Entity.changeLookDirection` bit-for-bit.
- **Scope**: This evaluation targeted `SensitivityGCD.kt` and `RotationEngine.kt`. The rest of the codebase (`Pathfinder.kt`, `NavigationTarget.kt`, `MovementController.kt`) belongs to later milestones (M2–M4) and was not tested.

---

## 4. Conclusion

**Verdict**: **REJECT**

`SensitivityGCD.kt` fails Milestone M1 acceptance criteria:
1. Remainder accumulator explodes to values orders of magnitude greater than $0.5 \times \text{step}$ when pitch approaches $\pm 90^\circ$.
2. Directional reversal from the pitch boundary is blocked by accumulated rotational debt, causing camera freeze.

**Required Action**: The implementer (`teamwork_preview_worker_m1`) must fix `SensitivityGCD.kt` lines 98–121 to ensure `pitchRemainder` is not overwritten with discarded excess delta when boundary clamping occurs (e.g. by checking a `pitchClamped` flag or guarding line 120), so that all 55 tests pass cleanly.

---

## 5. Verification Method

To independently verify:
1. Run the test command:
   ```bat
   cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat test"
   ```
2. Invalidation Condition: The REJECT verdict is invalidated if and only if `SensitivityGCD.kt` is patched, the build exits with code 0, and all 55 tests in `RotationEngineTest`, `SpringSmootherAdversarialTest`, and `SensitivityGCDAdversarialTest` report 0 failures and 0 errors.
3. Review `src/test/kotlin/com/github/foragerhelper/rotation/SensitivityGCDAdversarialTest.kt` lines 248–338 for the exact reproduction harnesses.
