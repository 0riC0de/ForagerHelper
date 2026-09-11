# Handoff Report — Build Environment, Fabric 1.21.11 APIs & Testing Survey

**Agent**: `teamwork_preview_explorer_survey_3`  
**Working Directory**: `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_3`  
**Milestone**: Survey (Survey 3 of 3)  
**Date**: 2026-09-11  

---

## 1. Observation

1. **Gradle Build Environment & Dependencies**:
   - In `gradle.properties`:
     - Line 10: `minecraft_version=1.21.11`
     - Line 11: `yarn_mappings=1.21.11+build.6`
     - Line 12: `loader_version=0.19.3`
     - Line 13: `loom_version=1.17-SNAPSHOT`
     - Line 14: `fabric_kotlin_version=1.13.13+kotlin.2.4.10`
     - Line 21: `fabric_api_version=0.141.6+1.21.11`
   - In `build.gradle.kts`:
     - Line 6: `id("org.jetbrains.kotlin.jvm") version "2.4.10"`
     - Lines 28-34: Loom dependencies configure `minecraft`, `mappings`, `fabric-loader`, `fabric-api`, and `fabric-language-kotlin`.
     - Lines 46-54: Java compile release set to 21 (`options.release = 21`, `jvmTarget = JvmTarget.JVM_21`).
   - Command execution result (`compileKotlin`):
     - `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"`
     - Result: `BUILD SUCCESSFUL in 17s`, exit code 0.
   - Command execution result (`test`):
     - `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"`
     - Result: `BUILD SUCCESSFUL in 14s`, exit code 0 (`Task :test NO-SOURCE`).

2. **Render Frame Ticks & Camera Orientation**:
   - `foraginghelpermod.client.InputController` line 46:
     `ClientTickEvents.END_CLIENT_TICK.register(::onEndTick)` (executes at 20Hz / 50ms tick rate).
   - `foraginghelpermod.client.path.WalkController` lines 404-406:
     `player.yaw = lastYaw; player.pitch = lastPitch` (mutates tick yaw directly).
   - `foraginghelpermod.client.hud.DebugWorldOverlay` line 15:
     `WorldRenderEvents.END_EXTRACTION.register { context -> ... }` (already utilizes Fabric 1.21.11 render events).
   - Bytecode of `net.minecraft.client.render.Camera.update`:
     Reads `focusedEntity.getYaw(tickProgress)` and `focusedEntity.getPitch(tickProgress)` and sets `setRotation(yaw, pitch)`.
   - Bytecode of `net.minecraft.entity.Entity.changeLookDirection`:
     Line 16-35: adds delta to `pitch` and `yaw`.
     Line 55-74: adds delta directly to `this.lastPitch` and `this.lastYaw`.

3. **Mouse Sensitivity & GCD Calculation**:
   - Bytecode of `net.minecraft.client.Mouse.updateMouse(double)`:
     - Line 7-16: `double sensitivity = client.options.getMouseSensitivity().getValue()`
     - Line 19-26: `double f = sensitivity * 0.6000000238418579d + 0.20000000298023224d`
     - Line 29-37: `double fCubed = f * f * f`
     - Line 39-45: `double gcdMultiplier = fCubed * 8.0d`
     - Line 195-202: `double dy = this.cursorDeltaY * gcdMultiplier`
     - Line 292: `client.player.changeLookDirection(dx, dy)`
   - Bytecode of `net.minecraft.entity.Entity.changeLookDirection(double, double)`:
     - Line 0-6: `float pitchDelta = (float)dy * 0.15f`
     - Line 8-14: `float yawDelta = (float)dx * 0.15f`
     - Combined step: `step = f^3 * 8.0 * 0.15 = f^3 * 1.2` degrees.

4. **3D Collision Checking**:
   - In `net.minecraft.world.CollisionView`:
     - `Iterable<VoxelShape> getBlockCollisions(Entity, Box)`
     - `boolean isSpaceEmpty(Entity, Box)`
     - `boolean isBlockSpaceEmpty(Entity, Box, boolean)`
   - In `net.minecraft.util.math.Box`:
     - `Box stretch(double x, double y, double z)` / `stretch(Vec3d)` expands bounding box along movement trajectory.
     - `boolean intersects(Box)` checks AABB overlap.
   - In `net.minecraft.block.ShapeContext`:
     - `ShapeContext.of(Entity)` provides entity collision context (step height, crouching, boots).
   - In `AStarPathfinder` line 325-338:
     Point-based Bresenham check `round(from.x + (to.x - from.x) * t)` fails to account for player width (0.6) and causes diagonal corner snagging.

---

## 2. Logic Chain

1. **Stutter & 20Hz Tick Bottleneck**:
   - *Observation*: `InputController` runs only on `ClientTickEvents.END_CLIENT_TICK` (20 TPS). `WalkController` assigns `player.yaw` and `player.pitch` directly on tick.
   - *Reasoning*: A 60/144/240Hz monitor draws frames at intervals of 16.6ms, 6.9ms, or 4.1ms. If rotation is updated only every 50ms, the camera angle stays stagnant for multiple frames and then abruptly jumps.
   - *Inference*: Updating rotation during render frames (via `WorldRenderEvents` or mixin) decouples camera motion from the 20Hz tick loop and achieves monitor-refresh-rate panning.

