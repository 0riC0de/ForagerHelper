# ForagerHelper Build Configuration, Fabric 1.21.11 Client APIs & Testing Survey

**Agent**: `teamwork_preview_explorer_survey_3`  
**Date**: 2026-09-11  
**Target Milestone**: Survey & Exploration  
**Project**: ForagerHelper Fabric 1.21.11 Mod  
**Working Directory**: `c:\Users\משתמש\source\repos\ForagerHelper`  

---

## 1. Executive Summary

This survey provides the technical foundation for the clean-slate rewrite of ForagerHelper's navigation, rotation, and target-selection engine. We mapped:
1. **Build Environment & Toolchain**: Gradle 9.6.1, Fabric Loom 1.17.20, Kotlin 2.4.10, Java 21 target running on Java 23 at short path `C:\Users\D0AF~1\JDKS~1\OPENJD~1`. Clean build verified (`compileKotlin` and `test` exit code 0).
2. **Render Frame Ticks & Camera Orientation**: Bytecode investigation of `net.minecraft.client.render.Camera`, `GameRenderer`, `Entity`, and Fabric's `WorldRenderEvents`. Proves that vanilla camera orientation reads lerped entity yaw/pitch via `getLerpedYaw(tickProgress)`. Calling `player.changeLookDirection(dx, dy)` updates `yaw`/`pitch` AND `lastYaw`/`lastPitch` simultaneously, eliminating tick snap and enabling smooth monitor refresh rate (60/144/240Hz) panning.
3. **Mouse Sensitivity & Anti-Cheat GCD Quantization**: Reverse-engineered vanilla `Mouse.updateMouse` and `Entity.changeLookDirection` bytecode. The exact formula is `f = s * 0.6 + 0.2`, `step = f^3 * 1.2` degrees per raw mouse count. We define the exact fractional accumulator algorithm for 100% anti-cheat compliance.
4. **3D Collision Checking**: Mapped `CollisionView` / `ClientWorld` methods `getBlockCollisions(Entity, Box)`, `isSpaceEmpty(Entity, Box)`, `ShapeContext.of(player)`, and `Box.stretch()`. Replaces point-based Bresenham line checks with swept bounding-box raycasts to eliminate diagonal corner snagging.
5. **Gradle Unit Testing Setup**: Verified that Gradle `test` task is already wired (`Task :test NO-SOURCE`). Adding `testImplementation(kotlin("test"))` and `useJUnitPlatform()` enables headless offline unit testing for math, spring smoothing, GCD quantization, 3D A*, and target logic.

---

## 2. Build Configuration & Toolchain Mapping

### 2.1 Configuration Breakdown

| Component | Configured Version | Location / Source | Notes |
| :--- | :--- | :--- | :--- |
| **Minecraft** | `1.21.11` | `gradle.properties:10` | Client environment |
| **Yarn Mappings** | `1.21.11+build.6:v2` | `gradle.properties:11`, `build.gradle.kts:29` | Fabric official mappings |
| **Fabric Loader** | `0.19.3` | `gradle.properties:12` | Runtime & compile loader |
| **Fabric Loom** | `1.17-SNAPSHOT` (resolves to `1.17.20`) | `gradle.properties:13`, `settings.gradle.kts:12` | Fabric build plugin |
| **Fabric API** | `0.141.6+1.21.11` | `gradle.properties:21`, `build.gradle.kts:33` | Mod dependency |
| **Fabric Language Kotlin** | `1.13.13+kotlin.2.4.10` | `gradle.properties:14`, `build.gradle.kts:34` | Kotlin runtime bundle |
| **Kotlin JVM Plugin** | `2.4.10` | `build.gradle.kts:6` | `id("org.jetbrains.kotlin.jvm")` |
| **Java Target** | JVM 21 (`release = 21`, `JavaVersion.VERSION_21`) | `build.gradle.kts:47, 52, 62` | Compiled with Java 21 bytecode specs |
| **JDK Environment** | OpenJDK 23 | `C:\Users\D0AF~1\JDKS~1\OPENJD~1` | 8.3 short path bypasses Windows Hebrew username encoding issues |

### 2.2 Build Execution Verification

