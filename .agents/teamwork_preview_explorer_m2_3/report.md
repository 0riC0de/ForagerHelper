# Architecture and Implementation Specification: NodePenaltyMap & Unstuck Integration

**Module**: `com.github.foragerhelper.path.NodePenaltyMap`  
**Author**: `teamwork_preview_explorer_m2_3` (Node Penalization & Unstuck Specialist)  
**Milestone**: M2 (Hitbox-Aware Pathfinder)  
**Related Modules**: `Pathfinder.kt` (M2.1), `SweptBoxLOS.kt` (M2.2), `MovementController.kt` (M4), `UnstuckHandler.kt` (M4), `DebugWorldOverlay.kt`  

---

## 1. Executive Summary

This document specifies the complete architectural design and implementation specification for **`src/main/kotlin/com/github/foragerhelper/path/NodePenaltyMap.kt`** and its integration into the **`Pathfinder`** and **`MovementController`** subsystems of ForagerHelper (Fabric 1.21.11).

In the legacy implementation (`foraginghelpermod.client.path.WalkController`), when a player stagnated against an obstacle (e.g. an unopened gate, a wandering animal, or a geometry snag), the pathfinder was invoked without any dynamic state memory. Because A* is deterministic and static, it recomputed the exact same blocked trajectory, resulting in an **infinite stuck loop**.

`NodePenaltyMap` resolves this issue by introducing:
1. **Dynamic Spatial Penalty Memory**: An ultra-low-overhead, zero-allocation spatial penalty store indexed by primitive 64-bit packed block coordinates (`BlockPos.asLong()`).
2. **Smooth Decay Timers (TTL)**: Penalties naturally fade over time (default 20 seconds) via linear decay, ensuring temporary obstacles (such as walking sheep or momentarily closed doors) do not permanently invalidate paths.
3. **Spatial Penalty Diffusion**: Applying a potential-field falloff around the stagnation point (e.g. radius 1 with $0.5\times$ attenuation), preventing the pathfinder from "shimming" along the exact perimeter of the obstacle.
4. **Admissible A* $g$-Score Cost Integration**: Adding the localized penalty directly to the edge traversal cost ($g(v) = g(u) + \text{stepCost} + \text{penalty}(v)$), mathematically preserving heuristic admissibility while aggressively forcing detours.
5. **Multi-Tier Unstuck Recovery Protocol**: A coordinated recovery lifecycle between the movement controller, unstuck handler, and pathfinder (Tier 1 micro-nudge, Tier 2 reverse backoff + penalize + repath, Tier 3 compounding penalty + Aspect of the Void / abort).

---

## 2. Root Cause Analysis: The Legacy Infinite Stuck Loop

### 2.1 The Stagnation Defect in `WalkController.kt`
In the legacy codebase:
- **`WalkController.kt:207-215`**:
  ```kotlin
  val now = Vec3d(player.x, player.y, player.z)
  val prev = lastPos
  lastPos = now
  if (prev != null && now.squaredDistanceTo(prev) < STUCK_MOVE_SQ) {
      stuckTicks++
  } else {
      stuckTicks = 0
  }
  ```
  `STUCK_MOVE_SQ` is set to $0.0025$ ($0.05$ m per tick). If the player is blocked by an obstacle, `stuckTicks` accumulates up to `STUCK_TICKS = 20`.

- **`WalkController.kt:118-129`**:
  ```kotlin
  val needRepath =
      goal != targetLog ||
      path.isEmpty() ||
      pathIndex >= path.size ||
      ticksSincePath >= REPATH_INTERVAL ||
      stuckTicks >= STUCK_TICKS

  if (needRepath) {
      goal = targetLog.toImmutable()
      ticksSincePath = 0
      stuckTicks = 0
      val start = player.blockPos
      val result = AStarPathfinder.findPath(
          world, start, targetLog, reach,
          maxHorizontalRange = 40,
          exactDestination = exactDestination,
      )
      ...
  ```

