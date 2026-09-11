# Architecture & Survey Report: Universal Target Framework (R3) & UI/HUD/Config Integration

**Author**: Survey Explorer 2 (`teamwork_preview_explorer_survey_2`)  
**Date**: 2026-09-11  
**Project**: ForagerHelper Fabric 1.21.11 Mod  
**Target Milestone**: R3 Universal Target Framework, UI/HUD/Config/Overlay Integration

---

## 1. Executive Summary

This report delivers a complete architectural survey of target scanning, selection, configuration, UI screens, status HUD, debug gizmo overlays, keybinds, and commands in the ForagerHelper codebase.

Currently, the mod is rigidly coupled to tree foraging:
- `TreeScanner`, `TreeCluster`, and `TreeScorer` exclusively find and score Minecraft logs (`BlockTags.LOGS`).
- `InputController` directly imports tree classes, manages a hardcoded `committedPositions: Set<BlockPos>` for tree clusters, and manually switches between tree foraging and manual route goals.
- `StatusHud` and `DebugWorldOverlay` directly query `targetLog` and tree metrics (`treeCount`, `selectedTreeSize`), making it impossible to target mobs, ores, or generic waypoints without display glitches or crashes.
- `WalkController` conflates pathfollowing, camera rotation (`lookAtNaturally`), mining alignment nudges (`nudgeForMining`), and Ether Warp teleports into a single 460-line monolithic singleton.

We define the complete architectural blueprint for **R3: Universal Target Framework** (`target/`):
1. An extensible `NavigationTarget` interface decoupling target semantics from foraging.
2. Concrete implementations: `BlockTarget`, `EntityTarget`, and `PositionTarget`.
3. Pluggable `TargetScanner<T : NavigationTarget>` implementations for trees, mobs/wildlife, and custom blocks.
4. A centralized `TargetManager` handling target acquisition, commitment/locking, invalidation, and focus point distribution.
5. Non-breaking, polymorphic integration into `HelperConfig`, `HelperOptionsScreen`, `StatusHud`, `DebugWorldOverlay`, and `ManualRouteCommand`.

---

## 2. Comprehensive Survey of Existing Implementations

### 2.1 Target Scanning & Selection Pipeline

#### Files:
- `src/main/kotlin/foraginghelpermod/client/scan/TreeScanner.kt` (193 lines)
- `src/main/kotlin/foraginghelpermod/client/scan/TreeCluster.kt` (19 lines)
- `src/main/kotlin/foraginghelpermod/client/scan/TreeScorer.kt` (23 lines)
- `src/main/kotlin/foraginghelpermod/client/InputController.kt` (lines 89–151)

#### Existing Mechanics:
1. **Cluster Discovery (`TreeScanner.scanClusters`)**:
   - Loops over a bounding box of `radius` (default 12) centered at player feet: `minX..maxX`, `minY..maxY`, `minZ..maxZ`.
   - Checks `isLogLike(state)`: checks `state.isAir`, `state.isIn(BlockTags.LOGS)`, and fallback string checks on block registry paths (`"log"`, `"stem"`, `"hyphae"`).
   - Flood fills up to `MAX_CLUSTER_SIZE = 96` logs in 6 directions (`Direction.entries`), bounded by Manhattan distance `maxManhattanFromOrigin = radius + 2`.
   - Computes `nearestLog`, `baseLog` (lowest Y, then closest XZ), and `distanceSq` to `player.eyePos`.
2. **Cluster Ranking (`TreeScorer.pickBest`)**:
   - Computes:
     $$\text{Score} = (\pm \text{cluster.size}) + 0.35 \times \sqrt{\text{distanceSq}}$$
     where size is positive if `preferSmallTrees == true` and negative if `false`.
   - Returns the cluster with the minimum score.
3. **Log Selection (`TreeScanner.selectTargetLog`)**:
   - Filters cluster logs using a usability predicate:
     - `AStarPathfinder.hasUsableMiningSpot(world, log, reach)`
     - `!AStarPathfinder.hasNonLeafMiningBlocker(world, player.blockPos, log)`
   - Prioritizes candidate logs within player reach (`squaredDistance <= reach * reach`).
   - Breaks ties by distance, then lowest Y level.
