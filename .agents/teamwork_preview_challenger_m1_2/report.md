# Adversarial Challenge Report: SensitivityGCD & Anti-Cheat Quantization

**Challenger**: `teamwork_preview_challenger_m1_2`  
**Role**: Adversarial Anti-Cheat & GCD Verifier  
**Target Platform**: Minecraft 1.21.11 (Fabric Loader 0.19.3, Yarn 1.21.11+build.6, Java 21/23, Kotlin 2.4.10)  
**Date**: 2026-09-11  
**Verdict**: **REJECT**  

---

## 1. Executive Summary

We performed an adversarial stress-test and numerical verification of `SensitivityGCD.kt` and `RotationEngine.kt`, targeting:
1. Exact integer multiple parity with Minecraft's mouse sensitivity GCD step ($f^3 \times 1.2$).
2. Extreme sensitivity limits ($s \in \{0.0, 0.5, 1.0, 2.0\}$ and degenerate inputs).
3. 10,000-frame remainder accumulator stability across diverse input signals.
4. Zenith and nadir boundary clamping ($[-90^\circ, +90^\circ]$) and anti-windup integrity.

### Summary of Findings:
- **GCD Quantization & Step Multiple**: **PASS**. Applied deltas strictly match vanilla `Entity.changeLookDirection` and mouse pulse counts.
- **Sensitivity Range & Degenerate Inputs**: **PASS**. Step calculation, spyglass scaling ($1/8$), negative/NaN fallback ($0.5$), and excessive sensitivity clamping ($2.0$) are mathematically sound.
- **10,000-Frame Remainder Stability (Unbounded / Yaw)**: **PASS**. Yaw and free pitch maintain strict error bounds ($|E| \le 0.5 \times \text{step}$) with zero steady-state drift over 10,000 frames under constant sub-steps, sinusoids, and random white noise.
- **Pitch Boundary Remainder Stability & Anti-Windup**: **CRITICAL FAILURE (REJECT)**. A logic defect in `SensitivityGCD.quantize()` lines 98–121 causes the `pitchRemainder` accumulator to catastrophically explode when pitch approaches or pegs against $\pm 90^\circ$. Instead of remaining bounded by $\pm 0.5 \times \text{step}$, `pitchRemainder` accumulates hundreds or thousands of degrees of phantom rotational debt, completely freezing the camera from reversing direction.

---

## 2. Empirical Test Suite & Execution Results

We introduced the adversarial test suite `src/test/kotlin/com/github/foragerhelper/rotation/SensitivityGCDAdversarialTest.kt` consisting of 8 rigorous stress tests.

### Test Execution Command:
```bat
cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat test"
```

### Full Test Run Matrix (55 Tests Across Entire Suite):
| Test Suite | Tests | Passed | Failed | Status |
|---|:---:|:---:|:---:|:---:|
| `RotationEngineTest` | 25 | 25 | 0 | PASS |
| `SpringSmootherAdversarialTest` | 22 | 22 | 0 | PASS |
| `SensitivityGCDAdversarialTest` | 8 | 6 | 2 | **FAIL** |
| **Total** | **55** | **53** | **2** | **FAIL** |

### Individual Test Case Results in `SensitivityGCDAdversarialTest`:
1. `testAppliedDeltaIsExactMultipleOfGCDStep`: **PASS** (0.033s)
2. `testSensitivityExtremeRange`: **PASS** (0.043s)
3. `testDegenerateSensitivitiesFallback`: **PASS** (0.000s)
4. `test10000FrameRemainderAccumulatorConstantSubStep`: **PASS** (0.095s)
5. `test10000FrameRemainderAccumulatorSinusoidal`: **PASS** (0.027s)
6. `test10000FrameRemainderAccumulatorRandomNoise`: **PASS** (0.012s)
7. `testPitchLimitsNeverExceededWhenApproachingBoundaries`: **FAILED** (0.009s)
8. `testPitchBoundaryRemainderExplosionStressTest`: **FAILED** (0.011s)

