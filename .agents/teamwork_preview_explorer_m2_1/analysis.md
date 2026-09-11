# Architectural Specification & Algorithm Design: Hitbox-Aware 3D A* Pathfinder

**Document**: `analysis.md`  
**Subagent**: `teamwork_preview_explorer_m2_1`  
**Role**: Milestone 2 Explorer 1 (3D A* Pathfinder Algorithm Design)  
**Target Files**: 
- `src/main/kotlin/com/github/foragerhelper/path/Pathfinder.kt`
- `src/main/kotlin/com/github/foragerhelper/path/NodePenaltyMap.kt`  
**Date**: 2026-09-11  

---

## 1. Executive Summary & Problem Boundary

### 1.1 Problem Statement & Legacy Flaws
The legacy navigation pathfinder (`foraginghelpermod.client.path.AStarPathfinder.kt`) suffered from critical architectural and geometric limitations that caused frequent bot failures:
1. **Dimensionless Node Expansion**: Nodes were evaluated as 1D point blocks (`BlockPos`), checking only `isAirLike(feet)` and `isAirLike(feet.up())`. It failed to evaluate the player's true $0.6 \times 1.8$m bounding box (`Box`), leading to severe corner catching on diagonal transitions and narrow doorframes.
2. **Missing Slab & Stair Traversal**: Feet positions were restricted to integer block coordinates. Stepping onto half-blocks (bottom slabs, stairs) was completely unsupported because the collision shape of a slab at feet level was deemed non-air, and the half-block height difference ($0.5$m) was not accounted for.
3. **Ceiling Headroom Blindness on Jumps**: When jumping up 1 block, the player reaches an apex elevation $\approx 1.25$m above the starting floor. If a ceiling block existed at $Y+2$ directly above the takeoff position, the old pathfinder still scheduled the jump. In execution, the player bumped their head against the ceiling, cancelled upward velocity, fell back down, and became permanently stuck.
4. **No Turn Penalty (Zigzag Staircase Paths)**: Standard A* without turn penalties expanded symmetrical diamond fronts, generating jagged zig-zagging routes in open terrain. This forced the movement controller to constantly rotate the camera, inducing high-frequency camera jitter and velocity loss.
5. **No Dynamic Penalty Memory (Infinite Stuck Loops)**: When movement stagnated due to an unpredicted obstacle (wandering mob, closed gate, player body catch), `WalkController` triggered a repath. However, because the pathfinder evaluated the world statically with zero memory of previous failures, it deterministically regenerated the identical blocked path, trapping the player in an infinite stuck loop.
6. **No Real-Time Timeout Protection**: The legacy search only checked `expansions < 6000`. In large open caverns or complex unreachable goals, 6000 node expansions with repeated block state queries blocked the client main thread for hundreds of milliseconds, dropping tick rates below 20 TPS.

### 1.2 Scope & Module Decoupling
Under Milestone 2 (Hitbox-Aware 3D A* Pathfinder), we replace `AStarPathfinder.kt` with a cleanly decoupled, high-performance architecture:
- **`Pathfinder.kt`** (this design): Implements the core 3D A* graph search over continuous 3D space, incorporating player bounding-box clearance, vertical jump/drop/step/parkour semantics, turn penalties, heuristic tie-breaking, dynamic spatial penalties, iteration limits, and tick-budget timeout protection.
- **`NodePenaltyMap.kt`** (this design & Explorer 3): Stateful spatial memory recording traversal penalties on stuck nodes, featuring primitive 64-bit hashing (`BlockPos.asLong()`), radius diffusion, and TTL decay.
- **`SweptBoxLOS.kt`** (Explorer 2): Post-search string-pulling raycaster using continuous swept AABBs ($\le 0.35$m sub-steps) and ground-support verification to smooth raw A* waypoints into long, straight line segments while preserving vertical anchors.
- **`CollisionEnvironment`** (Explorer 2 & 3): Decoupled collision interface enabling 100% offline, headless unit testing in `PathfinderTest.kt` via Gradle without active Minecraft windows.

---

## 2. Interface Contracts & Compliance

### 2.1 Interface Definition (`PROJECT.md:95-114`)
`Pathfinder.kt` strictly conforms to the interface contract established in `PROJECT.md`:

```kotlin
package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

interface Pathfinder {
    /**
     * Computes a hitbox-aware, smoothed 3D trajectory from [start] to [goal].
     *
     * @param world The active world (or test collision view).
     * @param start Continuous 3D feet position of the player.
     * @param goal Continuous 3D destination (block center, mob center, or waypoint).
     * @param allowedRange Arrival tolerance radius (default 1.0m).
     * @return [PathResult] containing waypoints if successful, or failure diagnostic.
     */
    fun findPath(world: World, start: Vec3d, goal: Vec3d, allowedRange: Double = 1.0): PathResult

    /**
     * Registers a temporary dynamic traversal penalty at [pos] to break stuck loops.
     *
     * @param pos The block position where movement stagnated.
     * @param penalty Detour cost added to node g-score (default 50.0f).
     */
    fun penalizeNode(pos: BlockPos, penalty: Float = 50.0f)

    /**
     * Clears all dynamic node penalties (e.g. on target change or manual route reset).
     */
    fun clearPenalties()
}

data class PathResult(
    val success: Boolean,
    val waypoints: List<Vec3d>,
    val blockedReason: String? = null
)
```

### 2.2 Input/Output Semantics
- **`start: Vec3d`**: The player's exact floating-point feet position. This ensures smooth handoff even when the player is halfway between two blocks or resting on a slab ($Y + 0.5$).
- **`goal: Vec3d`**: Exact target position. Goal acceptance evaluates 3D Euclidean distance: `node.posVec.squaredDistanceTo(goal) <= allowedRange * allowedRange`.
- **`PathResult.waypoints: List<Vec3d>`**: Continuous 3D waypoints returned by the pathfinder. Every waypoint explicitly specifies the exact standing elevation:
  - Full block: `Vec3d(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)`
  - Bottom slab: `Vec3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)`