4. **Target Commitment & Locking (`InputController`)**:
   - `committedPositions: Set<BlockPos>` holds all log positions of the currently selected tree cluster.
   - When `committedPositions` is not empty, subsequent scans will only stay locked to a cluster containing those logs (`resolveCommittedOrPick`).
   - When a log is chopped, `ChopController` waits 2 ticks for block removal confirmation (`MISSING_CONFIRM_TICKS = 2`), then calls `InputController.forgetTarget(log)`.
   - `forgetTarget` removes the position from `committedPositions` and clears `targetLog`. Once the set is empty, a new cluster can be chosen.

---

### 2.2 Configuration System (`HelperConfig.kt`)

#### File:
- `src/main/kotlin/foraginghelpermod/client/HelperConfig.kt` (25 lines)

#### Existing State & Properties:
```kotlin
object HelperConfig {
    var enabled: Boolean = false
    var autoBreak: Boolean = false
    var lookAtTarget: Boolean = false
    var showStatusHud: Boolean = false
    var sneakWhileActive: Boolean = false
    var preferSmallTrees: Boolean = false
    var autoWalk: Boolean = false
    var useAspectOfVoid: Boolean = true
    var showPathOverlay: Boolean = true
    var manualRouteGoal: BlockPos? = null
}
```
#### Observations:
- In-memory singleton object. No disk serialization (JSON/TOML). All state resets on game restart.
- Flags are queried globally across UI, tick handlers, pathfinders, and renderers.
- `manualRouteGoal` is a nullable `BlockPos` treated as a special override goal in `InputController.onEndTick`.

---

### 2.3 User Interface (`HelperOptionsScreen.kt` & `UiTheme.kt`)

#### Files:
- `src/main/kotlin/foraginghelpermod/client/ui/HelperOptionsScreen.kt` (259 lines)
- `src/main/kotlin/foraginghelpermod/client/ui/UiTheme.kt` (48 lines)

#### Existing Mechanics:
- `HelperOptionsScreen` extends Minecraft client `Screen(Text.translatable("screen.oriforaginhelpermod.options"))`.
- Smooth open/close scale + fade animation using cubic and back easings (`OPEN_MS = 260f`, `CLOSE_MS = 180f`).
- Panel width 280px, centered on screen, with dark semi-transparent glass style (`Colors.PANEL = 0xF012161C`, `Colors.ACCENT = 0xFF7CBA5A`).
- Contains a list of 9 `OptionRow(label, description, getter, setter)` checkboxes bound to `HelperConfig`:
  1. "Enable Helper" -> `HelperConfig.enabled`
  2. "Prefer Small Trees" -> `HelperConfig.preferSmallTrees`
  3. "Auto Walk" -> `HelperConfig.autoWalk`
  4. "Void Recovery" -> `HelperConfig.useAspectOfVoid`
  5. "Path Overlay" -> `HelperConfig.showPathOverlay`
  6. "Auto Break" -> `HelperConfig.autoBreak`
  7. "Look at Target" -> `HelperConfig.lookAtTarget`
  8. "Show Status HUD" -> `HelperConfig.showStatusHud`
  9. "Sneak While Active" -> `HelperConfig.sneakWhileActive`
- Interactive clicking with transformed mouse coordinates to handle panel scaling and bounce.
- `keyPressed` handles `GLFW_KEY_ESCAPE` to trigger smooth exit animation (`requestClose`).
- `shouldPause(): Boolean = false` ensures world tick continues while options are toggled.

---

### 2.4 Status HUD (`StatusHud.kt`)

#### File:
- `src/main/kotlin/foraginghelpermod/client/hud/StatusHud.kt` (74 lines)

#### Existing Mechanics:
- Registered on Fabric API `HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, OriForaginHelperMod.id("status"), StatusHud::render)`.
- Renders only when `client.player != null`, `!client.options.hudHidden`, and `HelperConfig.showStatusHud == true`.
- Screen coordinates: top-left corner (`x = 4`, `y` increments by 12 per line).
- Output text lines:
  - Line 1: `Foraging Helper: ON` (ACCENT) / `Foraging Helper: OFF` (DANGER)
  - Line 2: `Trees: $trees | Dist: %.1fm | Size: %d` or `Trees: $trees | No target`
  - Line 3: `Prefer: Small` or `Prefer: Large`
  - Line 4: `Locked` (ACCENT) if `InputController.isLocked`
  - Line 5: `Walk: ${InputController.walkStatus}`
  - Line 6: `Target: ${target.x}, ${target.y}, ${target.z}`
- **Rigid Coupling**: Completely hardcoded to tree counts and log coordinates. If player is following a manual route or hunting mobs, the HUD continues to print `"Trees: 0 | No target"`.