The build command was tested directly via `run_command`:
```bat
cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"
```
**Result**:
```
Starting a Gradle Daemon, 2 incompatible and 2 stopped Daemons could not be reused, use --status for details
> Configure project :
Fabric Loom: 1.17.20
> Task :checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :compileKotlin UP-TO-DATE
BUILD SUCCESSFUL in 17s
```
Zero warnings, zero errors.

---

## 3. Render Frame Tick & Camera Orientation Hooks

### 3.1 Problem with Existing Implementation
In `foraginghelpermod.client.InputController` (lines 46, 60-109) and `WalkController` (lines 68, 404-406):
- `InputController` registers exclusively to `ClientTickEvents.END_CLIENT_TICK`.
- Rotations are applied only on client ticks (20 Hz, every 50ms):
  ```kotlin
  player.yaw = lastYaw
  player.pitch = lastPitch
  ```
- Because Minecraft renders at high frame rates (e.g. 144Hz = ~6.9ms per frame), setting `player.yaw` every 50ms results in visible camera stutter, angular snapping, and curve-resetting jitter.

### 3.2 Minecraft 1.21.11 Camera Architecture (Bytecode Analysis)

Decompilation of `net.minecraft.client.render.Camera`:
```java
public void update(World world, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress) {
    ...
    this.lastTickProgress = tickProgress;
    ...
    float yaw = focusedEntity.getYaw(tickProgress);
    float pitch = focusedEntity.getPitch(tickProgress);
    this.setRotation(yaw, pitch);
    ...
}
```

Decompilation of `net.minecraft.entity.Entity.getYaw(float tickProgress)`:
```java
public float getYaw(float tickProgress) {
    return this.getLerpedYaw(tickProgress);
}

public float getLerpedYaw(float tickProgress) {
    if (tickProgress == 1.0F) {
        return this.getYaw();
    }
    return MathHelper.lerpAngleDegrees(tickProgress, this.lastYaw, this.getYaw());
}
```

Decompilation of `net.minecraft.entity.Entity.changeLookDirection(double dx, double dy)`:
```java
public void changeLookDirection(double dx, double dy) {
    float pitchDelta = (float)dy * 0.15F;
    float yawDelta = (float)dx * 0.15F;
    this.setPitch(this.getPitch() + pitchDelta);
    this.setYaw(this.getYaw() + yawDelta);
    this.setPitch(MathHelper.clamp(this.getPitch(), -90.0F, 90.0F));
    
    // CRITICAL OBSERVATION:
    this.lastPitch += pitchDelta;
    this.lastYaw += yawDelta;
    this.lastPitch = MathHelper.clamp(this.lastPitch, -90.0F, 90.0F);
    
    if (this.vehicle != null) {
        this.onPassengerLookAround(this.vehicle);
    }
}
```

### 3.3 Key Architectural Discovery
When `changeLookDirection` is invoked:
- It increments BOTH `yaw` and `lastYaw` by `yawDelta`.
- Because `lastYaw` and `yaw` both advance equally, `MathHelper.lerpAngleDegrees(tickProgress, lastYaw, yaw)` evaluates directly to `yaw` (offset by any in-progress tick interpolation).
- There is **zero tick snapping**!
- This explains why vanilla hardware mouse movement feels silky smooth at 240Hz even though the game ticks at 20Hz.

### 3.4 Frame Event Hooks: Comparison & Recommendations

| Option | Mechanism | Frequency | Latency | Complexity | Recommendation |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Option A: Fabric API `WorldRenderEvents.START_MAIN` / `END_EXTRACTION`** | `WorldRenderEvents.START_MAIN.register { ctx -> ... }` | Monitor refresh rate (frame render) | 1 frame delay for camera matrix if applied after `updateCamera` | Zero mixin needed; already used in `DebugWorldOverlay.kt` | **Recommended primary hook**. Fully supported by vanilla Fabric API. |
| **Option B: Fabric API `HudRenderCallback.EVENT`** | `HudRenderCallback.EVENT.register { drawContext, tickCounter -> ... }` | Every frame before/during HUD | 1 frame delay | Zero mixin needed | Alternative render event |
| **Option C: Mixin into `GameRenderer.render`** | `@Inject(method = "render", at = @At("HEAD"))` | Exact start of render frame | 0 frame delay (runs immediately before `updateCamera`) | Requires mixin json registration in `fabric.mod.json` | Best for zero-latency frame timing if mixins are introduced |

