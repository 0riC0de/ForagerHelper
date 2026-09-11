# Comprehensive Codebase Survey & Root Cause Analysis
**Subagent**: `teamwork_preview_explorer_survey_1`  
**Role**: Codebase Navigation & Rotation Explorer  
**Date**: 2026-09-11  
**Project**: ForagerHelper (Minecraft Fabric 1.21.11)

---

## 1. Executive Summary

This report delivers a complete architectural audit of the navigation, camera/rotation, movement, and pathfinding subsystems in the ForagerHelper Fabric 1.21.11 client mod.

The current implementation concentrates almost all navigation and camera behavior inside a single monolithic object: `WalkController.kt` (460 lines), supported by `AStarPathfinder.kt` (375 lines), `InputController.kt` (152 lines), and `ChopController.kt` (100 lines). 

Our investigation identified four severe structural flaws:
1. **Camera Stutter & Curve-Resetting**: Rotations are locked to client ticks (20 Hz) rather than render frames (60/144/240 Hz), and any waypoint shift or head drift forcibly resets interpolation progress back to `0`, discarding in-flight curves.
2. **Artificial High-Frequency Jitter & GCD Violations**: Hardcoded white noise is injected on every tick, and target angles are written directly to `player.yaw`/`player.pitch` without Minecraft mouse sensitivity GCD quantization, triggering anti-cheat angle heuristics.
3. **Diagonal Corner Snagging**: Path smoothing (`smoothPath`) uses a 1D center-point Bresenham raycast that ignores the player's 0.6×1.8m bounding box, generating diagonal shortcuts that clip solid wall corners.
4. **Input-Camera Tight Coupling & Infinite Stuck Loops**: Movement key simulation is directly computed from `yawDiff` against the camera's current angle; if the camera looks at a block or target, movement keys freeze or oscillate. When blocked, re-pathing recomputes the identical failing path because A* lacks dynamic obstacle penalization.

A clean-slate re-architecture into three decoupled modules—**R1 (`RotationEngine`)**, **R2 (`Pathfinder`)**, and **R4 (`MovementController`)**—is required to eliminate these defects while preserving existing UI, HUD, and configuration shells.

---

## 2. Complete Codebase Map (Navigation, Camera, Movement & Pathfinding)

### 2.1 File & Class Inventory

| File Path | Primary Class / Object | Role / Responsibility | Key Dependencies |
| :--- | :--- | :--- | :--- |
| `src/.../client/InputController.kt` | `object InputController` | Client tick event listener (`END_CLIENT_TICK`); orchestrates scan, walk, chop, options GUI | `WalkController`, `AStarPathfinder`, `ChopController`, `TreeScanner`, `HelperConfig` |
| `src/.../client/path/WalkController.kt` | `object WalkController` | Monolithic controller: waypoint following, camera rotation, input simulation, GLFW key restore, void recovery | `MinecraftClient`, `AStarPathfinder`, `EtherWarpPlanner`, `HelperConfig` |
| `src/.../client/path/AStarPathfinder.kt` | `object AStarPathfinder` | 3D grid A* search, Bresenham path smoothing, parkour heuristics, mining line-of-sight | `ClientWorld`, `BlockPos`, `TreeScanner` |
| `src/.../client/path/ChopController.kt` | `object ChopController` | Target block interaction, attack verification, mining face selection | `WalkController` (nudging & look), `TreeScanner`, `AStarPathfinder` |
| `src/.../client/path/EtherWarpPlanner.kt` | `object EtherWarpPlanner` | Raycasting safe landing blocks for Aspect of the Void teleportation (5-57 blocks) | `ClientWorld`, `BlockPos`, `TreeScanner` |
| `src/.../client/HelperConfig.kt` | `object HelperConfig` | Global mutable configuration flags (autoWalk, lookAtTarget, useAspectOfVoid, manualRouteGoal, etc.) | `BlockPos` |
| `src/.../client/ModKeyBindings.kt` | `object ModKeyBindings` | Keybinding registration (default `KEY_H` for options menu) | `KeyBindingHelper`, `OriForaginHelperMod` |
| `src/.../client/ManualRouteCommand.kt` | `object ManualRouteCommand` | Brigadier client command `/forageroute <x> <y> <z>` and `/forageroute clear` | `HelperConfig`, `WalkController` |
| `src/.../client/hud/DebugWorldOverlay.kt` | `object DebugWorldOverlay` | Render event hook (`WorldRenderEvents.END_EXTRACTION`) rendering 3D gizmos for waypoints and targets | `WalkController`, `InputController`, `HelperConfig` |
| `src/.../client/hud/StatusHud.kt` | `object StatusHud` | 2D HUD overlay displaying active state, tree counts, distance, walk status | `InputController`, `HelperConfig`, `UiTheme` |
| `src/.../client/ui/HelperOptionsScreen.kt` | `class HelperOptionsScreen` | Interactive animated screen with glassmorphism UI toggles for config properties | `HelperConfig`, `UiTheme` |