---

### 2.5 World Debug Overlay (`DebugWorldOverlay.kt`)

#### File:
- `src/main/kotlin/foraginghelpermod/client/hud/DebugWorldOverlay.kt` (35 lines)

#### Existing Mechanics:
- Hooks into Fabric rendering event: `WorldRenderEvents.END_EXTRACTION.register { context -> ... }`.
- Condition: `HelperConfig.enabled && HelperConfig.showPathOverlay && client.player != null`.
- Uses Minecraft 1.21.11 client debug gizmos:
  ```kotlin
  context.worldRenderer().startDrawingGizmos().use {
      WalkController.path.forEachIndexed { index, pos ->
          val color = if (index == WalkController.pathIndex) 0xFFFFA000.toInt() else 0xFF199FFF.toInt()
          drawBox(pos, color)
      }
      InputController.targetLog?.let { drawBox(it, 0xFFFF3030.toInt()) }
      HelperConfig.manualRouteGoal?.let { drawBox(it, 0xFFB060FF.toInt()) }
      WalkController.etherWarpTarget?.let { drawBox(it, 0xFF30FF70.toInt()) }
  }
  ```
- Draws stroked 2px boxes ignoring occlusion with lifespan 2 ticks via `GizmoDrawing.box(pos, DrawStyle.stroked(color, 2.0f)).ignoreOcclusion().withLifespan(2)`.
- **Rigid Coupling**: Only renders a single `targetLog` as a `BlockPos` and `manualRouteGoal`. Cannot render entity hitboxes, velocity vectors, or block faces.

---

### 2.6 Keybinds & Input Handling (`ModKeyBindings.kt`, `InputController.kt`)

#### Files:
- `src/main/kotlin/foraginghelpermod/client/ModKeyBindings.kt` (27 lines)
- `src/main/kotlin/foraginghelpermod/client/InputController.kt` (lines 45–72)

#### Existing Mechanics:
- Category: `KeyBinding.Category.create(OriForaginHelperMod.id("foraging_helper"))`.
- Key: `toggleHelper` bound by default to `GLFW.GLFW_KEY_H` ("key.oriforaginhelpermod.toggle").
- In `InputController.onEndTick`:
  ```kotlin
  while (ModKeyBindings.toggleHelper.wasPressed()) {
      openOptions()
  }
  ```
- If GUI is open (`client.currentScreen != null`), `WalkController.stop(client)` is invoked to prevent unwanted automated inputs while interacting with containers or menus.
- **Hardware Key State Restoration**: `WalkController.releaseMovement` checks actual physical GLFW keyboard states (`glfwGetKey` / `glfwGetMouseButton`) when stopping navigation so user keys are not left "dead" if held during automation.

---

### 2.7 Manual Route Command (`ManualRouteCommand.kt`)

#### File:
- `src/main/kotlin/foraginghelpermod/client/ManualRouteCommand.kt` (36 lines)

#### Existing Mechanics:
- Registered via `ClientCommandRegistrationCallback.EVENT`.
- Command root: `/forageroute`
  - `/forageroute <x> <y> <z>`: sets `HelperConfig.manualRouteGoal = BlockPos(x, y, z)` and outputs feedback `Text.literal("Forager route set to $pos")`.
  - `/forageroute clear`: sets `HelperConfig.manualRouteGoal = null`, calls `WalkController.stop()`, and outputs feedback `Text.literal("Forager route cleared")`.
- In `InputController.onEndTick`:
  ```kotlin
  val manualGoal = HelperConfig.manualRouteGoal
  if (manualGoal != null) {
      WalkController.tick(client, manualGoal, REACH, forceRoute = true, exactDestination = true)
      return
  }
  ```
- Completely bypasses tree scanning when `manualRouteGoal` is set, but directly calls `WalkController` with `exactDestination = true`.

---

## 3. Coupling Analysis & Architectural Deficiencies