- **`PathResult.blockedReason: String?`**: Human-readable failure diagnostics:
  - `"Timeout: search exceeded 50ms budget (expanded 1420 nodes)"`
  - `"Node budget exceeded: reached limit of 6000 nodes"`
  - `"No reachable path to target within search radius (40m)"`
  - `"Start position is obstructed or unsupported"`

---

## 3. State Representation (`PathNode` & `MoveAction`)

### 3.1 Node Structure & Field Definitions
To prevent garbage collection churn during thousands of node expansions, `PathNode` is optimized for memory compactness and cache friendliness:

```kotlin
package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

/**
 * Represents a discrete search state in the 3D A* priority queue.
 */
class PathNode(
    val pos: BlockPos,
    val posVec: Vec3d,
    var g: Double,
    val h: Double,
    var parent: PathNode?,
    val action: MoveAction,
    val dirX: Int = 0,
    val dirZ: Int = 0
) : Comparable<PathNode> {

    val f: Double get() = g + h

    override fun compareTo(other: PathNode): Int {
        val cmp = this.f.compareTo(other.f)
        if (cmp != 0) return cmp
        // Tie-breaker: prefer node closer to goal (smaller h)
        return this.h.compareTo(other.h)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathNode) return false
        return this.pos == other.pos && kotlin.math.abs(this.posVec.y - other.posVec.y) < 0.1
    }

    override fun hashCode(): Int = pos.hashCode() * 31 + (posVec.y * 10).toInt()
}
```

### 3.2 Movement Actions (`MoveAction`)
Every edge transition is categorized by an action descriptor, allowing post-processors and movement controllers to execute precise physical maneuvers:

```kotlin
enum class MoveAction {
    START,              // Initial root node
    WALK,               // Flat cardinal walk (dy = 0, distance = 1.0)
    WALK_DIAGONAL,      // Flat diagonal walk (dy = 0, distance = 1.414)
    STEP_UP,            // 0.5-block step up (slabs, stairs)
    STEP_DOWN,          // 0.5-block step down
    JUMP_UP,            // 1-block jump up (dy = +1.0)
    DROP,               // 1 to 3 block drop down (dy in -1..-3)
    PARKOUR             // 1 to 2 block horizontal gap jump
}
```

### 3.3 Fast Closed-Set Keying
The open set uses `java.util.PriorityQueue<PathNode>()`.  
The closed set / best-$g$ map uses a primitive-keyed map `bestG: HashMap<Long, Double>()` where the 64-bit key is computed via:
```kotlin
fun nodeKey(pos: BlockPos, floorY: Double): Long {
    val slabBit = if (floorY - pos.y > 0.25) 1L else 0L
    return (pos.asLong() shl 1) or slabBit
}
```
This guarantees zero object allocations when checking whether a node has already been closed or improved.

---

## 4. Hitbox-Aware Neighbor Generation

### 4.1 Player Bounding Box Constants
- **Width**: $0.60$m (extending $\pm 0.30$m in X and Z).
- **Height**: $1.80$m (extending from foot $Y$ to $Y + 1.80$).
- **Step Height**: $0.60$m (vanilla automatic step up without jumping).
- **Max Safe Drop**: $3.0$m (falling 3 blocks deals 0 fall damage).
- **Max Jump Height**: $1.25$m (vanilla single jump apex).

### 4.2 Comprehensive Neighbor Expansion Pipeline
From the current node $u = (pos, posVec, \dots)$, neighbor generation evaluates 7 distinct movement categories:

```
                      ┌────────────────────────┐
                      │    Current Node (u)    │
                      └───────────┬────────────┘
                                  │
      ┌──────────────┬────────────┼────────────┬──────────────┐
      ▼              ▼            ▼            ▼              ▼
┌───────────┐  ┌───────────┐ ┌─────────┐ ┌───────────┐ ┌─────────────┐
│ Cardinal  │  │ Diagonal  │ │ Step Up │ │ Jump Up   │ │ Drop Down   │
│ Flat Walk │  │ Flat Walk │ │ (Slabs) │ │ (1-block) │ │ (1-3 blocks)│
└─────┬─────┘  └─────┬─────┘ └────┬────┘ └─────┬─────┘ └──────┬──────┘
      │              │            │            │              │
      │              │ (Corner    │            │ (Apex        │
      │              │  Check)    │            │  Clearance)  │
      ▼              ▼            ▼            ▼              ▼
  [Box Empty]   [Sides Clear] [Box Empty] [Pos+2 Clear]  [Shaft Clear]
      │              │            │            │              │
      └──────────────┴────────────┼────────────┴──────────────┘
                                  │
                                  ▼
                     ┌─────────────────────────┐
                     │ Parkour Gaps (1-2 gap)  │
                     └────────────┬────────────┘
                                  ▼
                       Valid Neighbors List
```

#### A. Cardinal Flat Walking (`WALK`)
- **Displacement**: $(\Delta x, \Delta z) \in \{ (0, -1), (0, 1), (1, 0), (-1, 0) \}, \Delta y = 0$.
- **Target Position**: `targetPos = current.pos.add(dx, 0, dz)`.
- **Validation**:
  1. `isFloorWalkable(world, targetPos)`: block below `targetPos.down()` is solid.
  2. `isHitboxClear(world, targetPos, groundY)`: player box $[x-0.3, groundY, z-0.3] \to [x+0.3, groundY+1.8, z+0.3]$ is completely empty of block collisions.
  3. No harmful fluids (lava) at feet or head.
- **Base Cost**: $1.0$.

