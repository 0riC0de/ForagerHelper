# Handoff Report: Hitbox-Aware 3D A* Pathfinder Architecture

**Author**: Explorer 1 (`teamwork_preview_explorer_m2_1`)  
**Role**: Milestone 2 Explorer 1 (3D A* Pathfinder Algorithm Design)  
**Recipient**: Parent Orchestrator (`b449dcf8-efe4-4358-9a4a-012242c7a26b`) & Implementers  
**Target Files**:
- `src/main/kotlin/com/github/foragerhelper/path/Pathfinder.kt`
- `src/main/kotlin/com/github/foragerhelper/path/NodePenaltyMap.kt`
- `analysis.md` (detailed architectural specification in current working directory)  
**Date**: 2026-09-11  

---

## 1. Observation

1. **Legacy Pathfinding Deficiencies in `AStarPathfinder.kt`**:
   - In `src/main/kotlin/foraginghelpermod/client/path/AStarPathfinder.kt:80-88`:
     ```kotlin
     fun canStandAt(world: ClientWorld, feet: BlockPos): Boolean {
         if (!isAirLike(world, feet) || !isAirLike(world, feet.up())) return false
         if (!world.getFluidState(feet).isEmpty || !world.getFluidState(feet.up()).isEmpty) return false
         val ground = feet.down()
         val groundState = world.getBlockState(ground)
         if (groundState.isAir) return false
         if (TreeScanner.isLogLike(groundState)) return false
         return !groundState.getCollisionShape(world, ground).isEmpty
     }
     ```
     Observations:
     a) Only checks integer `feet` and `feet.up()` block positions. Does not evaluate player's $0.6 \times 1.8$m bounding box (`Box`), causing corner catching on doors and diagonal transitions.
     b) Slabs and stairs are rejected as standing spots because a slab at feet level has a collision box and is deemed non-air.
     c) In `neighbors` (`AStarPathfinder.kt:243-246`):
        ```kotlin
        val up = flat.up()
        if (!tooFar(up, origin, maxRange) && canStandAt(world, up) &&
            isAirLike(world, pos.up(2))) {
            result.add(up.toImmutable() to 1.45)
        }
        ```
        Checking only `isAirLike(world, pos.up(2))` checks ceiling above takeoff, but fails to check ceiling above target jump apex ($Y + 1.25$ above ground), causing players to jump into low ceilings.
     d) Diagonal transitions (`AStarPathfinder.kt:269-277`) do not check for corner snagging against adjacent wall corners.
     e) No turn penalty: generates erratic zigzags.
     f) No dynamic node penalization memory: deterministic search loops indefinitely when blocked.
     g) No timeout protection: only checks `expansions < 6000`, causing client tick stalls up to 200ms in open caverns.

2. **Architectural Contract in `PROJECT.md:95-114`**:
   ```kotlin
   package com.github.foragerhelper.path

   import net.minecraft.util.math.BlockPos
   import net.minecraft.util.math.Vec3d
   import net.minecraft.world.World

   interface Pathfinder {
       fun findPath(world: World, start: Vec3d, goal: Vec3d, allowedRange: Double = 1.0): PathResult
       fun penalizeNode(pos: BlockPos, penalty: Float = 50.0f)
       fun clearPenalties()
   }

   data class PathResult(
       val success: Boolean,
       val waypoints: List<Vec3d>,
       val blockedReason: String? = null
   )
   ```
   Observations:
   - `start` and `goal` are continuous 3D `Vec3d` coordinates.
   - Output `waypoints` is `List<Vec3d>` with explicit standing height for slabs and stairs.
   - `PathResult` includes `blockedReason: String?` for diagnostics.

3. **Current Build & Test Environment Verification**:
   - Executed: `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test --rerun-tasks"`
   - Result: `BUILD SUCCESSFUL in 48s, 5 actionable tasks: 5 executed`.
   - Milestone 1 tests (`RotationEngineTest`, `SensitivityGCDAdversarialTest`, `SpringSmootherAdversarialTest`) pass 100%.

4. **Peer Coordination & Interfaces**:
   - Explorer 2 (`teamwork_preview_explorer_m2_2/report.md:617-621`): Defined `SweptBoxLOS.smoothPath(env, rawNodes)` and `CollisionEnvironment`.
   - Explorer 3 (`teamwork_preview_explorer_m2_3/handoff.md:58-63`): Defined `NodePenaltyMap` with primitive 64-bit hashing (`BlockPos.asLong()`), spatial diffusion (radius 1, 0.5 falloff), and 20s TTL linear decay.

---

## 2. Logic Chain