| Component | Current Coupling / Defect | Consequence |
|---|---|---|
| **InputController** | Imports `TreeCluster`, `TreeScanner`, `TreeScorer`, `AStarPathfinder`. Manages `committedPositions: Set<BlockPos>`. | Cannot target entities or generic blocks without rewriting `InputController`. |
| **Target Representation** | Target is represented as a nullable `BlockPos` (`targetLog`). | Cannot store entity targets (UUID, bounding box, velocity), complex multiblock targets, or face preferences. |
| **Target Commitment** | Hardcoded to log sets in `TreeCluster`. | Mobs cannot be committed/locked; target switches unpredictably on entity motion. |
| **StatusHud** | Hardcoded queries to `InputController.treeCount`, `nearestDistance()`, `selectedTreeSize()`. | Broken/misleading HUD when routing to a coordinate or pursuing a mob. |
| **DebugWorldOverlay** | Queries `targetLog: BlockPos?` and `manualRouteGoal: BlockPos?`. | Cannot visualize entity hitboxes, velocity lead points, or custom interaction points. |
| **WalkController** | Combines pathfollowing, camera turning (`lookAtNaturally`), input pressing, unstuck recovery, mining nudge, and Ether Warp. | Monolithic spaghetti. Tightly couples camera orientation to 20Hz movement ticks. |
| **ManualRouteCommand** | Directly assigns `HelperConfig.manualRouteGoal = BlockPos(...)`. | Does not integrate with a general target queue or target lifecycle. |

---

## 4. Technical Specification for R3: Universal Target Framework

```
foraginghelpermod/client/target/
├── NavigationTarget.kt          # Sealed interface & target contracts
├── BlockTarget.kt               # Blocks, logs, ores, crops with face preference & stand spots
├── EntityTarget.kt              # Living entities, hitboxes, distance maintenance, lead velocity
├── PositionTarget.kt            # Fixed coordinates, waypoints (/forageroute)
├── TargetScanner.kt             # Pluggable scanner abstraction: TargetScanner<T>
├── TreeClusterTargetScanner.kt  # Adapter for TreeScanner/TreeCluster -> BlockTarget
├── MobTargetScanner.kt          # LivingEntity / Hostile / Passive scanner
├── CustomBlockTargetScanner.kt  # BlockState tag/registry scanner
└── TargetManager.kt             # Central target lifecycle, lock commitment, focus distribution
```

### 4.1 Core Abstraction: `NavigationTarget`

```kotlin
package foraginghelpermod.client.target

import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.debug.gizmo.GizmoDrawing

/**
 * Universal navigation and interaction target.
 * Decouples navigation, camera focus, and action execution from specific target types.
 */
sealed interface NavigationTarget {
    /** Unique identity for locking and comparison. */
    val id: String

    /** Primary world position for distance heuristics and pathing. */
    fun getTargetPos(world: ClientWorld): Vec3d

    /** Focus point for RotationEngine camera tracking. */
    fun getFocusPoint(world: ClientWorld, playerEyePos: Vec3d): Vec3d

    /** Arrival / interaction distance criteria. */
    fun isInReach(player: ClientPlayerEntity, reach: Double): Boolean

    /** Whether the target has been satisfied or reached. */
    fun isCompleted(world: ClientWorld, player: ClientPlayerEntity): Boolean

    /** Whether the target is still valid in the world (not broken, not dead, loaded). */
    fun isValid(world: ClientWorld): Boolean

    /** Set of candidate feet positions where player can stand to interact. */
    fun getStandingSpots(world: ClientWorld, reach: Double): Set<BlockPos>

    /** Debug rendering via Minecraft 1.21.11 Gizmos. */
    fun renderDebug(world: ClientWorld, gizmos: GizmoDrawing)

    /** Status descriptor for HUD display. */
    fun describeStatus(player: ClientPlayerEntity): TargetStatusInfo
}

data class TargetStatusInfo(
    val title: String,
    val details: String,
    val distance: Double,
    val isLocked: Boolean,
)
```

---

### 4.2 Concrete Target: `BlockTarget`

Designed for blocks (trees, ores, crops, interactables):

