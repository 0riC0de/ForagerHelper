# Handoff Report: Codebase Survey & Root Cause Analysis

**Agent**: `teamwork_preview_explorer_survey_1`  
**Working Directory**: `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_1`  
**Date**: 2026-09-11  
**Handoff Type**: Hard (Task Complete)

---

## 1. Observation

1. **Camera Rotation Coupled to 20 TPS Tick Rate**:
   - `InputController.kt:46`: `ClientTickEvents.END_CLIENT_TICK.register(::onEndTick)`
   - `WalkController.kt:68-74`: `tick(...)` executes inside `onEndTick`, updating camera angles via `lookAtNaturally(...)` (lines 339-407) and assigning `player.yaw = lastYaw; player.pitch = lastPitch` (lines 405-406).
   - Verbatim: No render-frame interpolation hook (`WorldRenderEvents`, `ClientPreRenderEvent`, or delta-time) exists for rotation.

2. **Curve-Resetting Stutter in Angle Interpolation**:
   - `WalkController.kt:182-186`: `ticksSinceLastLook >= TARGET_UPDATE_DELAY && lastLookPoint!!.squaredDistanceTo(newLookPoint) > 0.02`
   - `WalkController.kt:357-360`:
     ```kotlin
     val newTarget = lookPlanTargetYaw.isNaN() ||
         abs(MathHelper.wrapDegrees(targetYaw - lookPlanTargetYaw)) > 0.35f ||
         abs(targetPitch - lookPlanTargetPitch) > 0.35f
     ```
   - `WalkController.kt:381`:
     ```kotlin
     lookPlanStep = 0
     ```
   - Verbatim: Whenever player movement changes `targetYaw` by >0.35°, `lookPlanStep` is reset to 0, aborting in-flight easing curves.

3. **High-Frequency White Noise Jitter**:
   - `WalkController.kt:397-400`:
     ```kotlin
     val jitterScale = if (precise) 0.04f else 0.12f
     val jitterYaw = Random.nextDouble(-jitterScale.toDouble(), jitterScale.toDouble()).toFloat()
     val jitterPitch = Random.nextDouble(-jitterScale.toDouble(), jitterScale.toDouble()).toFloat()
     val nextYaw = lookPlanStartYaw + lookPlanDeltaYaw * eased + curveYaw + jitterYaw
     ```
   - `WalkController.kt:409-413`:
     ```kotlin
     point.x + Random.nextDouble(-0.159, 0.159)
     ```
   - Verbatim: Uncorrelated pseudorandom offsets are added every tick directly to yaw and pitch.

4. **Angle Snapping & GCD Violations**:
   - `WalkController.kt:311-312`: `player.yaw = targetYaw; player.pitch = ...` snaps camera instantaneously on Aspect of the Void.
   - `WalkController.kt:405-406`: Raw unquantized floats are assigned to `player.yaw` and `player.pitch`, violating Minecraft's mouse sensitivity formula:
     $$\text{step} = (s \times 0.6 + 0.2)^3 \times 8 \times 0.15$$

5. **Diagonal Corner Snagging in Path Smoothing**:
   - `AStarPathfinder.kt:325-338`: `hasLineOfSight` performs a 1D discrete line check using `Math.round(from + (to - from) * t)` checking `canStandAt(world, pos)`.
   - Player bounding box is $0.6 \times 0.6 \times 1.8$ meters ($[-0.3, +0.3]$ around center).
   - Across diagonal corner cuts, the center line is clear but the player's 0.6m bounding box overlaps adjacent solid block corners by up to 0.3m.

6. **Missing Vertical Traversal Semantics (Slabs, Stairs, Ceilings)**:
   - `AStarPathfinder.kt:80-88`: `canStandAt` requires `isAirLike(world, feet)`.
   - `AStarPathfinder.kt:120-123`: `isAirLike` checks `state.getCollisionShape(world, pos).isEmpty`. Slabs and stairs have non-empty collision shapes and are flagged as impassable.
   - `AStarPathfinder.kt:242-246`: 1-block upward jumps check `isAirLike(world, pos.up(2))` but fail to check ceiling headroom at `flat.up(2)`.

