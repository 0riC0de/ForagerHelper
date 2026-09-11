# Project: ForagerHelper Core Navigation Rewrite

## Architecture
A clean-slate re-architecture replacing the monolithic, tick-coupled `WalkController` and Bresenham pathfinding with 4 decoupled, high-performance modules:

```
                  ┌──────────────────────┐
                  │    TargetManager     │
                  │ (NavigationTarget:   │
                  │  Block/Entity/Pos)   │
                  └──────────┬───────────┘
                             │ (Goal / Target Spot)
                             ▼
┌──────────────────┐      ┌─────────────────────────┐
│  RotationEngine  │◄────►│       Pathfinder        │
│(Critically-damped│      │(Hitbox-Aware 3D A*      │
│spring, GCD-quant,│      │ Swept-Box LOS, Penalized│
│render-frame hook)│      │ Node Memory, Unstuck)   │
└────────┬─────────┘      └────────────┬────────────┘
         │                             │ (Waypoints & Look Ahead)
         │                             ▼
         │                ┌─────────────────────────┐
         └───────────────►│   MovementController    │
                          │(Decoupled WASD Vector,  │
                          │ Jump/Strafe Translation,│
                          │ Multi-Tier Recovery)    │
                          └─────────────────────────┘
```

- **RotationEngine (`rotation/`)**: Camera orientation decoupled from 20 TPS client tick rate, executing on render frame events (`WorldRenderEvents.START_MAIN` / frame delta time). Spring-based continuous damping without curve resets. Anti-cheat mouse sensitivity GCD quantization (`(f * 0.6 + 0.2)^3 * 8 * 0.15`) with sub-step remainder accumulator.
- **Pathfinder (`path/`)**: 3D A* pathfinder respecting player $0.6 \times 1.8$m bounding box. Swept-box raycasts replacing 1D Bresenham smoothing to eliminate diagonal corner snagging. Vertical clearance checks for slabs, stairs, ceilings, and parkour gaps. Dynamic node penalization memory.
- **Target Framework (`target/`)**: Unified `NavigationTarget` sealed hierarchy (`BlockTarget`, `EntityTarget`, `PositionTarget`) with pluggable `TargetScanner<T>` implementations (`TreeClusterTargetScanner`, `MobTargetScanner`, `CustomBlockTargetScanner`) managed by `TargetManager`.
- **MovementController (`movement/`)**: Pure input translation projecting player-to-waypoint vectors into local WASD keypresses without overriding camera yaw. Coordinates with `RotationEngine` and executes multi-tier unstuck maneuvers.
- **Integration & Compatibility**: Adapts UI (`HelperOptionsScreen`), Config (`HelperConfig`), HUD (`StatusHud`), debug renderers (`DebugWorldOverlay` using 1.21.11 `GizmoDrawing`), and commands (`/forageroute`).

---

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Render-Frame Camera Panning | High-refresh-rate (60/144/240Hz) camera orientation via render frame hooks (`WorldRenderEvents.START_MAIN`) | M1 | R1, Explorer 1 & 3 |
| 2 | Critically-Damped Spring Smoothing | Continuous angle convergence eliminating curve resets and velocity jumps on target shift | M1 | R1, Explorer 1 |
| 3 | Mouse Sensitivity GCD Quantization | Strict compliance with vanilla `(f*0.6+0.2)^3 * 1.2` step via sub-step remainder accumulator | M1 | R1, Explorer 1 & 3 |
| 4 | Natural Head Movement & Focus | Looking along path tangent when navigating, smoothly focusing on interaction target at reach | M1 | R1, Explorer 1 |
| 5 | Hitbox-Aware 3D A* Node Expansion | Expanding nodes with 0.6x1.8m box collision validation against world geometry | M2 | R2, Explorer 1 & 3 |
| 6 | Swept-Box Line-of-Sight Smoothing | Replacing 1D Bresenham raycast with swept AABB to eliminate diagonal corner snagging | M2 | R2, Explorer 1 & 3 |
| 7 | Vertical Traversal Semantics | Handling slabs, stairs, 1-block drops, jump apex ceiling headroom, and parkour jumps | M2 | R2, Explorer 1 |
| 8 | Dynamic Node Penalization | Temporary cost penalty on nodes where movement stagnates to break infinite loops | M2 | R2, Explorer 1 |
| 9 | Multi-Tier Unstuck Maneuvers | Reactive reverse, strafe, jump recovery, and route recalculation | M2 | R2, Explorer 1 |
| 10 | Universal `NavigationTarget` Hierarchy | Sealed interface providing polymorphic goal, focus, reach, and debug rendering | M3 | R3, Explorer 2 |
| 11 | Concrete Target Types | `BlockTarget` (foraging/mining), `EntityTarget` (mobs/wildlife), `PositionTarget` (waypoints) | M3 | R3, Explorer 2 |
| 12 | Pluggable `TargetScanner<T>` | `TreeClusterTargetScanner`, `MobTargetScanner`, and `CustomBlockTargetScanner` | M3 | R3, Explorer 2 |
| 13 | Target Lifecycle & Focus Dispatch | `TargetManager` handling target locking, reach validation, and focus acquisition | M3 | R3, Explorer 2 |
| 14 | Decoupled WASD Vector Translation | Projecting motion vector onto player yaw to press forward/strafe without camera fight | M4 | R4, Explorer 1 |
| 15 | Movement Controller Lifecycle | State machine coordinating path execution, arrival, target interaction, and recovery | M4 | R4, Explorer 1 |
| 16 | StatusHud & Gizmo Integration | Polymorphic HUD and `DebugWorldOverlay` (1.21.11 `GizmoDrawing`) target/path rendering | M4 | R4, Explorer 2 |
| 17 | Config, Keybinds & Command Hookup | `/forageroute`, `HelperConfig`, `HelperOptionsScreen`, and `ModKeyBindings` compatibility | M4 | R4, Explorer 2 |
| 18 | Offline Test Harness & Suite | Comprehensive offline unit & integration tests covering Tiers 1-4 | Test Track | Dual Track, Explorer 3 |
| 19 | 100% E2E Pass & Adversarial Hardening | Verification of all acceptance criteria and adversarial coverage hardening (Tier 5) | Final M5 | Dual Track & Audit |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| Test Track | E2E Testing Track | Test harness, test runners, Tiers 1-4 test cases | None | IN_PROGRESS |
| M1 | Rotation Engine (R1) | `rotation/RotationEngine.kt`, spring dynamics, GCD quantization, render hooks | None | DONE |
| M2 | Hitbox-Aware Pathfinder (R2) | `path/Pathfinder.kt`, 3D A*, swept-box LOS, vertical traversal, node penalties | None | PLANNED |
| M3 | Universal Target Framework (R3) | `target/` package, `NavigationTarget`, `TargetScanner`, `TargetManager` | None | PLANNED |
| M4 | Movement Controller & Integration (R4) | `movement/MovementController.kt`, WASD translation, UI/HUD/Gizmo/Command wiring | M1, M2, M3 | PLANNED |
| M5 | Final Verification & Hardening | 100% E2E test pass (Tiers 1-4), Tier 5 adversarial stress testing, clean forensic audit | M4, Test Track | PLANNED |