```kotlin
package foraginghelpermod.client.target

import net.minecraft.block.BlockState
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.world.ClientWorld
import net.minecraft.client.render.DrawStyle
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.debug.gizmo.GizmoDrawing
import kotlin.math.sqrt

data class BlockTarget(
    val pos: BlockPos,
    val blockPredicate: (BlockState) -> Boolean,
    val preferredFace: Direction? = null,
    val reachDistance: Double = 4.5,
    val clusterId: String? = null,
    val clusterSize: Int? = null,
) : NavigationTarget {
    override val id: String = "block_${pos.asLong()}"

    override fun getTargetPos(world: ClientWorld): Vec3d =
        Vec3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

    override fun getFocusPoint(world: ClientWorld, playerEyePos: Vec3d): Vec3d {
        val y = when {
            playerEyePos.y < pos.y -> pos.y + 0.05
            playerEyePos.y > pos.y + 1.0 -> pos.y + 0.95
            else -> pos.y + 0.5
        }
        return Vec3d(pos.x + 0.5, y, pos.z + 0.5)
    }

    override fun isInReach(player: ClientPlayerEntity, reach: Double): Boolean {
        val center = getTargetPos(player.clientWorld)
        return player.eyePos.squaredDistanceTo(center) <= reach * reach
    }

    override fun isCompleted(world: ClientWorld, player: ClientPlayerEntity): Boolean {
        val state = world.getBlockState(pos)
        return state.isAir || !blockPredicate(state)
    }

    override fun isValid(world: ClientWorld): Boolean {
        val state = world.getBlockState(pos)
        return !state.isAir && blockPredicate(state)
    }

    override fun getStandingSpots(world: ClientWorld, reach: Double): Set<BlockPos> {
        // Collects surrounding valid standing spots with line of sight to pos
        return emptySet() // Computed by Pathfinder / collision evaluator
    }

    override fun renderDebug(world: ClientWorld, gizmos: GizmoDrawing) {
        gizmos.drawBox(pos, DrawStyle.stroked(0xFFFF3030.toInt(), 2.0f)).ignoreOcclusion().withLifespan(2)
    }

    override fun describeStatus(player: ClientPlayerEntity): TargetStatusInfo {
        val dist = sqrt(player.eyePos.squaredDistanceTo(getTargetPos(player.clientWorld)))
        val clusterText = clusterSize?.let { "Size: $it" } ?: ""
        return TargetStatusInfo(
            title = "Block: ${pos.x}, ${pos.y}, ${pos.z}",
            details = clusterText,
            distance = dist,
            isLocked = clusterId != null
        )
    }
}
```

---

### 4.3 Concrete Target: `EntityTarget`

Designed for mobs, animals, players, items:

```kotlin
package foraginghelpermod.client.target

import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.world.ClientWorld
import net.minecraft.client.render.DrawStyle
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.debug.gizmo.GizmoDrawing
import kotlin.math.sqrt

data class EntityTarget(
    val entityId: Int,
    val desiredDistance: Double = 2.5,
    val minDistance: Double = 1.5,
    val maxDistance: Double = 3.5,
    val predictVelocity: Boolean = true,
) : NavigationTarget {
    override val id: String = "entity_$entityId"

    fun getEntity(world: ClientWorld): Entity? = world.getEntityById(entityId)

    override fun getTargetPos(world: ClientWorld): Vec3d {
        val entity = getEntity(world) ?: return Vec3d.ZERO
        return entity.pos
    }

    override fun getFocusPoint(world: ClientWorld, playerEyePos: Vec3d): Vec3d {
        val entity = getEntity(world) ?: return Vec3d.ZERO
        val baseFocus = if (entity is LivingEntity) entity.eyePos else entity.boundingBox.center
        if (!predictVelocity) return baseFocus

        // Velocity prediction lead calculation:
        val velocity = entity.velocity
        val dist = sqrt(playerEyePos.squaredDistanceTo(baseFocus))
        val leadTime = (dist / 20.0).coerceIn(0.0, 0.5)
        return baseFocus.add(velocity.multiply(leadTime))
    }

    override fun isInReach(player: ClientPlayerEntity, reach: Double): Boolean {
        val entity = getEntity(player.clientWorld) ?: return false
        val box = entity.boundingBox
        val playerPos = player.pos
        val dx = (playerPos.x - box.center.x).coerceAtLeast(0.0)
        val dz = (playerPos.z - box.center.z).coerceAtLeast(0.0)
        val distHoriz = sqrt(dx * dx + dz * dz)
        return distHoriz <= desiredDistance && distHoriz >= minDistance
    }

    override fun isCompleted(world: ClientWorld, player: ClientPlayerEntity): Boolean {
        val entity = getEntity(world) ?: return true
        return !entity.isAlive
    }

    override fun isValid(world: ClientWorld): Boolean {
        val entity = getEntity(world) ?: return false
        return entity.isAlive && !entity.isRemoved
    }

    override fun getStandingSpots(world: ClientWorld, reach: Double): Set<BlockPos> {
        val entity = getEntity(world) ?: return emptySet()
        val center = entity.blockPos
        // Generates perimeter points around entity
        return setOf(center)
    }

    override fun renderDebug(world: ClientWorld, gizmos: GizmoDrawing) {
        val entity = getEntity(world) ?: return
        gizmos.drawBox(entity.boundingBox, DrawStyle.stroked(0xFFFF4500.toInt(), 2.0f)).ignoreOcclusion().withLifespan(2)
    }

    override fun describeStatus(player: ClientPlayerEntity): TargetStatusInfo {
        val entity = getEntity(player.clientWorld)
        val name = entity?.displayName?.string ?: "Entity #$entityId"
        val dist = entity?.let { sqrt(player.squaredDistanceTo(it)) } ?: 0.0
        val hp = if (entity is LivingEntity) " | HP: %.1f".format(entity.health) else ""
        return TargetStatusInfo(
            title = "Mob: $name",
            details = "Range: %.1fm%s".format(desiredDistance, hp),
            distance = dist,
            isLocked = true
        )
    }
}
```