### 2.2 Why Deterministic A* Fails on Dynamic Obstacles
1. When `stuckTicks >= 20` triggers `needRepath`, `stuckTicks` is immediately reset to 0.
2. `AStarPathfinder.findPath` queries the static block world (`world.getBlockState`).
3. If the obstacle is:
   - A dynamic entity (e.g. a cow, villager, zombie, or other player),
   - An interactive block in a closed state that the pathfinder assumes is passable,
   - A slight hitbox collision snag on an uneven stair/slab boundary or tight doorway,
4. A* evaluates the identical world graph with identical static edge weights ($1.0$ cardinal, $1.414$ diagonal).
5. A* deterministically outputs the **exact same path** `[W0, W1, W2, ...]`.
6. The movement controller applies forward WASD motion into the obstacle.
7. The player remains pinned against the obstacle, repeats for 20 ticks, repaths again, and repeats indefinitely.

### 2.3 Required Invariants to Break the Loop
To permanently eliminate the stuck loop, three conditions must be satisfied:
1. **Memory of Failure**: The system must remember that traversing node $N_{\text{blocked}}$ failed.
2. **Cost Asymmetry**: The cost of traversing $N_{\text{blocked}}$ in subsequent A* searches must be significantly higher than alternative detour corridors.
3. **Temporal Transience**: The memory must expire so that when the dynamic obstacle clears, the player can resume using optimal routes.

---

## 3. Dynamic Spatial Node Penalization Architecture

### 3.1 Zero-Allocation Spatial Indexing via Primitive 64-bit Longs
In Minecraft 1.21.11, `BlockPos` provides primitive 64-bit packing:
- `pos.asLong()`: Encodes $(x, y, z)$ into a primitive `Long` using bit shifts (26 bits for X, 12 bits for Y, 26 bits for Z).
- `BlockPos.fromLong(packedLong)`: Decodes the primitive back to an immutable `BlockPos`.

During A* execution, the pathfinder expands thousands of candidate nodes per search. Instantiating `BlockPos` objects or boxing keys in maps creates massive garbage collector pressure.
`NodePenaltyMap` provides overloaded lookup methods:
- `fun getPenalty(posLong: Long, currentTimeMs: Long): Float` — **Zero-allocation** direct primitive lookup used in the core A* neighbor expansion loop.
- `fun getPenalty(pos: BlockPos, currentTimeMs: Long): Float` — Ergonomic convenience method.

### 3.2 Internal Data Model: `PenaltyEntry`
Each penalized node is represented by a compact entry:

```kotlin
data class PenaltyEntry(
    var basePenalty: Float,
    val addedTimeMs: Long,
    var ttlMs: Long,
    var expiryTimeMs: Long,
    val reason: PenaltyReason = PenaltyReason.STAGNATION
) {
    fun computeEffectivePenalty(currentTimeMs: Long, decayMode: PenaltyDecayMode): Float {
        if (currentTimeMs >= expiryTimeMs) return 0.0f
        val elapsed = currentTimeMs - addedTimeMs
        if (elapsed <= 0L) return basePenalty

        return when (decayMode) {
            PenaltyDecayMode.STEP -> basePenalty
            PenaltyDecayMode.LINEAR -> {
                val fraction = 1.0f - (elapsed.toFloat() / ttlMs.toFloat())
                (basePenalty * fraction).coerceAtLeast(0.0f)
            }
            PenaltyDecayMode.EXPONENTIAL -> {
                // Half-life at ttlMs / 2
                val lambda = 1.386294f / ttlMs.toFloat() // ln(4)/TTL
                (basePenalty * kotlin.math.exp(-lambda * elapsed.toFloat())).coerceAtLeast(0.0f)
            }
        }
    }
}
```

### 3.3 Penalty Compounding and Maximum Cap
If a player attempts to pass an obstacle and gets stuck repeatedly in the same area:
- Repeated calls to `penalizeNode(pos, penalty)` must compound rather than being overwritten.
- Let $P_{\text{current}}$ be the existing base penalty and $P_{\text{add}}$ be the new penalty.
- The new base penalty is:
  $$P_{\text{new}} = \min(P_{\text{max}}, P_{\text{current}} + P_{\text{add}})$$
  where $P_{\text{max}} = 500.0\text{f}$.