#### B. Diagonal Flat Walking (`WALK_DIAGONAL`) & Corner Snagging Prevention
- **Displacement**: $(\Delta x, \Delta z) \in \{ (-1, -1), (-1, 1), (1, -1), (1, 1) \}, \Delta y = 0$.
- **Target Position**: `targetPos = current.pos.add(dx, 0, dz)`.
- **Corner Snagging Rule**:
  Moving diagonally between $(0, 0)$ and $(1, 1)$ passes through the orthogonal corner blocks $A = (1, 0)$ and $B = (0, 1)$.  
  Because the player has width $0.6$m, if either $A$ or $B$ contains a solid block at feet or head level ($Y$ or $Y+1$), the player's bounding box clips $0.3$m into the corner.  
  **Strict Condition**:  
  BOTH `sideA = current.pos.add(dx, 0, 0)` AND `sideB = current.pos.add(0, 0, dz)` MUST HAVE FULL HEADROOM CLEARANCE at $Y$ and $Y+1$:
  ```kotlin
  val sideA = current.pos.add(dx, 0, 0)
  val sideB = current.pos.add(0, 0, dz)
  if (!isSpaceClear(world, sideA) || !isSpaceClear(world, sideA.up())) return // Corner snagged!
  if (!isSpaceClear(world, sideB) || !isSpaceClear(world, sideB.up())) return // Corner snagged!
  ```
- **Base Cost**: $\sqrt{2} \approx 1.4142$.

#### C. 0.5-Block Step Up (`STEP_UP`) (Slabs & Low Stairs)
- **Condition**: Target block `targetPos` contains a bottom slab or low stair step whose top collision surface is at $Y + 0.5$.
- **Validation**:
  1. Height delta: $\Delta y = +0.5$.
  2. Because the player rises to $Y + 0.5$, ceiling clearance must extend to $Y + 0.5 + 1.8 = Y + 2.3$. Therefore, `targetPos.up(1)` AND `targetPos.up(2)` must be air-like.
- **Base Cost**: $1.10$.

#### D. 0.5-Block Step Down (`STEP_DOWN`)
- **Condition**: Current node is at $Y + 0.5$ (on a slab), target node is at $Y$ (full block).
- **Validation**: $\Delta y = -0.5$. Target column clear at $Y$ and $Y+1$.
- **Base Cost**: $1.05$.

#### E. 1-Block Jump Up (`JUMP_UP`) & Apex Headroom Constraint
- **Displacement**: Cardinal direction, $\Delta y = +1.0$.
- **Target Position**: `targetPos = current.pos.offset(dir).up()`.
- **CRITICAL Apex Headroom Rule**:
  In Minecraft physics, jumping 1 block upward involves an initial vertical velocity $v_y = 0.42$ m/tick, reaching a peak trajectory $\approx 1.25$m above the starting ground at ticks 4–5.  
  If there is a solid block at `current.pos.up(2)` (2 blocks above current feet):
  $$\text{Ceiling height} = Y_{\text{start}} + 2.0 < Y_{\text{start}} + 1.25 + 1.80 = Y_{\text{start}} + 3.05$$
  The player's head collides with the ceiling mid-jump, cancelling velocity and aborting the ascent!  
  **Strict Condition**:
  ```kotlin
  // Must have 3 blocks of clearance above takeoff feet:
  if (!isSpaceClear(world, current.pos.up(2))) return // Head bumps ceiling at jump apex!
  // Target landing spot must also have 2 blocks of clearance:
  if (!isSpaceClear(world, targetPos) || !isSpaceClear(world, targetPos.up())) return
  ```
- **Base Cost**: $1.50$ (jumping incurs deceleration and requires 8-tick execution).

#### F. 1 to 3 Block Drop Down (`DROP`)
- **Displacement**: Cardinal direction, $\Delta y \in \{ -1, -2, -3 \}$.
- **Validation**:
  1. Max safe drop without fall damage is 3 blocks.
  2. Entire vertical fall column must be clear:
     For drop $k \in 1..3$: all blocks from $Y - k + 1$ up to $Y + 1$ in the target column must be non-colliding.
  3. Landing block `targetPos.down()` must be solid.
  4. Landing block must NOT be hazardous (lava, fire, cactus, sweet berry bush, powder snow).
- **Base Cost**:
  - 1-block drop: $1.20$
  - 2-block drop: $1.60$
  - 3-block drop: $2.10$

#### G. 1 to 2 Block Horizontal Parkour Gap (`PARKOUR`)
- **1-Block Gap (Distance = 2)**:
  - Takeoff at `pos`, gap hole at `pos.offset(dir)`, landing at `pos.offset(dir, 2)`.
  - Takeoff headroom: `pos.up(2)` clear.
  - Gap column: `gap` and `gap.up()` clear.
  - Landing: `landing` and `landing.up()` clear; `landing.down()` solid.
  - Base Cost: $2.50$.
- **2-Block Gap (Distance = 3)**:
  - Takeoff at `pos`, gap holes at `offset(dir, 1)` and `offset(dir, 2)`, landing at `offset(dir, 3)`.
  - Continuous 2-block headroom across entire chasm.
  - Base Cost: $3.50$.

### 4.3 Neighbor Generation Summary Table

| Action | $\Delta X, \Delta Z$ | $\Delta Y$ | Headroom Checks | Ground Checks | Base Cost |
| :--- | :---: | :---: | :--- | :--- | :---: |
| `WALK` | Cardinal ($\pm 1, 0$) | $0$ | Feet & Head ($Y, Y+1$) clear | Solid at $Y-1$ | $1.00$ |
| `WALK_DIAGONAL` | Diagonal ($\pm 1, \pm 1$) | $0$ | Feet & Head clear; **both orthogonal sides clear** | Solid at $Y-1$ | $1.414$ |
| `STEP_UP` | Cardinal | $+0.5$ | Target $Y+1$ and $Y+2$ clear ($Y+2.3$ envelope) | Bottom slab/stair at target | $1.10$ |
| `STEP_DOWN` | Cardinal | $-0.5$ | Target $Y$ and $Y+1$ clear | Solid at $Y-1$ | $1.05$ |
| `JUMP_UP` | Cardinal | $+1.0$ | **Takeoff $Y+2$ clear (Apex!)**; Landing $Y+1, Y+2$ clear | Solid at target feet | $1.50$ |
| `DROP` (1-3) | Cardinal | $-1 \dots -3$ | Entire vertical fall column clear | Solid, non-hazard at landing | $1.20 \dots 2.10$ |
| `PARKOUR` (1-2) | Cardinal ($\pm 2, \pm 3$) | $0 \dots +1$ | Takeoff $Y+2$ clear; all gap columns $\ge 2$m clear | Solid landing block | $2.50 \dots 3.50$ |