---

## Interface Contracts

### 1. Rotation Engine Contract
```kotlin
package com.github.foragerhelper.rotation

import net.minecraft.util.math.Vec3d

interface RotationEngine {
    fun setTarget(focusPoint: Vec3d?, snap: Boolean = false)
    fun setTargetAngles(yaw: Float, pitch: Float, snap: Boolean = false)
    fun setPathTangent(tangent: Vec3d?)
    fun onRenderFrame(deltaTimeSeconds: Float, tickProgress: Float)
    fun reset()
    val currentYaw: Float
    val currentPitch: Float
}
```

### 2. Pathfinder Contract
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

### 3. Target Framework Contract
```kotlin
package com.github.foragerhelper.target

import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

sealed interface NavigationTarget {
    fun getTargetPos(world: World): Vec3d?
    fun getFocusPoint(world: World, playerEyePos: Vec3d): Vec3d?
    fun isInReach(player: ClientPlayerEntity, reachDistance: Double): Boolean
    fun isCompleted(world: World, player: ClientPlayerEntity): Boolean
    fun isValid(world: World): Boolean
    fun describeStatus(player: ClientPlayerEntity): String
}
```

### 4. Movement Controller Contract
```kotlin
package com.github.foragerhelper.movement

import com.github.foragerhelper.target.NavigationTarget
import net.minecraft.client.MinecraftClient

interface MovementController {
    fun setDestination(target: NavigationTarget)
    fun tick(client: MinecraftClient)
    fun stop()
    val isNavigating: Boolean
    val currentStatus: String
}
```

---

## Code Layout
- `src/main/kotlin/com/github/foragerhelper/rotation/`
  - `RotationEngine.kt`: Core engine implementation.
  - `SpringSmoother.kt`: Critically damped angular spring with wrap-around.
  - `SensitivityGCD.kt`: Minecraft sensitivity GCD quantization with remainder accumulation.
- `src/main/kotlin/com/github/foragerhelper/path/`
  - `Pathfinder.kt`: Hitbox-aware 3D A* pathfinder.
  - `SweptBoxLOS.kt`: Swept bounding-box raycaster.
  - `NodePenaltyMap.kt`: Dynamic spatial penalty memory.
- `src/main/kotlin/com/github/foragerhelper/target/`
  - `NavigationTarget.kt`: Sealed target hierarchy (`BlockTarget`, `EntityTarget`, `PositionTarget`).
  - `TargetScanner.kt`: Interface and scanners (`TreeClusterTargetScanner`, `MobTargetScanner`, `CustomBlockTargetScanner`).
  - `TargetManager.kt`: Active target lifecycle.
- `src/main/kotlin/com/github/foragerhelper/movement/`
  - `MovementController.kt`: Decoupled input translation & state machine.
  - `UnstuckHandler.kt`: Stagnation detection and multi-tier recovery maneuvers.
- `src/main/kotlin/com/github/foragerhelper/hud/` & `render/`:
  - Preserved `StatusHud` and `DebugWorldOverlay` adapted to polymorphic target/path queries.
- `src/test/kotlin/com/github/foragerhelper/`
  - Test suites for Tiers 1-5.
