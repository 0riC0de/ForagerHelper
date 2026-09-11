## Forensic Audit Report

**Work Product**: `src/main/kotlin/com/github/foragerhelper/rotation/` and `src/test/kotlin/com/github/foragerhelper/rotation/`
**Profile**: General Project
**Integrity Mode**: Development
**Verdict**: INTEGRITY VIOLATION

---

### Phase Results

| # | Check Name | Result | Details |
|---|------------|--------|---------|
| 1 | Hardcoded Output Detection | **PASS** | 0 hardcoded test lookup tables, expected output arrays, or dummy constants detected. |
| 2 | Facade Implementation Detection | **PASS** | Genuine 2nd-order ODE analytic spring math in `SpringSmoother.kt`, genuine Minecraft mouse sensitivity GCD quantization in `SensitivityGCD.kt`, and genuine Fabric `WorldRenderEvents.START_MAIN` hook in `RotationEngine.kt`. |
| 3 | Pre-populated Artifact Detection | **PASS** | No pre-existing fake logs, test output binaries, or attestation files in source or project root. |
| 4 | Test Attestation & Bypassing Check | **PASS** | `RotationEngineTest` (25 tests) was genuinely run by worker M1 and passes 25/25 standalone with zero mocked or bypassed assertions. |
| 5 | Behavioral Verification (`gradlew test`) | **FAIL** | Full Gradle test suite execution failed with exit code 1 (`33 completed, 2 failed`). Two adversarial tests in `SensitivityGCDAdversarialTest.kt` failed due to unbounded pitch remainder explosion and anti-windup breakdown. |

---

### Detailed Forensic Analysis

#### 1. Hardcoded Output Detection (PASS)
An exhaustive inspection of `SpringSmoother.kt`, `SensitivityGCD.kt`, `RotationEngine.kt`, and `RotationEngineTest.kt` was conducted:
- No pattern matching test inputs to fixed return values exists.
- Grep queries for `mock`, `stub`, `dummy`, `fake`, `pass`, `fail` across all rotation source and test files returned 0 matches.
- All angle calculations, spring step evaluations, and GCD quantizations are derived dynamically from closed-form analytic formulas and Minecraft 1.21.11 bytecode constants (`0.6000000238418579`, `0.20000000298023224`, `8.0`, `0.15`).

#### 2. Facade Implementation Detection (PASS)
- `SpringSmoother.kt` / `AngularSpring1D`: Accurately implements the exact analytic solution to the 2nd-order critically-damped ODE $x''(t) + 2\omega x'(t) + \omega^2 x(t) = 0$ ($c_2 = v_0 + \omega x_0$, $x(h) = (x_0 + c_2 h)e^{-\omega h}$, $v(h) = (v_0 - \omega c_2 h)e^{-\omega h}$). Incorporates Dawson virtual displacement clamping for peak velocity limiting ($720^\circ/\text{s}$) and angular wrapping on $S^1$ across $[-180^\circ, 180^\circ]$.
- `SensitivityGCD.kt`: Accurately replicates vanilla Minecraft's mouse sensitivity formula: $f = s \times 0.6 + 0.2$, $\text{multiplier} = f^3 \times 8.0$, $\text{step} = f^3 \times 1.2$. Employs IEEE 754 precision casting (`dx.toFloat() * 0.15f`) to eliminate float ULP mismatches.
- `RotationEngine.kt`: Hooks `WorldRenderEvents.START_MAIN`, computes delta times using `System.nanoTime()`, applies quantized look deltas via vanilla `player.changeLookDirection(dx, dy)`, and blends path tangents with target focus points using Hermite smoothstep $w(u) = 3u^2 - 2u^3$.

#### 3. Pre-populated Artifact Detection (PASS)
Searches across the project workspace revealed only standard Gradle build cache files, standard test outputs, and `fabric.mod.json`/`en_us.json`. No pre-baked verification files, spoofed test certificates, or fake log files were present.

#### 4. Test Attestation & Bypassing (PASS)
The 25 unit tests written in `RotationEngineTest.kt` are substantive, rigorous, and test real physical invariants (monotonic convergence, settling times, frame-rate invariance, angle wrapping across the 180 boundary, severe 2-second lag spikes, and GCD step divisibility). When executed standalone via:
```cmd
cmd /c "set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 && cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test --tests com.github.foragerhelper.rotation.RotationEngineTest"
```
The test suite completes in 1.082s with 25 tests, 0 failures, 0 skipped, 0 errors.