7. **Infinite Stuck Loop on Stagnation**:
   - `WalkController.kt:210-214`: Tracks `stuckTicks`.
   - `WalkController.kt:118-129`: Triggers `AStarPathfinder.findPath(world, start, targetLog)` with identical inputs and static costs, re-generating the identical blocked route.

8. **Movement Direction Coupled to Camera Yaw**:
   - `WalkController.kt:194, 418-426`: `yawDiff = MathHelper.wrapDegrees(targetYaw - player.yaw)` controls `options.forwardKey`, `leftKey`, and `rightKey`.
   - Turning camera to inspect target or look ahead de-presses forward movement or causes rapid strafe flapping.

9. **Build Environment & Baseline Status**:
   - Running: `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 build"`
   - Output: `BUILD SUCCESSFUL in 28s` (exit code 0).

---

## 2. Logic Chain

1. From Observation 1, camera rotations are calculated and applied exclusively inside client ticks (20 TPS). Displays running at 60Hz, 144Hz, or 240Hz render multiple identical visual frames between ticks, creating visual judder and micro-stutter.
2. From Observation 2, because `newLookPoint` updates with player movement and `newTarget` resets `lookPlanStep = 0` whenever yaw shifts by >0.35°, the easing curve is aborted before completion, causing velocity discontinuities (curve-resetting stutter).
3. From Observation 3, per-tick uncorrelated random noise (`±0.12°`) causes continuous high-frequency trembling on the crosshair.
4. From Observation 4, assigning raw floats directly to `player.yaw`/`pitch` breaks the mouse sensitivity GCD divisor, rendering the mod detectable by anti-cheat systems.
5. From Observation 5, 1D point Bresenham raycasting ignores the player's 0.6m bounding box width, smoothing paths across inner wall corners where the box collides with the solid corner, causing corner snagging.
6. From Observation 6, requiring empty collision shapes at player feet prevents stepping onto bottom slabs or stairs, and jumping without apex ceiling checks causes head-bonks.
7. From Observation 7, because A* has no dynamic penalty memory for stuck nodes, repathing produces the exact same blocked path, creating an infinite stuck loop.
8. From Observation 8, computing movement key states from camera-relative `yawDiff` prevents independent camera orientation, causing input fighting whenever the camera turns.
9. Therefore, replacing `WalkController` with decoupled modules: R1 (`RotationEngine` with render-frame spring dynamics and GCD quantization), R2 (`Pathfinder` with swept-box LOS, slab/stair handling, and dynamic node penalization), and R4 (`MovementController` with decoupled WASD projection and multi-tier recovery) directly solves all observed defects.

---

## 3. Caveats

- Tree scanning and scoring logic (`TreeScanner`, `TreeScorer`, `TreeCluster`) and Aspect of the Void planning (`EtherWarpPlanner`) are functionally sound and should be retained or adapted into the R3 target framework.
- The UI layer (`HelperOptionsScreen`), keybinds (`ModKeyBindings`), HUD (`StatusHud`), and gizmos (`DebugWorldOverlay`) rely on status properties (e.g. `WalkController.status`, `WalkController.path`) which must remain exposed via the new controllers or adapter accessors to prevent compile breakages.

---

## 4. Conclusion

The existing navigation and rotation system suffers from fundamental architectural coupling and mathematical flaws. A clean-slate re-architecture into R1, R2, and R4 with clearly defined interface contracts will resolve all camera stutter, jitter, GCD flaws, and corner snagging issues while maintaining full backward compatibility with the mod's existing HUD, UI, and commands.

---

## 5. Verification Method

1. **Gradle Build Verification**:
   Execute the baseline compile and build command:
   ```bash
   cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin build"
   ```
2. **Code Inspection**:
   - Check `report.md` at `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_1\report.md` for full class maps and mathematical formulations.
   - Verify that R1 interfaces include render frame hooks and GCD quantization formulas.
   - Verify that R2 interfaces include swept AABB validation and node penalization.
   - Verify that R4 interfaces include camera-decoupled WASD vector projections.
3. **Invalidation Conditions**:
   - The analysis would be invalidated if Minecraft 1.21 client allowed arbitrary floating point angles without anti-cheat heuristics (which it does not) or if the player bounding box were negligible (which it is 0.6x1.8m).