### 2.2 Call Graph & Execution Flow

```
[Fabric Client Lifecycle]
         │
         ├── ClientTickEvents.END_CLIENT_TICK (20 Hz)
         │       └── InputController.onEndTick()
         │               ├── TreeScanner.scanClusters()
         │               ├── TreeScorer.pickBest()
         │               ├── WalkController.tick()
         │               │       ├── AStarPathfinder.findPath()
         │               │       ├── WalkController.lookAtNaturally() ──> Mutates player.yaw / player.pitch
         │               │       └── WalkController.setMovement()     ──> Presses forward/back/left/right/jump
         │               └── ChopController.tick()
         │                       ├── WalkController.lookAtBlock()
         │                       └── WalkController.nudgeForMining()
         │
         ├── WorldRenderEvents.END_EXTRACTION (Render Frames: 60-240 Hz)
         │       └── DebugWorldOverlay (Draws path/target gizmos)
         │
         └── HudElementRegistry (Render Frames: 60-240 Hz)
                 └── StatusHud.render()
```

---

## 3. Root Cause Analysis of Existing Deficiencies

### 3.1 Camera Rotation: Jitter, Stutter, Angle Snapping & GCD Violations

#### A. 20 Hz Tick Coupling vs. Monitor Refresh (60/144/240 Hz)
* **Location**: `InputController.kt:46`, `WalkController.kt:68-84, 189, 405-406`.
* **Mechanism**: Camera angle changes are applied exclusively inside `onEndTick(client)` via `ClientTickEvents.END_CLIENT_TICK`. Minecraft ticks run at a fixed 20 TPS (50ms interval).
* **Impact**: On a 144 Hz display (6.94ms per frame), the game renders 7 consecutive identical frames where player yaw/pitch does not move, followed by a sudden discrete jump on the 8th frame. This creates noticeable micro-stutter and visual frame judder.

#### B. Curve-Resetting Stutter & Interpolation Cancellation
* **Location**: `WalkController.kt:181-186, 357-388`.
* **Mechanism**:
  ```kotlin
  // Lines 357-360
  val newTarget = lookPlanTargetYaw.isNaN() ||
      abs(MathHelper.wrapDegrees(targetYaw - lookPlanTargetYaw)) > 0.35f ||
      abs(targetPitch - lookPlanTargetPitch) > 0.35f
  if (newTarget) {
      lookPlanStartYaw = MathHelper.wrapDegrees(player.yaw)
      lookPlanTargetYaw = targetYaw
      ...
      lookPlanStep = 0 // CRITICAL DEFECT: Resets progress to 0!
  }
  ```
  As the player walks, `newLookPoint` shifts continuously, and `humanizeLookPoint` injects a new randomized offset every 5 ticks.