- The expiry timestamp is refreshed to:
  $$\text{expiryTimeMs} = \max(\text{currentExpiry}, \text{currentTimeMs} + \text{ttlMs})$$
This ensures persistent blockages scale their detour repulsion while capping arithmetic costs.

---

## 4. Decay Models and Lifecycle Pruning

### 4.1 Decay Models
Three decay modes are supported:
1. **`LINEAR` (Default & Recommended)**:
   $$P_{\text{eff}}(t) = P_{\text{base}} \times \max\left(0.0, 1.0 - \frac{t - t_0}{\text{TTL}}\right)$$
   - *Advantage*: Smooth and predictable. Gradually reduces the penalty so that if an alternate route is marginal, the pathfinder will retry the primary route after the obstacle is likely gone.
2. **`STEP` (Hard TTL)**:
   $$P_{\text{eff}}(t) = \begin{cases} P_{\text{base}} & \text{if } t < t_0 + \text{TTL} \\ 0.0 & \text{otherwise} \end{cases}$$
   - *Advantage*: Constant detour threshold throughout the entire TTL duration.
3. **`EXPONENTIAL`**:
   $$P_{\text{eff}}(t) = P_{\text{base}} \cdot e^{-\lambda(t - t_0)}$$
   - *Advantage*: Smooth asymptotic decay; standard in physical potential field models.

### 4.2 Lifecycle Pruning and Bounded Memory
To prevent memory leaks across multi-hour foraging sessions:
1. **Lazy Pruning on Query**: When `getPenalty` encounters an entry where `currentTimeMs >= entry.expiryTimeMs`, it marks or removes the entry.
2. **Periodic Active Sweep**: `cleanupExpired(currentTimeMs)` iterates through entries and removes all expired records. Invoked automatically at the start of each pathfinding request.
3. **LRU Capacity Limit (`MAX_TRACKED_NODES = 1024`)**:
   - If the map size exceeds 1024 entries, entries with the earliest expiry time are evicted.
   - Total memory footprint for 1024 entries is $< 70\text{ KB}$, ensuring zero GC pauses.

---

## 5. Spatial Penalty Diffusion

### 5.1 Why Single-Node Penalization Fails (Corner-Shimming Problem)
If only the single center block $(x_0, y_0, z_0)$ where the player's feet reside is penalized:
- A* will evaluate the adjacent diagonal node $(x_0 + 1, y_0, z_0 + 1)$ or lateral node $(x_0 + 1, y_0, z_0)$ as unpenalized (cost $+1.0$).
- If the obstacle is a wide entity (e.g. an iron golem, cow, or horse with a $1.4\text{ m}$ collision box) or a 2-block closed fence gate, stepping 1 block to the side still snags the player's $0.6\times 1.8\text{ m}$ bounding box.
- Swept-box path smoothing (string-pulling) may also attempt to cut across the corner of $(x_0, y_0, z_0)$.

### 5.2 Diffusion Mathematical Formulation
When `penalizeNode(centerPos, penalty, radius = 1, falloff = 0.5f)` is called:
- For every relative offset $(\Delta x, \Delta y, \Delta z)$ in:
  $$\Delta x \in [-\text{radius}, +\text{radius}], \quad \Delta z \in [-\text{radius}, +\text{radius}], \quad \Delta y \in [-1, +1]$$
- Compute the horizontal Chebyshev distance:
  $$d_{\text{horiz}} = \max(|\Delta x|, |\Delta z|)$$
- The diffused penalty for node $(x_0 + \Delta x, y_0 + \Delta y, z_0 + \Delta z)$ is:
  $$P(\Delta x, \Delta y, \Delta z) = \text{penalty} \times (\text{falloff})^{d_{\text{horiz}}}$$
  - Center ($d=0$): $50.0 \times 1.0 = 50.0\text{f}$
  - Direct neighbors ($d=1$, orthogonal and diagonal): $50.0 \times 0.5 = 25.0\text{f}$
  - Vertical offset ($\Delta y \ne 0$): also receives the diffused penalty, ensuring stair and slab transitions adjacent to the obstacle are penalized equally.