---

## 3. Deep-Dive Forensic Analysis of Failure Modes

### 3.1 Failure 1: Remainder Accumulator Explosion at Pitch Boundaries

**Test**: `testPitchBoundaryRemainderExplosionStressTest`  
**Failure Output**:
```
org.opentest4j.AssertionFailedError: Frame 1: Pitch remainder exploded to 0.5499999418854686 while pegged at pitch limit (max allowed: 0.07500000968575524)
at SensitivityGCDAdversarialTest.kt:332
```

#### Code Inspection (`SensitivityGCD.kt`):
```kotlin
98:         // --- Pitch Boundary Over-Rotation Clamping ---
99:         if (currentPitch != null && countsPitch != 0) {
100:             val potentialPitchDelta = countsToDelta(countsPitch, safeSens, isSpyglass)
101:             val projectedPitch = currentPitch + potentialPitchDelta
102:             if (projectedPitch > 90.0f) {
103:                 val allowedDelta = (90.0f - currentPitch).toDouble()
104:                 countsPitch = (allowedDelta / step).toInt().coerceAtLeast(0)
105:                 pitchRemainder = 0.0
106:             } else if (projectedPitch < -90.0f) {
107:                 val allowedDelta = (-90.0f - currentPitch).toDouble()
108:                 countsPitch = (allowedDelta / step).toInt().coerceAtMost(0)
109:                 pitchRemainder = 0.0
110:             }
111:         }
112: 
113:         // --- Compute Exact Applied Deltas (Bit-Exact with Vanilla) ---
114:         val appliedDeltaYaw = countsToDelta(countsYaw, safeSens, isSpyglass)
115:         val appliedDeltaPitch = countsToDelta(countsPitch, safeSens, isSpyglass)
116: 
117:         // --- Update Remainders for Next Frame ---
118:         yawRemainder = totalYaw - (countsYaw.toDouble() * step)
119:         if (currentPitch == null || (currentPitch in -89.99f..89.99f)) {
120:             pitchRemainder = totalPitch - (countsPitch.toDouble() * step)
121:         }
```

#### The Defect Mechanics:
1. Assume player is at `currentPitch = 89.5f` with default sensitivity $s=0.5$ ($\text{step} \approx 0.1500^\circ$).
2. A desired pitch delta of $+1.0^\circ$ is requested.
3. `totalPitch = 1.0 + 0.0 = 1.0`.
4. `nearestCount(1.0, 0.15) = 7`.
5. `projectedPitch = 89.5f + countsToDelta(7) = 89.5 + 1.05 = 90.55f > 90.0f`.
6. Lines 102–105 execute:
   - `allowedDelta = 90.0 - 89.5 = 0.5`.
   - `countsPitch = (0.5 / 0.15).toInt() = 3`.
   - `pitchRemainder = 0.0` (intended to prevent windup).
7. Now execution proceeds to lines 119–121:
   - Condition: `currentPitch in -89.99f..89.99f`.
   - Since `89.5f` is strictly inside `[-89.99f, 89.99f]`, the condition evaluates to **TRUE**!
   - Line 120 executes and overwrites `pitchRemainder`:
     $$\text{pitchRemainder} = \text{totalPitch} - (\text{countsPitch} \times \text{step}) = 1.0 - (3 \times 0.1500) = 0.5500^\circ$$
8. On Frame 1, `pitchRemainder` is already $0.55^\circ$, which is **$7.33\times$** the maximum allowed bound of $0.5 \times \text{step} = 0.075^\circ$.
9. Over 1,000 frames pushing toward nadir, `pitchRemainder` accumulates $+1000^\circ$ of unclampable rotational debt.

---

### 3.2 Failure 2: Integral Windup Freezing Directional Reversal

**Test**: `testPitchLimitsNeverExceededWhenApproachingBoundaries`  
**Failure Output**:
```
org.opentest4j.AssertionFailedError: Camera must reverse upward immediately upon negative desired pitch: countsPitch=0, pitch=89.89995, sens=0.5
at SensitivityGCDAdversarialTest.kt:280
```

