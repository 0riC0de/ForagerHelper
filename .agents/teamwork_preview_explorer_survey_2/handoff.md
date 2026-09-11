# Handoff Report: Target Framework & UI/HUD/Config Survey

**Agent**: `teamwork_preview_explorer_survey_2`  
**Working Directory**: `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_2`  
**Target Milestone**: Survey & Architectural Mapping (R3 Universal Target Framework & UI/HUD/Config)

---

## 1. Observation

### Exact File Paths & Code Locations:
1. **Target Scanning & Selection**:
   - `src/main/kotlin/foraginghelpermod/client/scan/TreeScanner.kt:25-58`: `scanClusters(world, origin, from, radius=12)` searches a cube around `origin` and uses `floodFill` up to `MAX_CLUSTER_SIZE = 96` for blocks matching `isLogLike(state)`.
   - `src/main/kotlin/foraginghelpermod/client/scan/TreeScanner.kt:75-94`: `selectTargetLog` filters logs using `isUsable` and sorts by distance and Y level (`compareBy<BlockPos> { squaredDistance(from, it) }.thenBy { it.y }`).
   - `src/main/kotlin/foraginghelpermod/client/scan/TreeScorer.kt:14-22`: `score(cluster, preferSmall)` computes `sizeTerm + 0.35 * distance`.
   - `src/main/kotlin/foraginghelpermod/client/InputController.kt:43, 97-105, 111-131`: `committedPositions: Set<BlockPos>` locks the bot to all logs of the active tree cluster. Target selection requires both `AStarPathfinder.hasUsableMiningSpot` and `!AStarPathfinder.hasNonLeafMiningBlocker`.
   - `src/main/kotlin/foraginghelpermod/client/InputController.kt:83-87`: Manual destination is treated as a hardcoded special case:
     ```kotlin
     val manualGoal = HelperConfig.manualRouteGoal
     if (manualGoal != null) {
         WalkController.tick(client, manualGoal, REACH, forceRoute = true, exactDestination = true)
         return
     }
     ```

2. **Configuration & Screen**:
   - `src/main/kotlin/foraginghelpermod/client/HelperConfig.kt:8-24`: `object HelperConfig` stores 10 in-memory fields (`enabled`, `autoBreak`, `lookAtTarget`, `showStatusHud`, `sneakWhileActive`, `preferSmallTrees`, `autoWalk`, `useAspectOfVoid`, `showPathOverlay`, `manualRouteGoal: BlockPos?`). No disk persistence.
   - `src/main/kotlin/foraginghelpermod/client/ui/HelperOptionsScreen.kt:18-28`: Displays 9 `OptionRow` checkboxes in an animated glass-style panel (280px width) using `UiTheme.kt` colors and cubic/back easings.
   - `src/main/kotlin/foraginghelpermod/client/ui/HelperOptionsScreen.kt:49, 125-130`: `shouldPause(): Boolean = false`, Esc triggers `requestClose()`.

3. **Status HUD & World Overlay**:
   - `src/main/kotlin/foraginghelpermod/client/hud/StatusHud.kt:15-19`: Registered on `HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, OriForaginHelperMod.id("status"), StatusHud::render)`.
   - `src/main/kotlin/foraginghelpermod/client/hud/StatusHud.kt:38-71`: Directly queries `InputController.treeCount`, `InputController.nearestDistance()`, `InputController.targetLog`, `InputController.selectedTreeSize()`, and `InputController.walkStatus`. Only formats tree log coordinates and tree counts.
   - `src/main/kotlin/foraginghelpermod/client/hud/DebugWorldOverlay.kt:15-28`: Registered on `WorldRenderEvents.END_EXTRACTION`. Uses `GizmoDrawing.box(pos, DrawStyle.stroked(color, 2.0f)).ignoreOcclusion().withLifespan(2)` to draw `WalkController.path` (orange/blue), `InputController.targetLog` (red `0xFFFF3030`), `HelperConfig.manualRouteGoal` (purple `0xFFB060FF`), and `WalkController.etherWarpTarget` (green `0xFF30FF70`).

4. **Keybinds & Commands**:
   - `src/main/kotlin/foraginghelpermod/client/ModKeyBindings.kt:17-25`: Registers `toggleHelper` bound to `GLFW.GLFW_KEY_H`.
   - `src/main/kotlin/foraginghelpermod/client/ManualRouteCommand.kt:14-33`: Registers client command `/forageroute <x> <y> <z>` and `/forageroute clear` via `ClientCommandRegistrationCallback.EVENT`.
   - `src/main/kotlin/foraginghelpermod/client/path/WalkController.kt:267-284`: `restorePhysicalKeyState` queries GLFW hardware states directly (`glfwGetKey` / `glfwGetMouseButton`) when stopping navigation so user keys are not stuck dead.