- If a neighboring node already has a penalty $P_{\text{existing}}$, the neighbor receives $\max(P_{\text{existing}}, P_{\text{diffused}})$, preventing diffusion from overwriting higher direct penalties.

This forms a localized **cost hill** (repulsion field) in the A* cost graph, forcing the pathfinder to route completely around the blockage with at least a 1-block clear buffer.

---

## 6. A* Cost Integration ($g$-Score) & Admissibility Proof

### 6.1 Integration into A* Node Expansion
In `Pathfinder.kt`:
```kotlin
for (transition in transitions(world, current.pos)) {
    val neighbor = transition.destPos
    val stepCost = transition.traversalCost // e.g. 1.0 cardinal, 1.414 diagonal, +0.2 jump
    val dynamicPenalty = penaltyMap.getPenalty(neighbor.asLong(), searchStartTimeMs)
    
    val g = current.g + stepCost + dynamicPenalty
    
    val key = neighbor.asLong()
    val prevG = bestG[key]
    if (prevG != null && g >= prevG) continue
    
    bestG[key] = g
    val h = heuristic(neighbor, goal)
    openSet.add(PathNode(neighbor, g, h, current))
}
```

### 6.2 Proof of Heuristic Admissibility and Consistency
Let $G = (V, E)$ be the navigation graph.
- Unpenalized edge cost: $c_0(u, v) \ge 1.0$ for all $(u, v) \in E$.
- Penalized edge cost: $c_P(u, v) = c_0(u, v) + P(v)$.
- Since $P(v) \ge 0.0\text{f}$ for all $v \in V$, we have:
  $$c_P(u, v) \ge c_0(u, v)$$
- Let $h(u) = \|\vec{u} - \vec{\text{goal}}\|_2$ be the Euclidean distance heuristic.
- In Minecraft world space, the minimum travel distance between two points cannot be less than Euclidean distance divided by max speed (i.e. $h(u) \le \text{dist}_0(u, \text{goal})$).
- Since edge costs only increase under penalization:
  $$\text{dist}_P(u, \text{goal}) \ge \text{dist}_0(u, \text{goal}) \ge h(u)$$
- **Conclusion**: The heuristic $h(u)$ remains **strictly admissible** ($h(u) \le \text{true cost to goal}$) and **monotonically consistent**. A* is mathematically guaranteed to find the optimal path in the penalized cost landscape without generating suboptimal loops or failing completeness.

### 6.3 Numerical Thresholds: When A* Chooses a Detour
- Base step cost: $1.0$ per block.
- Standard default penalty: $P_0 = 50.0\text{f}$.
- Center node cost $= 1.0 + 50.0 = 51.0$.
- A* will eagerly explore any alternative detour route whose total length is up to **50 blocks longer** than the blocked path.
- **Handling Bottlenecks & Dead Ends**:
  If the obstacle is located in a 1-block tunnel with *no* possible alternative route within the search limit:
  - Because the penalty is finite ($50.0$), the open set will exhaust other options, pop the penalized node, and still find a path through it.
  - A* will **not** return "No path" (which would crash or surrender navigation).
  - This allows the unstuck system's higher recovery tiers (such as breaking the block, or Ether Warp teleportation) to activate.

---

## 7. Complete API Contract & Class Specification

### 7.1 Location and Package
- **File**: `src/main/kotlin/com/github/foragerhelper/path/NodePenaltyMap.kt`
- **Package**: `com.github.foragerhelper.path`

### 7.2 Interface: `NodePenaltyMap`
Conforms strictly to `PROJECT.md:95-108`:

```kotlin
package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos

enum class PenaltyReason {
    STAGNATION,      // Player stuck in place despite movement input
    COLLISION_SNAG,  // Hitbox swept-box collision snag detected
    DYNAMIC_MOB,     // Mob / entity obstructing corridor
    FALL_HAZARD,     // Dynamic hazard (e.g. lava, fire, cobweb)
    USER_MANUAL      // Manually placed route penalty
}

enum class PenaltyDecayMode {
    LINEAR,       // Smooth linear decay from base to 0 over TTL
    STEP,         // Full penalty until TTL, then immediately 0
    EXPONENTIAL   // Continuous exponential half-life decay
}

interface NodePenaltyMap {
    /**
     * Retrieves the current effective penalty for a block position.
     * Returns 0.0f if the node is not penalized or the penalty has expired.
     */
    fun getPenalty(pos: BlockPos, currentTimeMs: Long = System.currentTimeMillis()): Float

    /**
     * Primitive 64-bit Long lookup (pos.asLong()) to eliminate object allocations
     * during high-frequency A* node expansions.
     */
    fun getPenalty(posLong: Long, currentTimeMs: Long = System.currentTimeMillis()): Float

    /**
     * Penalizes a specific node and its surrounding neighborhood.
     *
     * @param pos The center block position to penalize.
     * @param penalty The base penalty cost added to A* g-score (default 50.0f).
     * @param radius Spatial diffusion radius (default 1 block, covers 3x3 horizontal area).
     * @param ttlMs Time-to-live in milliseconds before the penalty completely expires (default 20,000 ms).
     * @param reason The classification cause of this penalty.
     */
    fun penalizeNode(
        pos: BlockPos,
        penalty: Float = DEFAULT_PENALTY,
        radius: Int = DEFAULT_RADIUS,
        ttlMs: Long = DEFAULT_TTL_MS,
        reason: PenaltyReason = PenaltyReason.STAGNATION
    )

    /**
     * Immediately clears all active penalties.
     */
    fun clearPenalties()

    /**
     * Sweeps and removes all expired entries to prevent memory buildup.
     */
    fun cleanupExpired(currentTimeMs: Long = System.currentTimeMillis())

    /**
     * Returns a snapshot of all active penalties and their remaining weights.
     * Used by DebugWorldOverlay to draw real-time heatmaps in-game.
     */
    fun getActivePenalties(currentTimeMs: Long = System.currentTimeMillis()): Map<BlockPos, Float>

    /**
     * Current number of tracked penalized nodes.
     */
    val size: Int

    companion object {
        const val DEFAULT_PENALTY = 50.0f
        const val DEFAULT_TTL_MS = 20_000L // 20 seconds
        const val DEFAULT_RADIUS = 1
        const val DEFAULT_FALLOFF = 0.5f
        const val MAX_TRACKED_NODES = 1024
        const val MAX_ACCUMULATED_PENALTY = 500.0f
    }
}
```

### 7.3 Reference Implementation: `DefaultNodePenaltyMap`