---

### 4.4 Concrete Target: `PositionTarget`

Designed for exact waypoints and `/forageroute <x> <y> <z>`:

```kotlin
package foraginghelpermod.client.target

import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.world.ClientWorld
import net.minecraft.client.render.DrawStyle
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.debug.gizmo.GizmoDrawing
import kotlin.math.abs
import kotlin.math.sqrt

data class PositionTarget(
    val targetPos: Vec3d,
    val reachRadius: Double = 0.75,
    val exactBlock: BlockPos? = null,
) : NavigationTarget {
    override val id: String = "pos_${targetPos.x.toInt()}_${targetPos.y.toInt()}_${targetPos.z.toInt()}"

    constructor(pos: BlockPos) : this(
        Vec3d(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5),
        reachRadius = 0.75,
        exactBlock = pos.toImmutable()
    )

    override fun getTargetPos(world: ClientWorld): Vec3d = targetPos

    override fun getFocusPoint(world: ClientWorld, playerEyePos: Vec3d): Vec3d =
        Vec3d(targetPos.x, targetPos.y + 1.5, targetPos.z)

    override fun isInReach(player: ClientPlayerEntity, reach: Double): Boolean {
        val dx = player.x - targetPos.x
        val dz = player.z - targetPos.z
        val dy = abs(player.y - targetPos.y)
        return (dx * dx + dz * dz) <= reachRadius * reachRadius && dy < 1.5
    }

    override fun isCompleted(world: ClientWorld, player: ClientPlayerEntity): Boolean =
        isInReach(player, reachRadius)

    override fun isValid(world: ClientWorld): Boolean = true

    override fun getStandingSpots(world: ClientWorld, reach: Double): Set<BlockPos> =
        exactBlock?.let { setOf(it) } ?: setOf(BlockPos.ofFloored(targetPos))

    override fun renderDebug(world: ClientWorld, gizmos: GizmoDrawing) {
        val box = Box(
            targetPos.x - 0.4, targetPos.y, targetPos.z - 0.4,
            targetPos.x + 0.4, targetPos.y + 1.8, targetPos.z + 0.4
        )
        gizmos.drawBox(box, DrawStyle.stroked(0xFFB060FF.toInt(), 2.5f)).ignoreOcclusion().withLifespan(2)
    }

    override fun describeStatus(player: ClientPlayerEntity): TargetStatusInfo {
        val dx = player.x - targetPos.x
        val dy = player.y - targetPos.y
        val dz = player.z - targetPos.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        return TargetStatusInfo(
            title = "Route: ${targetPos.x.toInt()}, ${targetPos.y.toInt()}, ${targetPos.z.toInt()}",
            details = "Manual Route Goal",
            distance = dist,
            isLocked = true
        )
    }
}
```

---

### 4.5 Pluggable Scanners: `TargetScanner<T>`

```kotlin
package foraginghelpermod.client.target

import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.world.ClientWorld

fun interface TargetScanner<T : NavigationTarget> {
    fun scan(world: ClientWorld, player: ClientPlayerEntity, range: Double): List<T>
}
```

#### Implementations:
1. **`TreeClusterTargetScanner`**:
   - Calls `TreeScanner.scanClusters(world, origin, eye, radius)`.
   - Filters candidate clusters using `TreeScorer.pickBest`.
   - For each cluster, maps logs to `BlockTarget` instances with `TreeScanner::isLogLike`.
2. **`MobTargetScanner`**:
   - Queries `world.getOtherEntities(player, Box(...)) { it is LivingEntity && it.isAlive }`.
   - Filters passive vs hostile based on config.
   - Sorts by distance and health; returns `List<EntityTarget>`.