5. **Gradle Build Verification**:
   - Command executed:
     `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"`
   - Result: Exit code 0, `BUILD SUCCESSFUL in 15s`.

---

## 2. Logic Chain

1. **Premise 1**: The mod requirements (`ORIGINAL_REQUEST.md`) mandate R3: Universal Target Framework supporting `BlockTarget`, `EntityTarget`, and `PositionTarget`, along with pluggable `TargetScanner<T>`, while preserving the existing UI, keybinds, and HUD shell.
2. **Observation Step**: In the existing codebase, `InputController`, `StatusHud`, and `DebugWorldOverlay` explicitly reference `BlockPos` log positions and `TreeCluster` metrics. Mobs and generic blocks have no representation.
3. **Observation Step**: `ManualRouteCommand` sets a `BlockPos` in `HelperConfig.manualRouteGoal`, which causes `InputController` to branch into a separate hardcoded path.
4. **Deduction Step**: If target representation is generalized into `sealed interface NavigationTarget` (`BlockTarget`, `EntityTarget`, `PositionTarget`), then:
   - `ManualRouteCommand` can simply instantiate `PositionTarget(pos)` and submit it to `TargetManager`.
   - Tree cluster scanning can be encapsulated inside `TargetScanner<BlockTarget>`, producing `BlockTarget` instances with face preferences and mining spot evaluation.
   - Mob tracking can be implemented as `TargetScanner<EntityTarget>`, computing dynamic lead focus points from velocity and distance thresholds.
5. **Deduction Step**: To preserve `StatusHud` and `DebugWorldOverlay` without visual regressions:
   - `NavigationTarget` must provide polymorphic methods `describeStatus(player): TargetStatusInfo` and `renderDebug(world, gizmos: GizmoDrawing)`.
   - `StatusHud` renders the title, distance, lock status, and details polymorphically regardless of target type.
   - `DebugWorldOverlay` renders the active target's 3D bounding box (red for blocks, orange for entities, purple for route waypoints) via Fabric 1.21.11 `GizmoDrawing`.
6. **Deduction Step**: `HelperConfig` and `HelperOptionsScreen` can preserve all 10 existing fields and 9 UI rows, guaranteeing zero breakage of existing user settings.

---

## 3. Caveats

- **No Disk Serialization Currently**: `HelperConfig` does not persist options to a JSON/TOML configuration file across Minecraft restarts. Adding disk serialization is desirable in the future but outside the scope of R3.
- **Ether Warp Scope**: `EtherWarpPlanner` currently looks for teleport landing surfaces towards trees or goals. In the new architecture, Ether Warp target selection will query `NavigationTarget.getTargetPos(world)` directly.
- **Physical Key Restoration**: Retaining the GLFW hardware key polling in `MovementController` (from `WalkController.restorePhysicalKeyState`) is essential to prevent user input lockups.

---

## 4. Conclusion

- The existing codebase is compact (18 Kotlin files) and builds cleanly via Gradle under JDK 23 on Windows.
- The target scanning and selection pipeline is cleanly isolatable: `TreeCluster`, `TreeScanner`, and `TreeScorer` can be preserved and adapted into the new `TargetScanner<BlockTarget>` without modifying the core flood-fill math.
- The proposed R3 architecture (`foraginghelpermod/client/target/` with `NavigationTarget`, `BlockTarget`, `EntityTarget`, `PositionTarget`, `TargetScanner<T>`, and `TargetManager`) completely decouples navigation from foraging while enabling dynamic, polymorphic rendering in `StatusHud` and `DebugWorldOverlay`.
- `HelperConfig`, `HelperOptionsScreen`, and `ModKeyBindings` remain 100% backward-compatible.

---

## 5. Verification Method

1. **Gradle Build Verification**:
   Execute the canonical project build command:
   ```powershell
   cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"
   ```
   Must exit with code 0 and zero compilation errors.

2. **Files to Inspect**:
   - Survey Report: `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_2\report.md`
   - Existing UI: `src/main/kotlin/foraginghelpermod/client/ui/HelperOptionsScreen.kt`
   - Existing HUD: `src/main/kotlin/foraginghelpermod/client/hud/StatusHud.kt`
   - Existing Overlay: `src/main/kotlin/foraginghelpermod/client/hud/DebugWorldOverlay.kt`
   - Existing Config: `src/main/kotlin/foraginghelpermod/client/HelperConfig.kt`
   - Existing Tree Scan: `src/main/kotlin/foraginghelpermod/client/scan/TreeScanner.kt`
   - Existing Command: `src/main/kotlin/foraginghelpermod/client/ManualRouteCommand.kt`

3. **Invalidation Conditions**:
   - Any proposed change that breaks existing `HelperConfig` property access in `HelperOptionsScreen`.
   - Any design that requires `StatusHud` or `DebugWorldOverlay` to know the concrete internal details of specific target types rather than using polymorphic methods.