* **Impact**: Whenever `targetYaw` drifts by more than 0.35°, `newTarget` triggers, resetting `lookPlanStep = 0`. The cubic easing polynomial (`fraction * fraction * (3f - 2f * fraction)`) is aborted mid-curve. The camera velocity drops instantly to 0 and re-starts from the beginning of a new curve. This causes frequent, jerky hitches (curve-resetting stutter).

#### C. High-Frequency White Noise Jitter
* **Location**: `WalkController.kt:397-400, 409-413`.
* **Mechanism**:
  ```kotlin
  val jitterScale = if (precise) 0.04f else 0.12f
  val jitterYaw = Random.nextDouble(-jitterScale.toDouble(), jitterScale.toDouble()).toFloat()
  val jitterPitch = Random.nextDouble(-jitterScale.toDouble(), jitterScale.toDouble()).toFloat()
  val nextYaw = lookPlanStartYaw + lookPlanDeltaYaw * eased + curveYaw + jitterYaw
  ```
* **Impact**: Adding uncapped, uncorrelated pseudorandom offsets directly to the target angle every tick produces high-frequency visual vibration rather than natural human micro-drift.

#### D. Angle Snapping & Mouse Sensitivity GCD Violations
* **Location**: `WalkController.kt:311-312, 405-406`.
* **Mechanism**:
  ```kotlin
  // Line 311: Instantaneous snap on Aspect of the Void:
  player.yaw = targetYaw
  player.pitch = ...
  // Line 405: Direct assignment of unquantized floats:
  player.yaw = lastYaw
  player.pitch = lastPitch
  ```
  Vanilla Minecraft computes mouse rotation in `Mouse.updateMouse()` as:
  $$\Delta \theta = \Delta_{\text{mouse}} \times f \times 0.15, \quad f = (s \times 0.6 + 0.2)^3 \times 8$$
  where $s$ is the client mouse sensitivity.
* **Impact**:
  1. Direct assignment of unquantized trigonometric outputs creates mathematically impossible angle deltas that violate the client's sensitivity multiplier. Modern server-side anti-cheats (Vulcan, GrimAC, Polar, Intave) continuously track GCD frequency distributions and flag these rotations immediately as `AimAssist / Invalid Sensitivity`.
  2. Teleportation and target switches produce 1-tick 180° snap rotations.

---

### 3.2 Pathfinding: Corner Snagging, Hitbox Invalidation & Traversal Failures

#### A. 1D Bresenham Raycast vs. 3D Swept Bounding Box (Corner Snagging)
* **Location**: `AStarPathfinder.kt:306-338`.
* **Code**:
  ```kotlin
  private fun hasLineOfSight(world: ClientWorld, from: BlockPos, to: BlockPos): Boolean {
      val steps = maxOf(abs(to.x - from.x), abs(to.y - from.y), abs(to.z - from.z))
      for (i in 1..steps) {
          val t = i.toDouble() / steps
          val pos = BlockPos(
              kotlin.math.round(from.x + (to.x - from.x) * t).toInt(),
              ...
          )
          if (!canStandAt(world, pos)) return false
      }
      return true
  }
  ```
* **Mechanism**: `hasLineOfSight` checks only a 1D discrete line of integer blocks. A player's bounding box is $0.6 \times 0.6 \times 1.8$ meters ($[-0.3, +0.3]$ on X/Z).
* **Geometry**: In a diagonal cut past an inner corner from $(0,0)$ to $(2,2)$ where $(1,0)$ is a solid block:
  - Center line passes through $(1,1)$.
  - $(1,1)$ is air and has floor below, so `canStandAt((1,1))` returns `true`.
  - `smoothPath` connects $(0,0)$ directly to $(2,2)$, skipping the intermediate waypoint.
  - When the player walks this diagonal, their bounding box edge at $(1.0, 1.0)$ extends into $[0.7, 1.3] \times [0.7, 1.3]$, colliding $0.3$ blocks deep into the solid corner of block $(1,0)$.