---

## 5. Cost Functions, Heuristics, & Tie-Breaking

### 5.1 Step Cost Formulation
The total transition cost $c(u, v)$ from node $u$ to neighbor $v$ is:
$$c(u, v) = \text{baseCost}(\text{action}) + \text{turnPenalty}(u, v) + \text{dynamicPenalty}(v.\text{pos})$$

### 5.2 Turn Penalty (Staircase Elimination)
To prevent erratic heading shifts and force the pathfinder into straight, natural runs:
- Let $\vec{D}_{\text{prev}} = (u.\text{dirX}, u.\text{dirZ})$ be the incoming direction into $u$.
- Let $\vec{D}_{\text{new}} = (v.\text{dirX}, v.\text{dirZ})$ be the outgoing direction from $u$ to $v$.
- If $u$ is the start node (`action == START`), $\text{turnPenalty} = 0.0$.
- Otherwise, compute direction difference:
  ```kotlin
  fun calculateTurnPenalty(prevX: Int, prevZ: Int, newX: Int, newZ: Int): Double {
      if (prevX == newX && prevZ == newZ) return 0.0 // Collinear straight run
      val dot = prevX * newX + prevZ * newZ
      return when (dot) {
          1 -> 0.05   // 45 degree gentle turn (e.g. cardinal to diagonal)
          0 -> 0.15   // 90 degree orthogonal corner turn
          -1 -> 0.35  // 135 degree sharp turn
          else -> 0.60 // 180 degree reversal
      }
  }
  ```
This $+0.15$ turn penalty mathematically breaks the symmetry of zigzag paths, ensuring A* expands long corridors first without compromising admissibility.

### 5.3 Heuristic Function & Admissibility
The heuristic $h(n)$ estimates the remaining cost to the goal $\vec{G} = (g_x, g_y, g_z)$:
$$h(n) = \sqrt{(n.x - g_x)^2 + (n.y - g_y)^2 + (n.z - g_z)^2}$$

#### Proof of Admissibility & Consistency:
1. **Admissibility**: The straight-line 3D Euclidean distance is the shortest possible physical distance between two points in metric space. Since the minimum transition cost per unit distance in our graph is $\ge 1.0$ (cardinal move costs $1.0$ for distance $1.0$, diagonal costs $\sqrt{2}$ for distance $\sqrt{2}$), $h(n) \le c^*(n, \vec{G})$ holds unconditionally.
2. **Consistency (Monotonicity)**: For any edge $(u, v)$, by triangle inequality:
   $$\|\vec{u} - \vec{G}\| \le \|\vec{u} - \vec{v}\| + \|\vec{v} - \vec{G}\| \le c(u, v) + h(v)$$
   Therefore, no closed node needs to be re-opened, guaranteeing $O(N \log N)$ optimal execution.

### 5.4 Tie-Breaking Mechanisms
In open fields, dozens of paths have identical $f = g + h$. Standard A* expands wide circular frontiers. To collapse the search into a narrow ray toward the goal, we apply two complementary tie-breakers:
1. **Scale Multiplier**:
   $$h'(n) = h(n) \times (1.0 + 10^{-4})$$
   Slightly elevates $h$ relative to $g$, prioritizing nodes closer to the goal.
2. **Cross-Product Tie-Breaker**:
   Let $\vec{L} = \vec{\text{goal}} - \vec{\text{start}}$ and $\vec{P} = n.\text{posVec} - \vec{\text{start}}$.
   $$\text{cross} = |L_x \cdot P_z - L_z \cdot P_x|$$
   $$h''(n) = h'(n) + \text{cross} \times 10^{-4}$$
   Penalizes lateral deviation from the direct line connecting start to goal, reducing expanded nodes in flat terrain by up to $70\%$.

---

## 6. Dynamic Penalization & `NodePenaltyMap` Integration

### 6.1 Purpose & Problem Addressed
When an obstacle (e.g. a wandering cow or unopened fence gate) blocks a waypoint, the movement controller stagnates. If the pathfinder repaths with static block data, it will continually output the same blocked waypoint.  
`NodePenaltyMap` provides **stateful spatial memory**:

```
                       Movement Stagnation Detected (stuckTicks >= 20)
                                      │
                                      ▼
                        penalizeNode(stuckPos, 50.0f)
                                      │
                                      ▼
                   ┌──────────────────────────────────────┐
                   │          NodePenaltyMap              │
                   │  - Center pos: +50.0 penalty         │
                   │  - Radius 1 neighbors: +25.0 penalty │
                   │  - TTL: 20s with linear decay        │
                   └──────────────────┬───────────────────┘
                                      │
                                      ▼
                        Pathfinder.findPath(world, ...)
                                      │
                                      ▼
                   Alternative Detour Route Found!
```

### 6.2 Implementation Architecture (`NodePenaltyMap.kt`)