3. **`CustomBlockTargetScanner`**:
   - Scans neighborhood for specific block IDs (e.g. diamonds, ancient debris, wheat).
   - Wraps found blocks as `BlockTarget`.

---

### 4.6 Target Lifecycle Manager: `TargetManager`

```kotlin
package foraginghelpermod.client.target

import foraginghelpermod.client.HelperConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.world.ClientWorld

object TargetManager {
    var activeTarget: NavigationTarget? = null
        private set

    var currentScanner: TargetScanner<*>? = null
    var isLocked: Boolean = false
        private set

    fun setManualTarget(target: NavigationTarget) {
        activeTarget = target
        isLocked = true
    }

    fun clearTarget() {
        activeTarget = null
        isLocked = false
    }

    fun tick(client: MinecraftClient) {
        val world = client.world ?: return clearTarget()
        val player = client.player ?: return clearTarget()

        // 1. Validate active target
        val current = activeTarget
        if (current != null) {
            if (!current.isValid(world) || current.isCompleted(world, player)) {
                clearTarget()
            } else {
                return // Stay committed to active target
            }
        }

        // 2. If no active target and enabled, scan
        if (!HelperConfig.enabled) return

        val scanner = currentScanner ?: return
        val candidates = scanner.scan(world, player, 16.0)
        if (candidates.isNotEmpty()) {
            activeTarget = candidates.first()
            isLocked = true
        }
    }
}
```

---

## 5. Integration Architecture: UI, HUD, Config & Commands

```
┌───────────────────────────┐         ┌───────────────────────────┐
│     ManualRouteCommand    │         │     HelperOptionsScreen   │
│   (/forageroute x y z)    │         │   (9 Toggles + Glass UI)  │
└─────────────┬─────────────┘         └─────────────┬─────────────┘
              │ sets PositionTarget                 │ modifies
              ▼                                     ▼
┌─────────────────────────────────────────────────────────────────┐
│                          HelperConfig                           │
│  (enabled, autoWalk, autoBreak, preferSmallTrees, statusHud...) │
└─────────────────────────────┬───────────────────────────────────┘
                              │ reads state
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         TargetManager                           │
│  ┌───────────────────────┐   ┌───────────────────────────────┐  │
│  │   NavigationTarget    │   │      TargetScanner<T>         │  │
│  │  (Block/Entity/Pos)   │   │  (Trees / Mobs / CustomBlock) │  │
│  └───────────────────────┘   └───────────────────────────────┘  │
└──────────────┬──────────────────────────────┬───────────────────┘
               │ provides activeTarget        │
               ├──────────────────────────────┼───────────────────┐
               ▼                              ▼                   ▼
┌───────────────────────────┐   ┌───────────────────────┐   ┌─────────────────────────┐
│         StatusHud         │   │   DebugWorldOverlay   │   │     RotationEngine      │
│   (Polymorphic Status)    │   │  (Gizmo 3D Box/Lines) │   │ (Render-Frame Springs)  │
└───────────────────────────┘   └───────────────────────┘   └─────────────────────────┘
                                                                  │ focus point
                                                                  ▼
                                                            ┌─────────────────────────┐
                                                            │   MovementController    │
                                                            │ (Decoupled Input Trans) │
                                                            └─────────────────────────┘
```

### 5.1 HelperConfig Integration
- **Preserved Properties**:
  All existing 10 properties in `HelperConfig` (`enabled`, `autoBreak`, `lookAtTarget`, `showStatusHud`, `sneakWhileActive`, `preferSmallTrees`, `autoWalk`, `useAspectOfVoid`, `showPathOverlay`, `manualRouteGoal`) are preserved identically.
- **Extensions**:
  - `var targetMode: TargetMode = TargetMode.FORAGING` (Enum: `FORAGING`, `MOBS`, `CUSTOM_BLOCK`, `MANUAL_ROUTE`).
  - Setting `manualRouteGoal` automatically registers a `PositionTarget` in `TargetManager`.

### 5.2 HelperOptionsScreen Integration
- Existing visual presentation (floating 280px panel, cubic/back easing animation, translucent glass backdrop, accent rail, checkbox hover/click) remains 100% untouched and functional.
- The 9 existing rows continue to bind directly to `HelperConfig`.
- When `HelperConfig.enabled` is toggled off, `TargetManager.clearTarget()` and `MovementController.stop()` are triggered cleanly.