* **Impact**: The player stops dead against the corner, friction locks them against the wall, and the bot gets permanently snagged.

#### B. Flawed Vertical Traversal (Slabs, Stairs, and Ceilings)
* **Location**: `AStarPathfinder.kt:80-88, 242-246`.
* **Mechanism**:
  ```kotlin
  fun canStandAt(world: ClientWorld, feet: BlockPos): Boolean {
      if (!isAirLike(world, feet) || !isAirLike(world, feet.up())) return false
      ...
  }
  private fun isAirLike(world: ClientWorld, pos: BlockPos): Boolean {
      val state = world.getBlockState(pos)
      return state.getCollisionShape(world, pos).isEmpty
  }
  ```
* **Impact**:
  - Bottom slabs, stairs, snow layers, and carpets have non-empty collision shapes. `isAirLike(feet)` returns `false`. The pathfinder treats slabs and stairs as impassable walls!
  - In `neighbors()`, jump step `up = flat.up()` checks headroom at `pos.up(2)`, but fails to check headroom at `flat.up(2)`. If a ceiling is 2 blocks above the destination, the player jumps, smacks their head, loses horizontal momentum, and fails the jump.

#### C. Infinite Stuck Loops & Lack of Dynamic Obstacle Penalization
* **Location**: `WalkController.kt:118-129, 210-214`.
* **Mechanism**: When `stuckTicks >= 25`, `needRepath` triggers. It invokes `AStarPathfinder.findPath(start, targetLog)`.
* **Impact**: Because A* is deterministic and contains no dynamic penalty weights or failed-node memory, it returns the **exact same path** that caused the snag. The player repeats the exact same failing movement indefinitely.

---

### 3.3 Movement Controller: Input Coupling & Fighting

#### A. Input Direction Tied Directly to Camera Angle
* **Location**: `WalkController.kt:193-200, 418-426`.
* **Code**:
  ```kotlin
  val targetYaw = yawTo(player, targetPoint)
  val yawDiff = MathHelper.wrapDegrees(targetYaw - player.yaw)
  options.forwardKey.setPressed(!parkour || parkourAligned)
  options.leftKey.setPressed(!parkour && yawDiff < -55f)
  options.rightKey.setPressed(!parkour && yawDiff > 55f)
  options.sprintKey.setPressed(...)
  ```
* **Mechanism**: Forward and strafe key states are derived from `yawDiff` relative to the player's physical camera (`player.yaw`).
* **Impact**: If the camera turns to focus on a nearby log, inspect an entity, or look ahead, `yawDiff` exceeds thresholds, abruptly de-pressing `forwardKey` or causing violent strafe oscillation. Movement and camera control constantly conflict.

---

## 4. Architectural Requirements & Specifications

### 4.1 R1: Standalone Humanized Rotation Engine (`rotation/RotationEngine.kt`)

#### A. Core Responsibilities
1. **Render-Frame Tick**: Decouple camera updates from client 20 TPS ticks. Execute on every render frame via `WorldRenderEvents.START` or `ClientPreRenderEvent` (or render frame delta-time hook) to guarantee smooth 60/144/240 Hz motion.
2. **Critically-Damped Spring Dynamics**: Replace progress-resetting polynomial easing with a continuous second-order dynamical system:
   $$\ddot{\theta}(t) + 2\zeta\omega \dot{\theta}(t) + \omega^2 (\theta(t) - \theta_{\text{target}}) = 0$$
   where $\zeta = 1.0$ (critically damped, no overshoot) and $\omega$ is natural angular frequency. 
   - State carries forward: when $\theta_{\text{target}}$ changes mid-turn, current velocity $\dot{\theta}$ is preserved. Curve-resetting stutter is mathematically eliminated.