```kotlin
package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

class DefaultNodePenaltyMap(
    private val decayMode: PenaltyDecayMode = PenaltyDecayMode.LINEAR,
    private val defaultTtlMs: Long = NodePenaltyMap.DEFAULT_TTL_MS,
    private val defaultRadius: Int = NodePenaltyMap.DEFAULT_RADIUS,
    private val defaultFalloff: Float = NodePenaltyMap.DEFAULT_FALLOFF,
    private val maxTrackedNodes: Int = NodePenaltyMap.MAX_TRACKED_NODES
) : NodePenaltyMap {

    private val lock = ReentrantLock()
    private val entries = ConcurrentHashMap<Long, PenaltyEntry>()

    override fun getPenalty(pos: BlockPos, currentTimeMs: Long): Float {
        return getPenalty(pos.asLong(), currentTimeMs)
    }

    override fun getPenalty(posLong: Long, currentTimeMs: Long): Float {
        val entry = entries[posLong] ?: return 0.0f
        if (currentTimeMs >= entry.expiryTimeMs) {
            entries.remove(posLong)
            return 0.0f
        }
        return entry.computeEffectivePenalty(currentTimeMs, decayMode)
    }

    override fun penalizeNode(
        pos: BlockPos,
        penalty: Float,
        radius: Int,
        ttlMs: Long,
        reason: PenaltyReason
    ) {
        if (penalty <= 0.0f) return
        val now = System.currentTimeMillis()

        lock.withLock {
            // Check capacity bound
            if (entries.size >= maxTrackedNodes) {
                evictOldestEntry()
            }

            // 1. Penalize center node
            applySingleNodePenalty(pos.asLong(), penalty, ttlMs, now, reason)

            // 2. Spatial Diffusion
            if (radius > 0) {
                val clampedRadius = radius.coerceIn(1, 3)
                for (dx in -clampedRadius..clampedRadius) {
                    for (dz in -clampedRadius..clampedRadius) {
                        for (dy in -1..1) {
                            if (dx == 0 && dy == 0 && dz == 0) continue
                            val dHoriz = max(kotlin.math.abs(dx), kotlin.math.abs(dz))
                            val factor = kotlin.math.pow(defaultFalloff.toDouble(), dHoriz.toDouble()).toFloat()
                            val diffusedPenalty = penalty * factor
                            if (diffusedPenalty >= 1.0f) {
                                val neighborPos = pos.add(dx, dy, dz)
                                applySingleNodePenalty(neighborPos.asLong(), diffusedPenalty, ttlMs, now, reason)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applySingleNodePenalty(
        posLong: Long,
        penalty: Float,
        ttlMs: Long,
        now: Long,
        reason: PenaltyReason
    ) {
        val expiry = now + ttlMs
        val existing = entries[posLong]
        if (existing == null) {
            entries[posLong] = PenaltyEntry(
                basePenalty = penalty.coerceAtMost(NodePenaltyMap.MAX_ACCUMULATED_PENALTY),
                addedTimeMs = now,
                ttlMs = ttlMs,
                expiryTimeMs = expiry,
                reason = reason
            )
        } else {
            // Compound penalty and refresh TTL
            val compounded = (existing.basePenalty + penalty).coerceAtMost(NodePenaltyMap.MAX_ACCUMULATED_PENALTY)
            existing.basePenalty = compounded
            existing.ttlMs = max(existing.ttlMs, ttlMs)
            existing.expiryTimeMs = max(existing.expiryTimeMs, expiry)
        }
    }

    private fun evictOldestEntry() {
        var oldestKey: Long? = null
        var earliestExpiry = Long.MAX_VALUE
        for ((key, entry) in entries) {
            if (entry.expiryTimeMs < earliestExpiry) {
                earliestExpiry = entry.expiryTimeMs
                oldestKey = key
            }
        }
        oldestKey?.let { entries.remove(it) }
    }

    override fun clearPenalties() {
        lock.withLock {
            entries.clear()
        }
    }

    override fun cleanupExpired(currentTimeMs: Long) {
        entries.entries.removeIf { (_, entry) -> currentTimeMs >= entry.expiryTimeMs }
    }

    override fun getActivePenalties(currentTimeMs: Long): Map<BlockPos, Float> {
        val result = HashMap<BlockPos, Float>()
        for ((key, entry) in entries) {
            val eff = entry.computeEffectivePenalty(currentTimeMs, decayMode)
            if (eff > 0.0f) {
                result[BlockPos.fromLong(key)] = eff
            }
        }
        return result
    }

    override val size: Int
        get() = entries.size
}
```

### 7.4 Integration into `Pathfinder.kt`
In `Pathfinder.kt`, `NodePenaltyMap` is instantiated as a component:
```kotlin
class DefaultPathfinder(
    val penaltyMap: NodePenaltyMap = DefaultNodePenaltyMap()
) : Pathfinder {

    override fun penalizeNode(pos: BlockPos, penalty: Float) {
        penaltyMap.penalizeNode(pos, penalty)
    }

    override fun clearPenalties() {
        penaltyMap.clearPenalties()
    }

    override fun findPath(world: World, start: Vec3d, goal: Vec3d, allowedRange: Double): PathResult {
        penaltyMap.cleanupExpired()
        // A* search incorporates penaltyMap.getPenalty(...) in g-score
        ...
    }
}
```

---

## 8. Multi-Tier Unstuck Maneuver Lifecycle