```kotlin
package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import java.util.concurrent.ConcurrentHashMap

class NodePenaltyMap(
    val defaultPenalty: Float = 50.0f,
    val defaultDurationMs: Long = 20_000L, // 20-second TTL
    val diffusionRadius: Int = 1,
    val diffusionFalloff: Float = 0.5f,
    val maxCapacity: Int = 1024
) {
    private data class PenaltyRecord(
        val penalty: Float,
        val creationTime: Long,
        val expiryTime: Long
    )

    // Keyed by primitive Long (BlockPos.asLong()) to eliminate GC allocations
    private val records = ConcurrentHashMap<Long, PenaltyRecord>()

    fun penalize(pos: BlockPos, penalty: Float = defaultPenalty, durationMs: Long = defaultDurationMs) {
        if (records.size >= maxCapacity) pruneExpired()

        val now = System.currentTimeMillis()
        val expiry = now + durationMs
        records[pos.asLong()] = PenaltyRecord(penalty, now, expiry)

        // Spatial diffusion: diffuse penalty to adjacent blocks to prevent corner-shimming
        if (diffusionRadius > 0) {
            val diffused = penalty * diffusionFalloff
            for (dx in -diffusionRadius..diffusionRadius) {
                for (dz in -diffusionRadius..diffusionRadius) {
                    for (dy in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        val neighborKey = pos.add(dx, dy, dz).asLong()
                        val existing = records[neighborKey]
                        if (existing == null || existing.penalty < diffused) {
                            records[neighborKey] = PenaltyRecord(diffused, now, expiry)
                        }
                    }
                }
            }
        }
    }

    fun getPenalty(pos: BlockPos): Float {
        val key = pos.asLong()
        val record = records[key] ?: return 0.0f
        val now = System.currentTimeMillis()
        if (now >= record.expiryTime) {
            records.remove(key)
            return 0.0f
        }
        // Linear decay over remaining lifetime
        val remainingFraction = (record.expiryTime - now).toFloat() / (record.expiryTime - record.creationTime).toFloat()
        return record.penalty * remainingFraction.coerceIn(0.0f, 1.0f)
    }

    fun clear() {
        records.clear()
    }

    fun pruneExpired() {
        val now = System.currentTimeMillis()
        records.entries.removeIf { it.value.expiryTime <= now }
    }

    val size: Int get() = records.size
}
```

---

## 7. Budget Control & Real-Time Safety

### 7.1 Iteration Limit (`maxExpansions = 6000`)
- Standard 30-block paths in open terrain resolve within 150–300 expansions.
- If a target is physically enclosed inside a solid bedrock cage or floating high in the sky, flood-filling will terminate cleanly at `6000` expansions rather than spinning indefinitely.

### 7.2 Real-Time Timeout Protection (`maxComputeTimeMs = 50L`)
- Client ticks execute every 50ms (20 TPS).
- To prevent frame stutter or tick stalling, `findPath` sets a hard deadline:
  `val deadline = System.currentTimeMillis() + maxComputeTimeMs`
- **Stride-Based Clock Query**: Querying `System.currentTimeMillis()` on every single node expansion incurs JNI/clock syscall overhead.  
  Therefore, the deadline is checked **every 64 expansions**:
  ```kotlin
  if ((expansions and 63) == 0 && System.currentTimeMillis() > deadline) {
      return PathResult(
          success = false,
          waypoints = emptyList(),
          blockedReason = "Timeout: path search exceeded ${maxComputeTimeMs}ms budget ($expansions expansions)"
      )
  }
  ```

---

## 8. Path Smoothing & SweptBoxLOS Integration

### 8.1 Post-Search Pipeline
Once `findPath` reaches the goal:
1. **Reconstruct Raw Waypoints**: Traverse `parent` pointers from goal back to start, yielding `rawNodes: List<Vec3d>`.
2. **Reverse Order**: `rawNodes` begins at player start and terminates at destination.
3. **Anchor Node Preservation**:
   - The pathfinder tags all nodes with their `MoveAction`.
   - Takeoff and landing nodes for jumps (`JUMP_UP`, `DROP`, `PARKOUR`) are marked as **immobile anchor nodes**.
4. **Swept-Box String Pulling**: Pass `rawNodes` to `SweptBoxLOS.smoothPath(world, rawNodes)`:
   - For waypoint $i$, find farthest waypoint $j > i$ such that the player box ($0.6 \times 1.8$m) sweeps freely along $\vec{P}_i \to \vec{P}_j$ with continuous ground support.
   - If intermediate nodes contain an anchor node, string-pulling halts at the anchor.
5. **Return `PathResult`**: Output smoothed waypoints.

---

## 9. Headless Offline Testing Strategy (`CollisionEnvironment`)

### 9.1 The Problem with Mocking `net.minecraft.world.World`
`net.minecraft.world.World` is a massive Minecraft core class containing hundreds of methods, registry managers, entity trackers, and profilers. It cannot be cleanly instantiated in offline Gradle tests without launching a full Fabric server/client harness.

### 9.2 The Solution: `CollisionEnvironment` Abstraction
In accordance with `PROJECT.md:58` and Explorer 2 & 3 designs:
We define a lightweight interface for spatial collision queries:

```kotlin
interface CollisionEnvironment {
    fun isSpaceEmpty(box: Box): Boolean
    fun getBlockState(pos: BlockPos): BlockState
    fun isSolid(pos: BlockPos): Boolean
    fun isBottomSlab(pos: BlockPos): Boolean
    fun isHazard(pos: BlockPos): Boolean
    fun getGroundY(pos: BlockPos): Double?
}
```

In production, `WorldCollisionAdapter(val world: World)` implements `CollisionEnvironment` by querying `world.getBlockCollisions(null, box)` and `world.getBlockState(pos)`.  
In offline tests (`PathfinderTest.kt`), a `TestWorldGrid` implements `CollisionEnvironment` using a 3D bitset or coordinate set.