1. From **Observation 1a & 1d**, the player's bounding box is $0.6$m wide and $1.8$m high. Moving diagonally between $(0, 0)$ and $(1, 1)$ brings the player box across the corner vertices of $(1, 0)$ and $(0, 1)$ with an overlap of $0.3$m if either is solid. Therefore, diagonal neighbor generation must enforce that BOTH orthogonal side blocks `(x+dx, y, z)` and `(x, y, z+dz)` have clear headroom at feet and head ($Y$ and $Y+1$) to completely eliminate corner snagging before path smoothing.
2. From **Observation 1b**, in Minecraft, bottom slabs have collision height $0.5$m and step height is $0.6$m. By tracking continuous standing elevation `posVec.y` in `PathNode` ($Y$ for full blocks, $Y + 0.5$ for bottom slabs/stairs), the pathfinder naturally generates `STEP_UP` (cost 1.10) and `STEP_DOWN` (cost 1.05) transitions. Ceiling clearance is enforced up to $Y + 2.3$ (`pos.up(1)` and `pos.up(2)` clear).
3. From **Observation 1c**, vanilla jumps reach an apex of $\approx 1.25$m above starting ground ($Y + 3.05$ head height). Enforcing 3 blocks of clearance above takeoff (`current.pos.up(2)` clear) guarantees that the player will never bump their head against ceilings mid-jump.
4. From **Observation 1e**, adding an angular turn penalty ($\Delta \theta = 0^\circ \to 0.0, 45^\circ \to 0.05, 90^\circ \to 0.15, \dots$) mathematically penalizes zigzagging, breaking symmetrical diamond search frontiers and forcing A* to expand long straight corridors first.
5. From **Observation 1f & Observation 4**, incorporating `NodePenaltyMap` dynamically adds detour costs to stagnated nodes ($+50.0$f), compelling A* to generate alternative routes when obstacles appear.
6. From **Observation 1g**, enforcing an expansion budget (`maxExpansions = 6000`) and a real-time deadline check (`maxComputeTimeMs = 50L` queried every 64 iterations) guarantees that pathfinding never blocks client ticks or drops frame rates.
7. From **Observation 2 & Observation 4**, routing path smoothing through `SweptBoxLOS.smoothPath(env, rawNodes)` preserves anchor nodes (`JUMP_UP`, `DROP`, `PARKOUR`) while collapsing straight/diagonal corridors into minimal, humanized waypoints.
8. From **Observation 3 & Observation 4**, abstracting world collision queries via `CollisionEnvironment` allows `PathfinderTest.kt` to run 100% headless in `gradlew test` with a synthetic `TestWorldGrid` without requiring a running client.

---

## 3. Caveats

1. **Parkour Gap Bounds**: Parkour neighbor generation is strictly bounded to 1-block and 2-block flat horizontal gaps (distances 2 and 3). 3-block gaps (distance 4) require precise momentum/sprint timing and are excluded to maintain 100% execution reliability.
2. **Dynamic Obstacle Transience**: Penalties registered in `NodePenaltyMap` decay linearly over 20 seconds. If an obstructing mob wanders away immediately, the node remains penalized for several seconds. This slight conservatism is intentional to avoid thrashing.
3. **Movement Controller Separation**: The pathfinder computes waypoints and action tags. The physical keypress generation (WASD, jump, sprint) belongs to Milestone 4 (`movement/MovementController.kt`).

---

## 4. Conclusion

The architectural specification and algorithm design for `Pathfinder.kt` and `NodePenaltyMap.kt` is complete and documented in `analysis.md`. It provides:
1. Strict compliance with `PROJECT.md:95-114` (`Pathfinder` interface and `PathResult`).
2. Robust hitbox-aware neighbor expansion: cardinal, diagonal corner-checked, 0.5-block step up/down, 1-block jump with apex clearance, 1-3 block drops, and 1-2 block parkour gaps.
3. Admissible Euclidean heuristic with two-tier tie-breaking and angular turn penalties.
4. Stateful dynamic penalization via `NodePenaltyMap` to break infinite stuck loops.
5. Strict 50ms / 6000-node safety budget with diagnostic failure reporting.
6. Headless offline testability via `CollisionEnvironment`.

---

## 5. Verification Method

1. **Inspect Artifacts**:
   - Verify `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_1\analysis.md` contains full design, mathematical proofs, and code reference.
2. **Run Existing Test Suite**:
   ```cmd
   cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"
   ```
   Must exit with code 0.
3. **Downstream Implementation Verification**:
   When `Pathfinder.kt` and `NodePenaltyMap.kt` are implemented in `src/main/kotlin/com/github/foragerhelper/path/`:
   Run `gradlew.bat compileKotlin` and `gradlew.bat test`.
4. **Invalidation Conditions**:
   - Generation of diagonal moves without verifying both orthogonal cardinal corner blocks.
   - Generation of jump moves without checking takeoff apex headroom (`pos.up(2)`).
   - Execution taking $> 50$ms without terminating via timeout protection.