### 5.3 StatusHud Polymorphic Integration
Instead of hardcoding tree counts and log coordinates, `StatusHud` renders dynamically based on `TargetManager.activeTarget`:
1. **Header**: `Foraging Helper: ON` (ACCENT) or `Foraging Helper: OFF` (DANGER).
2. If `activeTarget == null`:
   - `Status: Searching for targets...`
   - `Mode: ${HelperConfig.targetMode}`
   - `Walk: ${MovementController.status}`
3. If `activeTarget != null`:
   - Queries `activeTarget.describeStatus(player)`:
     - `Line 2`: `activeStatus.title` (e.g. `Block: 120, 64, -300` or `Mob: Zombie` or `Route: 50, 70, 100`).
     - `Line 3`: `Dist: %.1fm  |  %s`.format(activeStatus.distance, activeStatus.details).
     - `Line 4`: `Locked` badge (ACCENT) if `activeStatus.isLocked`.
     - `Line 5`: `Walk: ${MovementController.status}`.

### 5.4 DebugWorldOverlay Integration
`DebugWorldOverlay.register()` hooks into `WorldRenderEvents.END_EXTRACTION`:
- Waypoints: renders `MovementController.currentPath` nodes (current index orange `0xFFFFA000`, upcoming cyan `0xFF199FFF`).
- Active Target: invokes `TargetManager.activeTarget?.renderDebug(world, gizmos)`:
  - `BlockTarget`: renders red stroked bounding box around target block.
  - `EntityTarget`: renders orange stroked bounding box around entity hitbox, plus velocity lead vector line.
  - `PositionTarget`: renders purple destination pillar.
- Ether Warp: renders green landing box `0xFF30FF70` if active.

### 5.5 ManualRouteCommand Integration
- `/forageroute <x> <y> <z>`:
  - Validates coordinates.
  - Constructs `PositionTarget(BlockPos(x, y, z))`.
  - Passes it to `TargetManager.setManualTarget(...)`.
  - Sets `HelperConfig.manualRouteGoal = BlockPos(x, y, z)` (for backward compatibility).
  - Triggers path recalculation immediately in `MovementController`.
- `/forageroute clear`:
  - Calls `TargetManager.clearTarget()`.
  - Sets `HelperConfig.manualRouteGoal = null`.
  - Calls `MovementController.stop()`.

### 5.6 Keybinds Integration
- `ModKeyBindings.toggleHelper` remains `GLFW.GLFW_KEY_H`.
- Toggling helper opens `HelperOptionsScreen`.
- `releaseMovement` hardware key check remains essential: when navigation stops, `MovementController` queries GLFW physical states so the player never experiences "dead" stuck keys.

---

## 6. Migration & Refactoring Roadmap

| Phase | Target Module | Description | Breaking Risk |
|---|---|---|---|
| **Phase 1** | `target/` package | Create `NavigationTarget`, `BlockTarget`, `EntityTarget`, `PositionTarget`, `TargetScanner`, `TargetManager`. | Zero (additive). |
| **Phase 2** | `target/TreeClusterTargetScanner` | Adapt existing `TreeScanner` and `TreeScorer` into `TargetScanner<BlockTarget>`. | Zero (encapsulated). |
| **Phase 3** | `StatusHud` & `DebugWorldOverlay` | Update to query `TargetManager.activeTarget` polymorphically with fallback wrappers. | Low (UI rendering only). |
| **Phase 4** | `ManualRouteCommand` & `InputController` | Replace raw `manualRouteGoal` branches in `InputController` with `TargetManager` delegation. | Low (simplifies control flow). |
| **Phase 5** | Inter-module Bridge | Connect `TargetManager.activeTarget` focus points to `RotationEngine` (R1) and standing spots to `Pathfinder` (R2) / `MovementController` (R4). | Fully decoupled. |

---

## 7. Verification & Build Validation

1. **Gradle Build Cleanliness**:
   - Tested command:
     `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"`
   - Result: `BUILD SUCCESSFUL in 15s`.
   - Verified that all Fabric 1.21.11 Yarn mappings (`WorldRenderEvents`, `GizmoDrawing`, `HudElementRegistry`, `DrawStyle`, `ClientCommandRegistrationCallback`) compile with zero errors on JDK 23.
2. **Offline Unit Test Strategy**:
   - The core math of `NavigationTarget` (target center, distance check, look focus prediction, priority ranking) is decoupled from Minecraft rendering loops and can be verified via pure Kotlin unit tests.