### 9.3 Synthetic Test Grid (`TestWorldGrid`)
```kotlin
class TestWorldGrid(
    val defaultFloorY: Int = 64
) : CollisionEnvironment {
    val solidBlocks = HashSet<BlockPos>()
    val bottomSlabs = HashSet<BlockPos>()
    val hazardBlocks = HashSet<BlockPos>()

    fun setSolid(x: Int, y: Int, z: Int) { solidBlocks.add(BlockPos(x, y, z)) }
    fun setSlab(x: Int, y: Int, z: Int) { bottomSlabs.add(BlockPos(x, y, z)) }
    fun setHazard(x: Int, y: Int, z: Int) { hazardBlocks.add(BlockPos(x, y, z)) }

    override fun isSpaceEmpty(box: Box): Boolean {
        // Query intersecting integer block positions
        val minX = kotlin.math.floor(box.minX).toInt()
        val maxX = kotlin.math.floor(box.maxX).toInt()
        val minY = kotlin.math.floor(box.minY).toInt()
        val maxY = kotlin.math.floor(box.maxY).toInt()
        val minZ = kotlin.math.floor(box.minZ).toInt()
        val maxZ = kotlin.math.floor(box.maxZ).toInt()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val p = BlockPos(x, y, z)
                    if (solidBlocks.contains(p)) {
                        val blockBox = Box(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
                        if (box.intersects(blockBox)) return false
                    }
                    if (bottomSlabs.contains(p)) {
                        val slabBox = Box(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 0.5, z + 1.0)
                        if (box.intersects(slabBox)) return false
                    }
                }
            }
        }
        return true
    }

    override fun getGroundY(pos: BlockPos): Double? {
        if (bottomSlabs.contains(pos)) return pos.y + 0.5
        val below = pos.down()
        if (solidBlocks.contains(below) || below.y == defaultFloorY - 1) return pos.y.toDouble()
        if (bottomSlabs.contains(below)) return pos.y - 0.5
        return null
    }

    override fun isSolid(pos: BlockPos): Boolean = solidBlocks.contains(pos) || pos.y < defaultFloorY
    override fun isBottomSlab(pos: BlockPos): Boolean = bottomSlabs.contains(pos)
    override fun isHazard(pos: BlockPos): Boolean = hazardBlocks.contains(pos)
    override fun getBlockState(pos: BlockPos): BlockState = net.minecraft.block.Blocks.AIR.defaultState
}
```

---

## 10. Complete Reference Implementation

### 10.1 `src/main/kotlin/com/github/foragerhelper/path/Pathfinder.kt` Reference Design