The complete unstuck architecture coordinates between `MovementController`, `UnstuckHandler`, and `Pathfinder`:

```
                    ┌─────────────────────────┐
                    │ MovementController.tick │
                    └────────────┬────────────┘
                                 │
                     [ Calculate Displacement ]
                                 │
           ┌─────────────────────┴─────────────────────┐
           ▼                                           ▼
[ Displacement >= 0.05m ]                   [ Displacement < 0.05m ]
           │                                           │
  stuckTicks = 0                                 stuckTicks++
  recoveryTier = NONE                                  │
                                                       ▼
                                             Evaluate Recovery Tier
                                                       │
         ┌───────────────────────────────┬─────────────┴─────────────────┐
         ▼                               ▼                               ▼
    [ Tier 1: 4-8 Ticks ]      [ Tier 2: 12-25 Ticks ]          [ Tier 3: > 30 Ticks ]
    • Micro-recovery           • Confirmed Obstacle             • Severe Deadlock
    • Set jumpKey = true       • Identify blocked node N        • Compound penalty (150f)
    • Micro-strafe (A/D)       • Reverse (backKey) 4-6 ticks    • If Ether Warp available:
    • Keep current path        • Call pathfinder.penalizeNode       teleport to target/spot
                               • Trigger immediate repath       • If no warp: abort &
                                                                    release movement keys
```

### 8.1 Detailed Tier Specifications
1. **Tier 1: Micro-Recovery (Ticks 4–8 / 0.2–0.4s)**
   - *Cause*: Small step-up snag, slab lip, or minor corner friction.
   - *Action*:
     - Assert `jumpKey.setPressed(true)` if `player.isOnGround`.
     - Alternate slight lateral strafe (e.g. 2 ticks left, 2 ticks right) to clear bounding box edges.
     - Does **not** penalize nodes or repath yet to avoid unnecessary path recalculations.
2. **Tier 2: Reverse Backoff & Dynamic Penalization (Ticks 12–25 / 0.6–1.2s)**
   - *Cause*: Impassable physical obstruction (closed gate, fence, wall, or mob).
   - *Action*:
     - Determine the blocked node: $N_{\text{blocked}} = \text{currentWaypoint.toBlockPos()}$ (or `player.blockPos.offset(facing)`).
     - **Disengage**: Release forward key and apply backward key (`backKey.setPressed(true)`) for 4 to 6 ticks ($0.2\text{s}$) to physically separate the player's bounding box from the collision surface.
     - **Penalize**: Call `pathfinder.penalizeNode(N_blocked, penalty = 50.0f, radius = 1)`.
     - **Repath**: Request a fresh path from the player's new position.
     - A* automatically selects a detour avoiding $N_{\text{blocked}}$ and its neighborhood.
     - Reset `stuckTicks = 0` upon receiving the new valid detour path.
3. **Tier 3: Severe Obstacle / Teleport Recovery / Abort (Ticks > 30 / > 1.5s)**
   - *Cause*: Trapped in a sealed enclosure, completely mob-blocked corridor, or unreachable target.
   - *Action*:
     - Re-penalize with compound weight: `pathfinder.penalizeNode(N_blocked, penalty = 150.0f, radius = 2)`.
     - If `HelperConfig.useAspectOfVoid` is enabled and Aspect of the Void is in hotbar:
       - Query `EtherWarpPlanner.findBestTarget` to attempt emergency teleport past the obstacle.
     - If teleport is disabled or fails:
       - Cleanly release all movement keys via physical GLFW hardware restore (`restorePhysicalKeyState`).
       - Set `status = "Stuck: Route blocked by obstacle"`.
       - Abort navigation safely without player jitter.

---

## 9. In-Game Debug Visualization (`DebugWorldOverlay.kt`)

`NodePenaltyMap` provides `getActivePenalties()`, enabling zero-cost debug rendering in `DebugWorldOverlay.kt` using Fabric 1.21.11 `GizmoDrawing`:

```kotlin
// In DebugWorldOverlay.kt:
if (HelperConfig.showDebugGizmos) {
    val activePenalties = pathfinder.penaltyMap.getActivePenalties()
    activePenalties.forEach { (pos, penalty) ->
        val alpha = ((penalty / 50.0f).coerceIn(0.2f, 1.0f) * 255).toInt()
        val color = (alpha shl 24) or 0x00FF3030 // Red wireframe with opacity proportional to penalty
        GizmoDrawing.box(pos, DrawStyle.stroked(color, 2.0f)).ignoreOcclusion().withLifespan(2)
    }
}
```
This gives users and testers immediate visual feedback showing the dynamic repulsion field expanding, decaying, and redirecting the player's path around obstacles.

---

## 10. Comprehensive Offline Testing Strategy

The test suite executes offline in headless CI without Minecraft window dependencies.

### 10.1 Test Matrix Across Behavioral Tiers

| Tier | Test Identifier | Purpose & Verification |
|---|---|---|
| **Tier 1 (Unit)** | `testDirectNodePenalization` | Assert `getPenalty(pos)` returns exact assigned penalty. |
| **Tier 1 (Unit)** | `testUnpenalizedNodeReturnsZero` | Unpenalized coordinates return `0.0f`. |
| **Tier 1 (Unit)** | `testPrimitiveLongEquivalence` | `getPenalty(pos.asLong()) == getPenalty(pos)`. |
| **Tier 1 (Unit)** | `testClearPenalties` | Calling `clearPenalties()` empties all tracked nodes. |
| **Tier 1 (Unit)** | `testLinearDecayHalfLife` | At $t = t_0 + \text{TTL}/2$, penalty is exactly $50\%$ of base. At $t \ge t_0 + \text{TTL}$, penalty is $0.0f$. |
| **Tier 2 (Boundaries)** | `testSpatialDiffusionRadius` | Center gets $100\%$, radius 1 orthogonal gets $50\%$, diagonal gets $50\%$, distance 2 gets $0\%$. |
| **Tier 2 (Boundaries)** | `testPenaltyCompoundingAndCap` | Multiple penalties on same node add together up to `MAX_ACCUMULATED_PENALTY` (500.0f). |
| **Tier 2 (Boundaries)** | `testTtlExpiryPruning` | `cleanupExpired` removes all records past their expiry timestamp. |
| **Tier 2 (Boundaries)** | `testMaxCapacityEviction` | Adding $> 1024$ nodes evicts oldest entries without unbounded growth or OOM. |
| **Tier 2 (Boundaries)** | `testZeroAndNegativePenaltyIgnored` | `penalizeNode(pos, penalty = -10f)` is cleanly ignored. |
| **Tier 3 (Integration)** | `testAStarDetourSelection` | In a 2-corridor maze (direct 8-block path vs 14-block detour), unpenalized selects direct. After `penalizeNode` on direct path, A* selects the 14-block detour! |
| **Tier 3 (Integration)** | `testDecayRestoresDirectPath` | Advancing synthetic clock past TTL restores direct path selection. |
| **Tier 3 (Integration)** | `testDiffusionPreventsCornerShimming` | Spatial diffusion prevents A* from generating a path scraping the immediate wall of the obstacle. |
| **Tier 4 (Workload)** | `testStagnationTriggerCycle` | Simulates 20 stagnation ticks: verifies backoff is engaged, penalty is placed, and new path diverges from stuck coordinate. |
| **Tier 4 (Workload)** | `testUnavoidableBottleneck` | In a single 1-wide dead-end tunnel, A* still finds the path through the penalized node rather than failing. |

---

## 11. Conclusion

`NodePenaltyMap.kt` delivers a clean, high-performance solution to the infinite stuck loop defect:
- **Zero GC overhead** in hot search loops via 64-bit packed coordinate lookups.
- **Physics-grounded spatial diffusion** that prevents corner shimming around multi-block or dynamic obstacles.
- **Mathematical rigor** ensuring A* heuristic admissibility is strictly maintained.
- **Robust integration** into a 3-tier unstuck recovery state machine.

This specification fulfills all requirements of Milestone 2 (R2 §4) and is fully ready for implementation.