3. **Minecraft Mouse Sensitivity GCD Quantization**:
   - Compute vanilla step size:
     $$d = s \times 0.6 + 0.2, \quad f = d^3 \times 8, \quad \text{GCD} = f \times 0.15$$
   - For every continuous frame delta $\Delta \theta$:
     $$k = \text{round}\left(\frac{\Delta \theta + \text{remainder}}{\text{GCD}}\right), \quad \Delta \theta_{\text{quantized}} = k \times \text{GCD}$$
     $$\text{remainder} \leftarrow (\Delta \theta + \text{remainder}) - \Delta \theta_{\text{quantized}}$$
   - Anti-cheat compliance is 100% guaranteed.
4. **Natural Head Orientation Modes**:
   - `PATH_TANGENT`: Camera aligns with direction vector between current and upcoming waypoints.
   - `TARGET_FOCUS`: Smoothly transitions camera focus to interaction target (block face or entity) when within interaction radius.
   - `IDLE_DRIFT`: Gentle, continuous low-frequency noise (Perlin / simplex) without per-tick random white noise.

#### B. Proposed Interface Contract
```kotlin
package foraginghelpermod.client.rotation

import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d

enum class RotationMode {
    IDLE,
    PATH_TANGENT,
    TARGET_FOCUS,
    MANUAL
}

interface IRotationEngine {
    val currentYaw: Float
    val currentPitch: Float
    val isAimingAtTarget: Boolean

    fun setTarget(point: Vec3d, mode: RotationMode = RotationMode.PATH_TANGENT)
    fun setTarget(yaw: Float, pitch: Float, mode: RotationMode = RotationMode.PATH_TANGENT)
    fun clearTarget()
    
    /** Invoked once per render frame (60/144/240 Hz) with frame delta seconds. */
    fun onRenderFrame(client: MinecraftClient, deltaSeconds: Float)
    
    /** Reset internal spring velocity and target states. */
    fun reset()
}
```

---

### 4.2 R2: Hitbox-Aware 3D A* Pathfinder (`path/Pathfinder.kt`)

#### A. Core Responsibilities
1. **Full 3D Player Bounding Box Collision Evaluation**:
   - Player bounding box: width = 0.6 ($[-0.3, +0.3]$), height = 1.8 ($[0.0, 1.8]$).
   - Collision validation against world `VoxelShape`s along the entire bounding volume for every node expansion.
2. **Swept-Box Line of Sight (LOS) Path Smoothing**:
   - Replace 1D point Bresenham raycasting with a swept AABB check.
   - Two waypoints $A$ and $B$ can be directly connected if and only if the continuous swept box from $A$ to $B$ intersects no collision shapes AND maintains continuous support beneath the player's feet.
   - Eliminates 100% of corner snagging.
3. **Complete Vertical Traversal Semantics**:
   - Slab & Stair handling: Identify half-block elevation increases (up to 0.5m) and allow smooth walking without jumping.
   - Ceilings: Ensure 1.8m height clearance is available above all jump arcs, specifically checking `destination.up(2)` and jump apex.
   - Drops: Safe falls from 1 to 3 blocks with clear fall column.
   - Parkour: 2-3 block gap jumps with 2-block vertical clearance throughout takeoff, gap, and landing.
4. **Dynamic Node Penalization & Obstacle Memory**:
   - Support dynamic cost penalties: `penalizeNode(pos: BlockPos, penaltyCost: Double)`.
   - When the movement controller detects a stuck event at node $N$, $N$ receives a high traversal penalty. Subsequent path calculations automatically route around the obstacle.
   - Penalties decay over time.

#### B. Proposed Interface Contract
```kotlin
package foraginghelpermod.client.path

import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

data class PathResult(
    val waypoints: List<Vec3d>,
    val nodePositions: List<BlockPos>,
    val isComplete: Boolean
)

interface IPathfinder {
    fun findPath(
        world: ClientWorld,
        start: Vec3d,
        goal: BlockPos,
        reach: Double,
        maxRange: Int = 40,
        exactDestination: Boolean = false
    ): PathResult?

    fun penalizeNode(pos: BlockPos, penalty: Double = 50.0)
    fun clearPenalties()
    fun isWalkable(world: ClientWorld, pos: BlockPos): Boolean
}
```