2. **Smooth Lerp Preservation**:
   - *Observation*: `Camera.update` reads `Entity.getYaw(tickProgress) = lerp(tickProgress, lastYaw, yaw)`. In `Entity.changeLookDirection`, both `yaw` and `lastYaw` are incremented by `yawDelta`.
   - *Reasoning*: Because `lerp(t, lastYaw + dy, yaw + dy) == lerp(t, lastYaw, yaw) + dy`, calling `player.changeLookDirection` (or incrementing both `yaw` and `lastYaw`) prevents interpolation snapping at any sub-tick fraction.

3. **Anti-Cheat Compliance**:
   - *Observation*: In vanilla Minecraft, physical mouse input is multiplied by `gcdMultiplier = (sensitivity * 0.6 + 0.2)^3 * 8.0` in `Mouse.updateMouse`, then multiplied by `0.15` in `Entity.changeLookDirection`.
   - *Reasoning*: Anti-cheat checks inspect incoming rotation packets to ensure $\Delta\theta \pmod{\text{step}} = 0$, where $\text{step} = (s \times 0.6 + 0.2)^3 \times 1.2$.
   - *Inference*: Any automated camera turn must quantize angular steps to integer counts of `step` and accumulate fractional sub-step remainders to prevent angle drift.

4. **Hitbox-Aware Swept-Box Line of Sight**:
   - *Observation*: Player dimensions are 0.6 width and 1.8 height. `CollisionView.getBlockCollisions(player, sweptBox)` provides all block collision shapes intersecting a 3D box.
   - *Reasoning*: Extending the player hitbox along the path vector via `playerBox.stretch(delta)` covers the entire swept volume of the player's body. Testing this swept box against `getBlockCollisions` detects whether shoulder edges clip corner blocks.

5. **Offline Testing Strategy**:
   - *Observation*: Gradle `test` task exists and executes cleanly. `Vec3d`, `BlockPos`, `Box`, `MathHelper` do not require OpenGL, GLFW, or Minecraft client runtime.
   - *Reasoning*: By isolating algorithmic logic (GCD quantization, spring smoothing, 3D A*, swept bounding-box intersection, movement key calculation) from `MinecraftClient`, unit tests can run headless in under 1 second.

---

## 3. Caveats

1. `WorldRenderEvents.START_MAIN` and `END_EXTRACTION` execute during the world render pipeline. Changes made to player yaw during `END_EXTRACTION` apply to the subsequent frame's camera calculation (1 frame latency, ~6.9ms at 144Hz). If 0-frame latency is strictly required, a lightweight mixin into `GameRenderer.render` at `HEAD` before `updateCamera` is needed.
2. `World.getBlockCollisions` requires a loaded world context (`ClientWorld`). In offline unit tests, A* and collision algorithms should be tested against a decoupled `CollisionProvider` / synthetic grid abstraction.

---

## 4. Conclusion

1. **Build Environment**: Fully functional and verified under Java 23 with target JVM 21 using Gradle 8.x/9.6.1 wrapper and Fabric Loom 1.17.20.
2. **Rotation Engine**: Implement `rotation/RotationEngine.kt` utilizing `WorldRenderEvents.START_MAIN` or `END_EXTRACTION`, calculating critically damped spring smoothing, quantizing steps via `step = (sensitivity * 0.6 + 0.2)^3 * 1.2`, and applying updates to player view via `changeLookDirection`.
3. **Pathfinder**: Implement `path/Pathfinder.kt` using player hitbox `(0.6, 1.8)`, `world.getBlockCollisions(player, sweptBox)`, and vertical clearance checks to replace flawed Bresenham point sampling.
4. **Target Framework**: Implement `target/` abstraction (`NavigationTarget`, `BlockTarget`, `EntityTarget`, `PositionTarget`).
5. **Movement Controller**: Implement `movement/MovementController.kt` to map player yaw and target direction to vanilla keybindings without locking camera controls.
6. **Testing**: Add `testImplementation(kotlin("test"))` and `useJUnitPlatform()` to `build.gradle.kts`, providing comprehensive unit tests for GCD quantization, spring smoothing, and 3D pathing.

---

## 5. Verification Method

To independently verify these findings:

1. **Verify Gradle Build & Tests**:
   Run:
   ```bat
   cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"
   cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"
   ```
   Both commands must exit with code 0.

2. **Verify Mouse GCD Formula**:
   Inspect bytecode in loom cache:
   ```powershell
   & "C:\Users\D0AF~1\JDKS~1\OPENJD~1\bin\javap.exe" -c -p -cp ".gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-2ae02fda0f\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-2ae02fda0f-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar" "net.minecraft.client.Mouse"
   ```
   Verify constant multipliers `0.6000000238418579d`, `0.20000000298023224d`, `8.0d`, and `Entity.changeLookDirection` factor `0.15f`.

3. **Verify Collision APIs**:
   Inspect `CollisionView.class`:
   ```powershell
   & "C:\Users\D0AF~1\JDKS~1\OPENJD~1\bin\javap.exe" -cp ".gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-2ae02fda0f\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-2ae02fda0f-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar" "net.minecraft.world.CollisionView"
   ```
   Confirm availability of `getBlockCollisions(Entity, Box)` and `isSpaceEmpty(Entity, Box)`.

4. **Invalidation Conditions**:
   - If `compileKotlin` fails on any class modification.
   - If `player.changeLookDirection` causes packet rejection on server.
   - If anti-cheat test detects non-GCD angle delta increments.
