# Original User Request

## Initial Request — 2026-09-11T12:20:21Z

The user requested: "use teamwork-preview (a team of autonomous agents) for this project."

Execute a clean-slate rewrite and re-architecture of the core navigation, rotation, and target-selection engine for the ForagerHelper Fabric 1.21.11 Minecraft mod. Preserve the existing functional UI, keybinds, and HUD shell while replacing the tangled, jittery controllers with 4 cleanly decoupled modules: a render-frame smooth rotation engine with mouse-sensitivity GCD quantization, a hitbox-aware 3D A* pathfinder with swept-box line-of-sight, an extensible target system supporting blocks and mobs, and an input-decoupled movement controller.

Working directory: c:\Users\משתמש\source\repos\ForagerHelper
Integrity mode: development

## Build Environment
- Java 23 is located at `C:\Users\D0AF~1\JDKS~1\OPENJD~1` (8.3 short path to avoid Windows Hebrew username encoding issues).
- To run Gradle builds cleanly:
  `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"`

## Requirements

### R1. Standalone Humanized Rotation Engine (`rotation/RotationEngine.kt`)
- Execute camera rotations independently of client ticks via render events or delta-time interpolation, achieving smooth camera panning at monitor refresh rates (60/144/240Hz).
- Implement continuous critically-damped spring / exponential smoothing to eliminate curve-resetting stutter.
- Quantize all yaw and pitch increments to Minecraft's mouse sensitivity GCD formula (`f * 0.6 + 0.2`, step = `f^3 * 8 * 0.15`) for complete anti-cheat compliance.
- Support natural head movement: orient player view along the path tangent while moving, smoothly acquiring target focus when approaching interaction distance.

### R2. Hitbox-Aware 3D A* Pathfinder (`path/Pathfinder.kt`)
- Implement a robust 3D A* pathfinder that evaluates the player's 3D bounding box (0.6 width, 1.8 height) against block collision boxes.
- Replace point-based Bresenham line checks with swept bounding-box raycasts to completely prevent diagonal corner snagging.
- Properly evaluate vertical traversal: slabs, stairs, 1-block drops, jump clearance under ceilings, and basic parkour gaps.
- Integrate dynamic node penalization and unstuck maneuvers (reverse, strafe, re-route) to break infinite stuck loops.

### R3. Universal Target Framework (`target/`)
- Define an extensible `NavigationTarget` abstraction decoupled from foraging:
  - `BlockTarget`: Coordinates, target block types, face preference, line-of-sight checks.
  - `EntityTarget`: Target entity, hitboxes, distance maintenance, optional velocity prediction for mobs.
  - `PositionTarget`: Coordinates/waypoints for manual routes (`/forageroute`).
- Provide pluggable `TargetScanner<T>` implementations for tree clusters, mobs/wildlife, and custom blocks.

### R4. Decoupled Movement Controller (`movement/MovementController.kt`)
- Pure input translation: computes relative direction between player position, next waypoint, and current player yaw to press forward/strafe/jump keys.
- Operates in tandem with `RotationEngine` without fighting or blocking camera turns.
- Clean integration with existing `HelperConfig`, `HelperOptionsScreen`, `StatusHud`, and `DebugWorldOverlay`.

## Acceptance Criteria

### Camera & Visuals
- [ ] Camera tracking while walking follows path waypoints smoothly without angle snapping, stutter, or random high-frequency jitter.
- [ ] Render-frame camera interpolation functions cleanly at high monitor refresh rates (60/144Hz+).
- [ ] Pitch and yaw updates conform to mouse sensitivity GCD quantization.

### Pathfinding & Motion
- [ ] Player navigates around corners, through 1-block openings, and across slabs/stairs without catching bounding-box edges.
- [ ] When blocked by a dynamic obstacle, unstuck recovery triggers and recalculates an alternate route rather than repeating failed actions.
- [ ] Manual destination command `/forageroute <x> <y> <z>` successfully routes the player to the exact coordinate.