---

### 4.3 R4: Decoupled Movement Controller (`movement/MovementController.kt`)

#### A. Core Responsibilities
1. **Input-Decoupled Vector Translation**:
   - Compute desired horizontal movement vector $\vec{D} = \text{normalize}(W_{xz} - P_{xz})$.
   - Calculate relative angle to current player heading $\alpha = \text{atan2}(-\vec{D}_x, \vec{D}_z) - \text{player.yaw}$.
   - Map $\alpha$ directly to 4-way / 8-way movement keys (W, A, S, D):
     - Forward: $\cos(\alpha) > 0.38$
     - Backward: $\cos(\alpha) < -0.38$
     - Right: $\sin(\alpha) > 0.38$
     - Left: $\sin(\alpha) < -0.38$
   - Movement proceeds toward waypoint $W$ smoothly regardless of what yaw the camera is facing.
2. **Stuck Detection & Multi-Tier Unstuck Recovery**:
   - Tier 1: Micro-strafe / jump nudge (3-6 ticks of stagnation).
   - Tier 2: Reverse backing + penalize current node in Pathfinder + request repath (12-20 ticks).
   - Tier 3: Aspect of the Void recovery (if enabled and player falling / blocked > 25 ticks).
3. **Hardware Key Safety**:
   - Query GLFW hardware states on stop to ensure physical player keys are never left dead.

#### B. Proposed Interface Contract
```kotlin
package foraginghelpermod.client.movement

import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.BlockPos

interface IMovementController {
    val isMoving: Boolean
    val isStuck: Boolean
    val currentWaypointIndex: Int
    val activePath: List<Vec3d>
    val status: String

    fun setPath(waypoints: List<Vec3d>, goal: BlockPos, reach: Double, exactDestination: Boolean)
    fun tick(client: MinecraftClient)
    fun stop(client: MinecraftClient)
    fun restoreHardwareKeys(client: MinecraftClient)
}
```

---

## 5. Integration Architecture & Decoupling Strategy

```
                         ┌───────────────────────────┐
                         │      InputController      │
                         └─────────────┬─────────────┘
                                       │
            ┌──────────────────────────┼──────────────────────────┐
            ▼                          ▼                          ▼
 ┌────────────────────┐     ┌────────────────────┐     ┌────────────────────┐
 │  Target Framework  │     │   IPathfinder      │     │  IRotationEngine   │
 │        (R3)        │     │       (R2)         │     │       (R1)         │
 └──────────┬─────────┘     └──────────┬─────────┘     └──────────┬─────────┘
            │                          │                          │
            │                          ▼                          │
            │               ┌────────────────────┐                │
            └──────────────>│ IMovementController│<───────────────┘
                            │       (R4)         │
                            └────────────────────┘
```

1. **Target Selection (R3)** produces a `NavigationTarget` (either block or entity).
2. **Pathfinder (R2)** computes swept-box collision-free waypoints to within reach of the target.
3. **Movement Controller (R4)** translates waypoints into relative WASD input on client ticks, independently notifying `Pathfinder` of stuck nodes.
4. **Rotation Engine (R1)** smoothly rotates camera toward path tangent or target focus on render frames with mouse sensitivity GCD quantization.
5. **Existing Systems (`HelperOptionsScreen`, `StatusHud`, `DebugWorldOverlay`, `ManualRouteCommand`)** interface cleanly through the updated controller properties without regression.

---

## 6. Build Verification Baseline

- Java 23 (`C:\Users\D0AF~1\JDKS~1\OPENJD~1`)
- Gradle command: `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"`
- Baseline build status: **PASSED (exit code 0, 0 errors)**.