#### 5. Behavioral Verification & Test Suite Failure (FAIL)
When executing the required project test command across all test classes in `src/test/kotlin/com/github/foragerhelper/rotation/`:
```cmd
cmd /c "set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 && cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"
```
The build fails with exit code 1:
```
SensitivityGCDAdversarialTest > testPitchBoundaryRemainderExplosionStressTest() FAILED
    org.opentest4j.AssertionFailedError at SensitivityGCDAdversarialTest.kt:332

SensitivityGCDAdversarialTest > testPitchLimitsNeverExceededWhenApproachingBoundaries() FAILED
    org.opentest4j.AssertionFailedError at SensitivityGCDAdversarialTest.kt:280

33 tests completed, 2 failed
> Task :test FAILED
BUILD FAILED in 33s
```

---

### Root Cause & Vulnerability Breakdown

The failure is caused by a critical mathematical bug in `SensitivityGCD.kt` lines 98-125:

1. **Boundary Clamping vs Remainder Accumulation**:
   When `currentPitch` is near the $\pm 90^\circ$ boundary (e.g. $+89.5^\circ$), requesting downward rotation ($+1.0^\circ$) causes `projectedPitch` to exceed $+90.0^\circ$.
   Line 105 correctly clamps `countsPitch` to `0` and sets `pitchRemainder = 0.0`.
   However, at line 120:
   ```kotlin
   if (currentPitch == null || (currentPitch in -89.99f..89.99f)) {
       pitchRemainder = totalPitch - (countsPitch.toDouble() * step)
   }
   ```
   Because `currentPitch` (which is $89.5^\circ$) is inside `-89.99f..89.99f`, line 121 executes and calculates:
   `pitchRemainder = totalPitch - (countsPitch * step)`.
   Because `countsPitch` was clamped to 0, `pitchRemainder` is assigned `totalPitch` (e.g. $1.0^\circ$), overwriting the `pitchRemainder = 0.0` set on line 105!

2. **Integral Windup / Remainder Explosion**:
   Over 1,000 frames of pegging the camera against the floor or ceiling, `pitchRemainder` accumulates $+1.0^\circ$ every frame, exploding to over $+500^\circ$ to $+1000^\circ$.
   This violates the strict invariant $|E| \le 0.5 \times \text{step}$ ($0.075^\circ$).

3. **Motion Reversal Lockout**:
   When the player immediately requests an upward turn (e.g. $-1.0^\circ$), `totalPitch = -1.0 + 1000.0 = +999.0^\circ`. The quantizer sees a massive positive delta and emits `countsPitch = 0` (due to boundary clamping), completely failing to turn upward. The camera is frozen looking at the ground for hundreds of frames until the remainder is manually drained.

---

### Evidence

#### Raw Tool Output: Full Gradle Test Run Failure
```
> Task :test
Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8 

SensitivityGCDAdversarialTest > testPitchBoundaryRemainderExplosionStressTest() FAILED
    org.opentest4j.AssertionFailedError at SensitivityGCDAdversarialTest.kt:332

SensitivityGCDAdversarialTest > testPitchLimitsNeverExceededWhenApproachingBoundaries() FAILED
    org.opentest4j.AssertionFailedError at SensitivityGCDAdversarialTest.kt:280

33 tests completed, 2 failed

> Task :test FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':test' (registered by plugin 'org.gradle.jvm-test-suite').
> java.nio.file.NoSuchFileException: C:\Users\D0AF~1\source\repos\FORAGE~1\build\test-results\test\binary\in-progress-results-generic.bin

BUILD FAILED in 33s
5 actionable tasks: 2 executed, 3 up-to-date
```

#### Raw Tool Output: Standalone RotationEngineTest Pass
```
> Task :test
Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8 

BUILD SUCCESSFUL in 27s
5 actionable tasks: 2 executed, 3 up-to-date

XML Report:
<testsuite name="com.github.foragerhelper.rotation.RotationEngineTest" tests="25" skipped="0" failures="0" errors="0" timestamp="2026-09-11T13:05:12.937Z" hostname="DESKTOP-DA030EE" time="1.082">
```

---

### Required Remediation (For Worker M1)

In `SensitivityGCD.kt`:
1. When `projectedPitch > 90.0f` or `projectedPitch < -90.0f` triggers boundary clamping on `countsPitch`, a boolean flag `pitchClampedAtBoundary = true` must be set, and `pitchRemainder` must be set to `0.0`.
2. In line 120, `pitchRemainder` must NOT be updated if `pitchClampedAtBoundary` is true. Remainder accumulation must strictly represent sub-step hardware quantizing fractions ($|E| \le 0.5 \times \text{step}$), never discarded field-of-view boundary overflow.
3. Re-run `gradlew test` to ensure all 33 tests pass with 0 failures.