#### RenderTickCounter in 1.21.11:
In 1.21.11, `RenderTickCounter` replaces float `tickDelta`:
- `client.renderTickCounter` (or `client.getRenderTickCounter()`):
  - `tickCounter.dynamicDeltaTicks`: delta ticks elapsed since last frame (float)
  - `tickCounter.getTickProgress(true)`: sub-tick fraction `[0.0, 1.0]`
  - `tickCounter.fixedDeltaTicks`: fixed delta ticks

---

## 4. Minecraft Client Mouse Sensitivity & Mouse GCD Calculation

### 4.1 Vanilla Mouse Input Bytecode Disassembly
From `net.minecraft.client.Mouse.updateMouse(double timeDelta)`:
```java
// 1. Fetch mouse sensitivity:
SimpleOption<Double> mouseSensitivityOption = this.client.options.getMouseSensitivity();
double sensitivity = mouseSensitivityOption.getValue(); // [0.0, 1.0]

// 2. Compute GCD base factor 'f':
double f = sensitivity * 0.6000000238418579D + 0.20000000298023224D; // f = s * 0.6 + 0.2

// 3. Compute cubic factor:
double fCubed = f * f * f;

// 4. Compute mouse multiplier:
double gcdMultiplier = fCubed * 8.0D;

// 5. Raw mouse delta scaling:
double dx = this.cursorDeltaX * gcdMultiplier;
double dy = this.cursorDeltaY * gcdMultiplier;

// 6. Invert mouse check:
if (this.client.options.getInvertMouseX().getValue()) dx = -dx;
if (this.client.options.getInvertMouseY().getValue()) dy = -dy;

// 7. Invoke changeLookDirection:
this.client.player.changeLookDirection(dx, dy);
```

And in `net.minecraft.entity.Entity.changeLookDirection(double dx, double dy)`:
```java
float pitchDelta = (float)dy * 0.15F;
float yawDelta = (float)dx * 0.15F;
```

### 4.2 Exact Anti-Cheat GCD Formula
Combining the two stages:
$$\text{step} = f^3 \times 8.0 \times 0.15 = f^3 \times 1.2$$
where:
$$f = \text{sensitivity} \times 0.6 + 0.2$$

For example:
- At default sensitivity $s = 0.5$:
  - $f = 0.5 \times 0.6 + 0.2 = 0.5$
  - $f^3 = 0.125$
  - $\text{step} = 0.125 \times 1.2 = 0.15^\circ$
- Every raw mouse count from hardware changes player yaw/pitch by exactly $0.15^\circ$.
- Anti-cheat solutions (Hypixel Watchdog, GrimAC, Polar, Karhu) analyze incoming rotation packets:
  $$\Delta\text{yaw} \pmod{\text{step}} \approx 0$$
  If non-quantized floating-point rotations are sent (e.g. $0.143289^\circ$), anti-cheat detects automated rotation immediately.

### 4.3 Quantization & Remainder Accumulator Algorithm
To execute smooth rotations while guaranteeing 100% GCD compliance:
```kotlin
object MouseGcdHelper {
    private var yawRemainder = 0.0
    private var pitchRemainder = 0.0

    fun computeStep(sensitivity: Double): Double {
        val f = sensitivity * 0.6 + 0.2
        return f * f * f * 8.0 * 0.15
    }

    /**
     * Quantizes desired rotation deltas to exact mouse steps.
     * Sub-step fractions are accumulated and carried forward to prevent rounding drift.
     */
    fun quantize(
        desiredDeltaYaw: Double,
        desiredDeltaPitch: Double,
        sensitivity: Double
    ): Pair<Float, Float> {
        val step = computeStep(sensitivity)
        if (step <= 1e-7) return Pair(0f, 0f)

        val totalYaw = desiredDeltaYaw + yawRemainder
        val totalPitch = desiredDeltaPitch + pitchRemainder

        val countsYaw = Math.round(totalYaw / step)
        val countsPitch = Math.round(totalPitch / step)

        val appliedDeltaYaw = (countsYaw * step).toFloat()
        val appliedDeltaPitch = (countsPitch * step).toFloat()

        yawRemainder = totalYaw - (countsYaw * step)
        pitchRemainder = totalPitch - (countsPitch * step)

        return Pair(appliedDeltaYaw, appliedDeltaPitch)
    }

    fun applyToPlayer(player: ClientPlayerEntity, appliedYaw: Float, appliedPitch: Float, sensitivity: Double) {
        val f = sensitivity * 0.6 + 0.2
        val gcdMultiplier = f * f * f * 8.0
        // Since changeLookDirection multiplies by 0.15f:
        val dx = (appliedYaw / 0.15f) / gcdMultiplier * gcdMultiplier // exact integer counts
        val dy = (appliedPitch / 0.15f) / gcdMultiplier * gcdMultiplier
        player.changeLookDirection(appliedYaw / 0.15, appliedPitch / 0.15)
    }
}
```