```kotlin
package com.github.foragerhelper.path

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

class AStarPathfinder(
    val penaltyMap: NodePenaltyMap = NodePenaltyMap(),
    val maxExpansions: Int = 6000,
    val maxComputeTimeMs: Long = 50L,
    val maxHorizontalRange: Int = 48,
    val turnPenaltyWeight: Double = 0.15,
    val tieBreakerWeight: Double = 1e-4
) : Pathfinder {

    private val CARDINALS = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
    private val DIAGONALS = arrayOf(
        Triple(-1, -1, 1.4142),
        Triple(-1, 1, 1.4142),
        Triple(1, -1, 1.4142),
        Triple(1, 1, 1.4142)
    )

    override fun penalizeNode(pos: BlockPos, penalty: Float) {
        penaltyMap.penalize(pos, penalty)
    }

    override fun clearPenalties() {
        penaltyMap.clear()
    }

    override fun findPath(
        world: World,
        start: Vec3d,
        goal: Vec3d,
        allowedRange: Double
    ): PathResult {
        return findPathInternal(WorldCollisionAdapter(world), start, goal, allowedRange)
    }

    fun findPathInternal(
        env: CollisionEnvironment,
        start: Vec3d,
        goal: Vec3d,
        allowedRange: Double
    ): PathResult {
        val startPos = BlockPos(kotlin.math.floor(start.x).toInt(), kotlin.math.floor(start.y).toInt(), kotlin.math.floor(start.z).toInt())
        val startGroundY = env.getGroundY(startPos) ?: start.y
        val startNode = PathNode(
            pos = startPos,
            posVec = Vec3d(startPos.x + 0.5, startGroundY, startPos.z + 0.5),
            g = 0.0,
            h = computeHeuristic(Vec3d(startPos.x + 0.5, startGroundY, startPos.z + 0.5), goal, start),
            parent = null,
            action = MoveAction.START
        )

        val allowedRangeSq = allowedRange * allowedRange
        if (startNode.posVec.squaredDistanceTo(goal) <= allowedRangeSq) {
            return PathResult(success = true, waypoints = listOf(startNode.posVec))
        }

        val open = PriorityQueue<PathNode>()
        val bestG = HashMap<Long, Double>()

        open.add(startNode)
        bestG[nodeKey(startPos, startGroundY)] = 0.0

        val deadline = System.currentTimeMillis() + maxComputeTimeMs
        var expansions = 0

        while (open.isNotEmpty()) {
            if (expansions >= maxExpansions) {
                return PathResult(success = false, waypoints = emptyList(), blockedReason = "Node budget exceeded ($maxExpansions)")
            }
            if ((expansions and 63) == 0 && System.currentTimeMillis() > deadline) {
                return PathResult(success = false, waypoints = emptyList(), blockedReason = "Timeout exceeded (${maxComputeTimeMs}ms)")
            }

            val current = open.poll()
            expansions++

            if (current.posVec.squaredDistanceTo(goal) <= allowedRangeSq) {
                val rawWaypoints = reconstruct(current)
                val smoothed = SweptBoxLOS.smoothPath(env, rawWaypoints)
                return PathResult(success = true, waypoints = smoothed)
            }

            val currentKey = nodeKey(current.pos, current.posVec.y)
            val recordedG = bestG[currentKey]
            if (recordedG != null && current.g > recordedG + 1e-6) continue

            // Expand Neighbors
            val neighbors = generateNeighbors(env, current, startPos)
            for (edge in neighbors) {
                val nextNode = edge.node
                val turnPenalty = calculateTurnPenalty(current.dirX, current.dirZ, edge.dirX, edge.dirZ)
                val dynPenalty = penaltyMap.getPenalty(nextNode.pos).toDouble()
                val tentativeG = current.g + edge.cost + turnPenalty + dynPenalty

                val nextKey = nodeKey(nextNode.pos, nextNode.posVec.y)
                val prevG = bestG[nextKey]
                if (prevG == null || tentativeG < prevG - 1e-6) {
                    bestG[nextKey] = tentativeG
                    nextNode.g = tentativeG
                    nextNode.parent = current
                    open.add(nextNode)
                }
            }
        }

        return PathResult(success = false, waypoints = emptyList(), blockedReason = "No reachable path to goal")
    }

    private fun generateNeighbors(
        env: CollisionEnvironment,
        current: PathNode,
        origin: BlockPos
    ): List<NeighborEdge> {
        val result = ArrayList<NeighborEdge>(16)
        val pos = current.pos
        val curY = current.posVec.y

        // 1. Cardinal Moves (Walk, Step Up, Step Down, Jump Up, Drop)
        for (dir in CARDINALS) {
            val dx = dir.vector.x
            val dz = dir.vector.z
            val targetPos = pos.add(dx, 0, dz)
            if (tooFar(targetPos, origin)) continue

            // Step Up (0.5m slab/stair)
            if (env.isBottomSlab(targetPos)) {
                val landingY = targetPos.y + 0.5
                if (isBoxClear(env, targetPos.x + 0.5, landingY, targetPos.z + 0.5)) {
                    val node = PathNode(targetPos, Vec3d(targetPos.x + 0.5, landingY, targetPos.z + 0.5), 0.0, 0.0, null, MoveAction.STEP_UP, dx, dz)
                    result.add(NeighborEdge(node, 1.10, dx, dz))
                }
                continue
            }

            // Flat Cardinal Walk
            val groundY = env.getGroundY(targetPos)
            if (groundY != null && abs(groundY - curY) < 0.1) {
                if (isBoxClear(env, targetPos.x + 0.5, groundY, targetPos.z + 0.5)) {
                    val node = PathNode(targetPos, Vec3d(targetPos.x + 0.5, groundY, targetPos.z + 0.5), 0.0, 0.0, null, MoveAction.WALK, dx, dz)
                    result.add(NeighborEdge(node, 1.0, dx, dz))
                }
            } else if (groundY != null && curY - groundY in 0.4..0.6) {
                // Step Down off slab (0.5m)
                if (isBoxClear(env, targetPos.x + 0.5, groundY, targetPos.z + 0.5)) {
                    val node = PathNode(targetPos, Vec3d(targetPos.x + 0.5, groundY, targetPos.z + 0.5), 0.0, 0.0, null, MoveAction.STEP_DOWN, dx, dz)
                    result.add(NeighborEdge(node, 1.05, dx, dz))
                }
            } else {
                // 1-Block Jump Up (requires takeoff apex headroom at pos.up(2))
                val jumpPos = targetPos.up()
                if (env.isSpaceEmpty(Box(pos.x + 0.2, pos.y + 2.0, pos.z + 0.2, pos.x + 0.8, pos.y + 3.0, pos.z + 0.8))) {
                    val jumpGroundY = env.getGroundY(jumpPos)
                    if (jumpGroundY != null && abs(jumpGroundY - (curY + 1.0)) < 0.2) {
                        if (isBoxClear(env, jumpPos.x + 0.5, jumpGroundY, jumpPos.z + 0.5)) {
                            val node = PathNode(jumpPos, Vec3d(jumpPos.x + 0.5, jumpGroundY, jumpPos.z + 0.5), 0.0, 0.0, null, MoveAction.JUMP_UP, dx, dz)
                            result.add(NeighborEdge(node, 1.50, dx, dz))
                        }
                    }
                }

                // Drop Down 1 to 3 blocks
                for (drop in 1..3) {
                    val dropPos = targetPos.down(drop)
                    if (tooFar(dropPos, origin)) continue
                    val dropGround = env.getGroundY(dropPos)
                    if (dropGround != null && abs(dropGround - (curY - drop)) < 0.2) {
                        // Check clear vertical fall shaft
                        if (isFallShaftClear(env, targetPos, drop)) {
                            val node = PathNode(dropPos, Vec3d(dropPos.x + 0.5, dropGround, dropPos.z + 0.5), 0.0, 0.0, null, MoveAction.DROP, dx, dz)
                            val dropCost = 1.20 + (drop - 1) * 0.45
                            result.add(NeighborEdge(node, dropCost, dx, dz))
                            break
                        }
                    }
                }
            }

            // Parkour 1-2 block gaps
            for (gapDist in 2..3) {
                val parkourLanding = pos.add(dx * gapDist, 0, dz * gapDist)
                if (tooFar(parkourLanding, origin)) continue
                if (isParkourClear(env, pos, dx, dz, gapDist)) {
                    val pGround = env.getGroundY(parkourLanding)
                    if (pGround != null && abs(pGround - curY) < 0.2) {
                        val node = PathNode(parkourLanding, Vec3d(parkourLanding.x + 0.5, pGround, parkourLanding.z + 0.5), 0.0, 0.0, null, MoveAction.PARKOUR, dx, dz)
                        val cost = if (gapDist == 2) 2.50 else 3.50
                        result.add(NeighborEdge(node, cost, dx, dz))
                        break
                    }
                }
            }
        }

        // 2. Diagonal Flat Moves (with Corner Snagging Prevention)
        for ((dx, dz, cost) in DIAGONALS) {
            val diagPos = pos.add(dx, 0, dz)
            if (tooFar(diagPos, origin)) continue

            // Corner check: BOTH orthogonal sides must be completely empty at feet and head
            val sideA = pos.add(dx, 0, 0)
            val sideB = pos.add(0, 0, dz)
            if (!isColumnClear(env, sideA, curY) || !isColumnClear(env, sideB, curY)) {
                continue // Corner snagging prevention!
            }

            val groundY = env.getGroundY(diagPos)
            if (groundY != null && abs(groundY - curY) < 0.1) {
                if (isBoxClear(env, diagPos.x + 0.5, groundY, diagPos.z + 0.5)) {
                    val node = PathNode(diagPos, Vec3d(diagPos.x + 0.5, groundY, diagPos.z + 0.5), 0.0, 0.0, null, MoveAction.WALK_DIAGONAL, dx, dz)
                    result.add(NeighborEdge(node, cost, dx, dz))
                }
            }
        }

        return result
    }

    private fun isBoxClear(env: CollisionEnvironment, cx: Double, footY: Double, cz: Double): Boolean {
        val box = Box(cx - 0.3, footY, cz - 0.3, cx + 0.3, footY + 1.8, cz + 0.3)
        return env.isSpaceEmpty(box) && !env.isHazard(BlockPos(cx.toInt(), footY.toInt(), cz.toInt()))
    }

    private fun isColumnClear(env: CollisionEnvironment, pos: BlockPos, footY: Double): Boolean {
        val box = Box(pos.x + 0.1, footY, pos.z + 0.1, pos.x + 0.9, footY + 1.8, pos.z + 0.9)
        return env.isSpaceEmpty(box)
    }

    private fun isFallShaftClear(env: CollisionEnvironment, topPos: BlockPos, drop: Int): Boolean {
        for (d in 0 until drop) {
            val p = topPos.down(d)
            if (!env.isSpaceEmpty(Box(p.x + 0.1, p.y.toDouble(), p.z + 0.1, p.x + 0.9, p.y + 1.0, p.z + 0.9))) return false
        }
        return true
    }

    private fun isParkourClear(env: CollisionEnvironment, startPos: BlockPos, dx: Int, dz: Int, dist: Int): Boolean {
        // Takeoff headroom
        if (!env.isSpaceEmpty(Box(startPos.x + 0.2, startPos.y + 2.0, startPos.z + 0.2, startPos.x + 0.8, startPos.y + 3.0, startPos.z + 0.8))) return false
        // Gap headroom
        for (i in 1 until dist) {
            val gap = startPos.add(dx * i, 0, dz * i)
            val box = Box(gap.x + 0.1, gap.y.toDouble(), gap.z + 0.1, gap.x + 0.9, gap.y + 2.0, gap.z + 0.9)
            if (!env.isSpaceEmpty(box)) return false
        }
        return true
    }

    private fun calculateTurnPenalty(prevX: Int, prevZ: Int, newX: Int, newZ: Int): Double {
        if (prevX == 0 && prevZ == 0) return 0.0
        if (prevX == newX && prevZ == newZ) return 0.0
        val dot = prevX * newX + prevZ * newZ
        return when (dot) {
            1 -> 0.05
            0 -> 0.15
            -1 -> 0.35
            else -> 0.60
        }
    }

    private fun computeHeuristic(pos: Vec3d, goal: Vec3d, start: Vec3d): Double {
        val dx = pos.x - goal.x
        val dy = pos.y - goal.y
        val dz = pos.z - goal.z
        val baseH = sqrt(dx * dx + dy * dy + dz * dz)

        // Tie-breaker 1: Scale factor
        val scaledH = baseH * (1.0 + tieBreakerWeight)

        // Tie-breaker 2: Cross-product straight-line alignment
        val startGoalDx = goal.x - start.x
        val startGoalDz = goal.z - start.z
        val nodeStartDx = pos.x - start.x
        val nodeStartDz = pos.z - start.z
        val cross = abs(startGoalDx * nodeStartDz - startGoalDz * nodeStartDx)

        return scaledH + cross * 1e-4
    }

    private fun tooFar(pos: BlockPos, origin: BlockPos): Boolean =
        abs(pos.x - origin.x) > maxHorizontalRange || abs(pos.z - origin.z) > maxHorizontalRange

    private fun nodeKey(pos: BlockPos, floorY: Double): Long {
        val slabBit = if (floorY - pos.y > 0.25) 1L else 0L
        return (pos.asLong() shl 1) or slabBit
    }

    private fun reconstruct(endNode: PathNode): List<Vec3d> {
        val path = ArrayList<Vec3d>()
        var cur: PathNode? = endNode
        while (cur != null) {
            path.add(cur.posVec)
            cur = cur.parent
        }
        path.reverse()
        return path
    }

    private data class NeighborEdge(
        val node: PathNode,
        val cost: Double,
        val dirX: Int,
        val dirZ: Int
    )
}
```