### Extensibility & Quality
- [ ] Core architecture supports targeting both blocks and mobs through the `NavigationTarget` interface.
- [ ] Existing tree foraging functionality operates seamlessly on the new architecture.
- [ ] Project builds cleanly via Gradle (`compileKotlin` and `build`) with zero errors.

## Follow-up — 2026-09-11T19:34:20Z

The user requested: "continue, i saw some proccesses got terminated because of limit issues try them again"

Resume execution of the ForagerHelper Fabric 1.21.11 Minecraft mod navigation rewrite.

Working directory: c:\Users\משתמש\source\repos\ForagerHelper
Integrity mode: development

## Build Environment
- Java 23 is located at `C:\Users\D0AF~1\JDKS~1\OPENJD~1` (8.3 short path).
- To run Gradle test suite:
  `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"`
- To run Gradle build:
  `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 build"`

## Current Project State & Completed Work
- **Milestone 1 (Rotation Engine R1) is COMPLETE and COMMITTED** (commit `c40513b`):
  - `src/main/kotlin/com/github/foragerhelper/rotation/RotationEngine.kt`
  - `src/main/kotlin/com/github/foragerhelper/rotation/SensitivityGCD.kt`
  - `src/main/kotlin/com/github/foragerhelper/rotation/SpringSmoother.kt`
  - All 55 tests in `RotationEngineTest`, `SensitivityGCDAdversarialTest`, and `SpringSmootherAdversarialTest` pass cleanly.
- Full architectural specifications, interface contracts, and feature inventories are documented in:
  - `.agents/PROJECT.md`
  - `.agents/TEST_INFRA.md`
  - `.agents/orchestrator_1/handoff.md`

## Remaining Milestones to Implement and Verify

### Milestone 2: Hitbox-Aware 3D A* Pathfinder (R2)
- Implement `src/main/kotlin/com/github/foragerhelper/path/Pathfinder.kt` respecting player bounding box (0.6 width x 1.8 height).
- Implement `src/main/kotlin/com/github/foragerhelper/path/SweptBoxLOS.kt` replacing 1D Bresenham line checks with swept AABB to eliminate diagonal corner snagging.
- Handle vertical traversal: slabs, stairs, 1-block drops, jump apex ceiling headroom, and parkour gaps.
- Implement `src/main/kotlin/com/github/foragerhelper/path/NodePenaltyMap.kt` for dynamic spatial penalty memory on stuck nodes.
- Author comprehensive unit tests for node expansion, swept-box collision, and path smoothing.

### Milestone 3: Universal Target Framework (R3)
- Implement `src/main/kotlin/com/github/foragerhelper/target/NavigationTarget.kt`: sealed interface supporting `BlockTarget` (foraging/mining), `EntityTarget` (mobs/wildlife), and `PositionTarget` (waypoints).
- Implement `src/main/kotlin/com/github/foragerhelper/target/TargetScanner.kt`: `TreeClusterTargetScanner`, `MobTargetScanner`, `CustomBlockTargetScanner`.
- Implement `src/main/kotlin/com/github/foragerhelper/target/TargetManager.kt`: target lifecycle, locking, reach validation, and focus acquisition.
- Author unit tests for target filtering, sorting, and reach calculation.

### Milestone 4: Decoupled Movement Controller & Integration (R4)
- Implement `src/main/kotlin/com/github/foragerhelper/movement/MovementController.kt`: pure input translation projecting player-to-waypoint vectors into local WASD keypresses without overriding camera yaw.
- Implement `src/main/kotlin/com/github/foragerhelper/movement/UnstuckHandler.kt`: stagnation detection and multi-tier recovery maneuvers.
- Wire with `RotationEngine`, `Pathfinder`, `TargetManager`.
- Connect to UI/HUD/Commands: `HelperConfig`, `HelperOptionsScreen`, `StatusHud`, `DebugWorldOverlay` (1.21.11 `GizmoDrawing`), and `/forageroute`.

### Milestone 5: Final Verification & Hardening
- Run full non-cached Gradle test suite across all modules (`gradlew test --rerun-tasks`).
- Verify `gradlew build` produces clean output with 0 errors.
- Ensure all acceptance criteria from the original request are satisfied.