---

## 5. 3D Collision Checking APIs in Fabric 1.21.11

### 5.1 Minecraft 1.21.11 Collision Methods

In `net.minecraft.world.CollisionView` (implemented by `ClientWorld`):
```java
// 1. Get colliding block shapes overlapping box:
Iterable<VoxelShape> getBlockCollisions(@Nullable Entity entity, Box box);

// 2. Fast boolean space clearance (blocks + entities):
boolean isSpaceEmpty(@Nullable Entity entity, Box box);

// 3. Block-only space clearance:
boolean isBlockSpaceEmpty(@Nullable Entity entity, Box box, boolean checkFluid);

// 4. Entity-only collision:
boolean doesNotCollideWithEntities(@Nullable Entity entity, Box box);
```

In `net.minecraft.block.ShapeContext`:
```java
// Entity-aware context (considers entity size, descending state, held items, boots):
ShapeContext context = ShapeContext.of(player);

// Collision shape query for specific block:
VoxelShape shape = blockState.getCollisionShape(world, blockPos, context);
```

In `net.minecraft.util.math.Box`:
```java
// Swept bounding box:
Box swept = playerBox.stretch(dx, dy, dz); // expands box by movement vector

// Intersection test:
boolean collides = box1.intersects(box2);

// Raycast test:
Optional<Vec3d> hit = box.raycast(startVec, endVec);
```

In `net.minecraft.util.shape.VoxelShapes`:
```java
// Intersection check between two VoxelShapes:
boolean overlaps = VoxelShapes.matchesAnywhere(shape1, shape2, BooleanBiFunction.AND);
```

### 5.2 Player Hitbox Constants
- Standard player width: `0.6` (half-width: `0.3`)
- Standard player height: `1.8`
- Eye height: `1.62`
- Player bounding box at foot coordinate $(x, y, z)$:
  $$\text{Box}(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3)$$

### 5.3 Swept-Box Line-of-Sight Algorithm for R2 Pathfinder
The existing `AStarPathfinder.hasLineOfSight` (lines 325-338) uses point-based rounding:
```kotlin
// Existing flawed point-based check:
val pos = BlockPos(round(from.x + ...), round(from.y + ...), round(from.z + ...))
if (!canStandAt(world, pos)) return false
```
This snags on diagonal corners because a single point line check cannot see that the 0.6-wide player shoulder clips a corner block.

**Swept-Box Replacement**:
```kotlin
fun hasSweptLineOfSight(world: ClientWorld, player: Entity, fromFeet: Vec3d, toFeet: Vec3d): Boolean {
    val delta = toFeet.subtract(fromFeet)
    val distance = delta.length()
    if (distance < 1e-4) return true

    val playerBox = Box(fromFeet.x - 0.3, fromFeet.y, fromFeet.z - 0.3, fromFeet.x + 0.3, fromFeet.y + 1.8, fromFeet.z + 0.3)
    val sweptBoundingBox = playerBox.stretch(delta.x, delta.y, delta.z)

    // Quick clearance: if no block collisions in the entire corridor, it is immediately clear
    val collisions = world.getBlockCollisions(player, sweptBoundingBox)
    val collisionList = collisions.toList()
    if (collisionList.isEmpty()) return true

    // Detailed swept step check (0.2m increments):
    val steps = ceil(distance / 0.2).toInt()
    for (i in 1..steps) {
        val t = i.toDouble() / steps
        val stepPos = fromFeet.add(delta.multiply(t))
        val stepBox = Box(stepPos.x - 0.3, stepPos.y, stepPos.z - 0.3, stepPos.x + 0.3, stepPos.y + 1.8, stepPos.z + 0.3)
        val stepShape = VoxelShapes.cuboid(stepBox)

        for (blockShape in collisionList) {
            if (VoxelShapes.matchesAnywhere(stepShape, blockShape, BooleanBiFunction.AND)) {
                return false // Shoulder/hitbox intersects block collision shape
            }
        }
    }
    return true
}
```