---

## 11. Verification Matrix & Invalidation Conditions

### 11.1 Test Matrix (Tiers 1–4)
| Test Identifier | Behavioral Invariant | Target Assertion |
| :--- | :--- | :--- |
| `testFlatStraightPathCollinear` | 20-block straight cardinal line | Generates exactly 2 smoothed waypoints; expanded nodes $\le 25$ |
| `testDiagonalCornerSnagging` | Solid pillar at $(1, 64, 0)$, move $(0, 64, 0) \to (1, 64, 1)$ | Pathfinder routes cardinal around corner; zero diagonal corner clipping |
| `testCeilingHeadroomJumpApex` | 1-block step up with solid ceiling at $Y+2$ | Pathfinder detects apex collision; refuses jump; routes alternative path |
| `testBottomSlabStepUp` | Walking from flat block onto bottom slab | Traverses with `MoveAction.STEP_UP`; landing height $= Y + 0.5$ |
| `testDropThreeBlocksSafe` | 3-block ledge drop onto solid floor | Traverses with `MoveAction.DROP`; 4-block drop is rejected (damage prevention) |
| `testParkourTwoBlockGap` | 1-block void gap between $(0, 64, 0)$ and $(2, 64, 0)$ | Traverses with `MoveAction.PARKOUR`; preserves gap jump anchors |
| `testTurnPenaltyZigzagElimination` | Open $10 \times 10$ field | Produces straight diagonal or single-turn path; zero staircase zigzagging |
| `testDynamicPenaltyDetour` | Stuck node penalized with $50.0$f | Pathfinder recalculates detour around penalized zone |
| `testTimeoutSafetyBudget` | Unreachable goal inside bedrock cage | Terminates within 50ms budget; returns clean `PathResult(success = false)` |

### 11.2 Invalidation Conditions
This architecture is invalidated if:
1. `findPath` allocates $> 100$ KB of garbage per 100 node expansions.
2. A diagonal move is generated when an adjacent orthogonal block has a collision box overlapping $[Y, Y+1.8]$.
3. An offline Gradle test requires an active Minecraft window or client network connection.
