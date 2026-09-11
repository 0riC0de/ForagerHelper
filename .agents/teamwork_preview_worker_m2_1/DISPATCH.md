## 2026-09-11T19:41:46Z
You are Worker 1 for Milestone 2 (Hitbox-Aware 3D A* Pathfinder).
Your working directory is: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_worker_m2_1

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

MANDATORY FIRST STEP:
Read c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md (authoritative requirements).
Also read:
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\TEST_INFRA.md
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_1\analysis.md (3D A* Pathfinder design)
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_1\handoff.md
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_2\analysis.md (SweptBoxLOS design)
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_2\handoff.md
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_3\analysis.md (Vertical traversal & offline test design)
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_3\handoff.md

Your Exclusive Write Ownership:
- `src/main/kotlin/com/github/foragerhelper/path/PathEnvironment.kt`
- `src/main/kotlin/com/github/foragerhelper/path/SweptBoxLOS.kt`
- `src/main/kotlin/com/github/foragerhelper/path/NodePenaltyMap.kt`
- `src/main/kotlin/com/github/foragerhelper/path/Pathfinder.kt`
- `src/test/kotlin/com/github/foragerhelper/path/PathfinderTest.kt`

Tasks:
1. Implement `src/main/kotlin/com/github/foragerhelper/path/PathEnvironment.kt`:
   - Query abstraction providing `isPassable(box: Box)`, `isSolid(pos: BlockPos)`, `getStandHeight(pos: BlockPos)`, and `isHazard(pos: BlockPos)`.
   - Production implementation `WorldPathEnvironment(world: World): PathEnvironment`.
   - Offline test implementation `TestWorldGrid: PathEnvironment` supporting platforms, walls, slabs, stairs, ceilings, and hazards.
2. Implement `src/main/kotlin/com/github/foragerhelper/path/SweptBoxLOS.kt`:
   - Sub-stepping interval delta_s = 0.20m (<= 0.25m), clearance margin delta = 0.05m (effective width 0.70m), foot clearance epsilon_y = 0.02m.
   - Ground support validation (probe footprint, max drop 1.10m, step-up 0.60m) rejecting chasm shortcuts and hazards.
   - Elimination of diagonal corner snagging against obstacle corners while keeping 1-block (1.0m) doorways passable.
   - Path smoothing via string-pulling with slope constraints and anchor node preservation.
3. Implement `src/main/kotlin/com/github/foragerhelper/path/NodePenaltyMap.kt`:
   - Thread-safe Long-keyed spatial penalty storage.
   - Dynamic penalization (+50.0f cost, radius 1 spatial diffusion with 0.5 falloff, 20s TTL decay).
4. Implement `src/main/kotlin/com/github/foragerhelper/path/Pathfinder.kt`:
   - Implement `com.github.foragerhelper.path.Pathfinder` from `PROJECT.md` (`findPath`, `penalizeNode`, `clearPenalties`).
   - 3D A* search with `PathNode` tracking continuous height, g, h, f, parent, and action.
   - Diagonal transitions enforcing that BOTH orthogonal cardinal side blocks are clear at feet and head (Y and Y+1).
   - Step up (0.5m for slabs/stairs), step down, jump up (1.0m with strict 3-block / 2.5m apex headroom check above takeoff), drops (1-3m), parkour gap jumps (1-2m).
   - Angular turn penalties to break zigzags.
   - Iteration budget (6000 nodes) and 50ms compute deadline protection.
5. Author comprehensive unit tests in `src/test/kotlin/com/github/foragerhelper/path/PathfinderTest.kt` using `TestWorldGrid`:
   - Cover Tiers 1-5: cardinal paths, corner clearance without snagging, 1-block doorway pass-through, slab/stair ascent, jump headroom ceiling limits, drops, parkour, dynamic penalization rerouting, swept-box smoothing, and edge cases (start==goal, unreachable target, zero timeout).
6. Verify Build and Test:
   - Run compilation:
     `cmd /c "set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 && cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"`
   - Run test suite:
     `cmd /c "set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 && cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"`
   - Confirm all tests (both existing 55 tests and all new PathfinderTest tests) PASS with 0 failures and 0 errors.
7. Write your handoff report to `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_worker_m2_1\handoff.md`.
8. Send a completion message to your parent with exact test results and handoff path.