#### The Defect Mechanics:
1. Because `pitchRemainder` exploded to e.g. $+500.0^\circ$ while navigating downward, the player/path now attempts to look upward by requesting `desiredDeltaPitch = -1.0^\circ`.
2. In `quantize()`:
   $$\text{totalPitch} = \text{desiredDeltaPitch} + \text{pitchRemainder} = -1.0 + 500.0 = +499.0^\circ$$
3. `countsPitch = nearestCount(+499.0, step) = 3327`.
4. `projectedPitch = 89.89995 + 499.0 = 588.9^\circ > 90.0^\circ`.
5. Line 103: `allowedDelta = 90.0 - 89.89995 = 0.10005^\circ`.
6. Line 104: `countsPitch = (0.10005 / step).toInt() = 0`.
7. `appliedDeltaPitch = 0.0f`!
8. **Result**: The player requested an immediate upward look of $-1.0^\circ$, but the rotation engine generated zero movement pulses (`countsPitch = 0`). The camera is locked to the floor until the caller applies 500 frames of upward rotation to bleed off the accumulator debt!
9. Furthermore, `DefaultRotationEngine` clamps its internal pitch to `[-89.9f, 89.9f]`, meaning `currentPitch` will *always* satisfy `currentPitch in -89.99f..89.99f` and trigger this accumulator leak every single time the camera approaches the vertical floor.

---

## 4. Blast Radius Assessment

- **Severity**: **HIGH / BLOCKING**
- **User Impact**: Any navigation path involving vertical looking (e.g. descending stairs, harvesting crops on the ground, mining downward, attacking mobs below) will peg the camera at the nadir limit. Once pegged, attempting to look back up along the path will suffer 1–5 seconds of total camera freeze until the accumulator winds down.
- **Anti-Cheat Impact**: While individual applied deltas remain integer multiples of GCD, the catastrophic delay in camera responsiveness followed by rapid uncoiling can trigger anti-cheat heuristic flags (e.g., Vulcan / Karhu irregular mouse movement flags).

---

## 5. Recommended Mitigation for Implementer

In `SensitivityGCD.kt`:
1. When pitch boundary clamping occurs (lines 102–110), set an explicit flag indicating that clamping occurred:
   ```kotlin
   var pitchClamped = false
   if (currentPitch != null && countsPitch != 0) {
       val potentialPitchDelta = countsToDelta(countsPitch, safeSens, isSpyglass)
       val projectedPitch = currentPitch + potentialPitchDelta
       if (projectedPitch > 90.0f) {
           val allowedDelta = (90.0f - currentPitch).toDouble()
           countsPitch = (allowedDelta / step).toInt().coerceAtLeast(0)
           pitchRemainder = 0.0
           pitchClamped = true
       } else if (projectedPitch < -90.0f) {
           val allowedDelta = (-90.0f - currentPitch).toDouble()
           countsPitch = (allowedDelta / step).toInt().coerceAtMost(0)
           pitchRemainder = 0.0
           pitchClamped = true
       }
   }
   ```
2. Guard remainder updating so that line 120 is skipped if `pitchClamped` is true or if `currentPitch >= 90.0f || currentPitch <= -90.0f`:
   ```kotlin
   if (!pitchClamped) {
       pitchRemainder = totalPitch - (countsPitch.toDouble() * step)
   }
   ```
3. Alternatively, when boundary clamping occurs, update `pitchRemainder` relative to `allowedDelta`, or strictly clear `pitchRemainder = 0.0` and do not re-assign from `totalPitch`.

---

## 6. Challenger Verdict

**Verdict**: **REJECT**  
Milestone M1 cannot be approved until the pitch remainder explosion and anti-windup lockup in `SensitivityGCD.kt` is remediated and `SensitivityGCDAdversarialTest` passes with 0 failures.