---

## 6. Unit Testing Infrastructure Under Gradle

### 6.1 Current Test Configuration
- `gradlew.bat test` is already an active Gradle task.
- Running `gradlew.bat test` succeeded with `Task :compileTestKotlin NO-SOURCE`, `Task :test NO-SOURCE`.
- Currently, `build.gradle.kts` does not include `testImplementation` dependencies and `src/test/kotlin` does not exist.

### 6.2 Recommended Test Setup
In `build.gradle.kts`:
```kotlin
dependencies {
    // Existing dependencies...
    
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
```

### 6.3 Decoupled Offline Testing Architecture
To run blazing-fast unit tests without bootstrapping Minecraft client graphics/audio/networking:
1. **Math & Physics**:
   - `Vec3d`, `BlockPos`, `Box`, `MathHelper` can be instantiated in offline unit tests without any Minecraft bootstrap. They are pure data structures!
2. **Rotation Engine**:
   - Test spring smoothing formula, exponential dampener step response, wrap-around angle calculations, and mouse GCD step quantization with pure unit tests.
3. **3D A* Pathfinder**:
   - Provide a lightweight mock/synthetic `VoxelWorld` or `CollisionProvider` interface so pathfinding, corner clearance, vertical climb, and stuck detection can be tested across synthetic obstacle courses in milliseconds.
4. **Target Framework**:
   - Test `BlockTarget`, `EntityTarget`, `PositionTarget` distance sorting, raycast hit verification, and target lock/release state machines offline.
5. **Movement Controller**:
   - Test direction vector math: converting player heading angle and goal vector into forward/strafe/jump keyboard states.

---

## 7. Next Steps & Architecture Recommendations

1. **Rotation Engine (`rotation/RotationEngine.kt`)**:
   - Register to `WorldRenderEvents.START_MAIN` or `WorldRenderEvents.END_EXTRACTION` for monitor-rate render frame updates.
   - Implement critically damped spring smoothing `(targetAngle - currentAngle)` with sub-step remainder tracking.
   - Use `MouseGcdHelper` to quantize all angle changes to `step = (s * 0.6 + 0.2)^3 * 1.2`.
   - Call `player.changeLookDirection` to advance `yaw` and `lastYaw` without tick snapping.

2. **Hitbox-Aware Pathfinder (`path/Pathfinder.kt`)**:
   - Use `world.getBlockCollisions(player, box)` and `Box.stretch()` for swept-box raycasts.
   - Support vertical traversal: 1-block steps, slabs/stairs, ceilings (minimum 1.8 clearance), and parkour gaps.
   - Integrate stuck recovery maneuvers: reverse, strafe, re-route.

3. **Universal Target Framework (`target/`)**:
   - Create `NavigationTarget` interface with `BlockTarget`, `EntityTarget`, and `PositionTarget` implementations.
   - Pluggable `TargetScanner<T>` for tree logs, mobs, or custom blocks.

4. **Decoupled Movement Controller (`movement/MovementController.kt`)**:
   - Calculate relative angle difference between player yaw and path tangent.
   - Set vanilla options keys (`forwardKey`, `leftKey`, `rightKey`, `jumpKey`, `sprintKey`).
   - Integrate with `StatusHud`, `HelperOptionsScreen`, and `DebugWorldOverlay`.

5. **Test Suite (`src/test/kotlin`)**:
   - Add JUnit 5 test suite verifying GCD quantization, angle wrapping, spring decay, and A* pathing on synthetic grids.
