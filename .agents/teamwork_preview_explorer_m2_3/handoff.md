# Handoff Report: NodePenaltyMap Architecture & Unstuck Integration

**Author**: `teamwork_preview_explorer_m2_3` (Node Penalization & Unstuck Specialist)  
**Milestone**: M2 (Hitbox-Aware Pathfinder)  
**Recipient**: Orchestrator parent (`c19b23eb-08dd-4cd6-b5a9-8f12e36c1a4c`) & Implementers  
**Target Specification Document**: `report.md` (in current working directory)  

---

## 1. Observation

1. **Legacy Infinite Stuck Loop**:
   - In `src/main/kotlin/foraginghelpermod/client/path/WalkController.kt:207-215`:
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
   - In `WalkController.kt:118-129`:
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
     ```
     When `stuckTicks >= STUCK_TICKS` (20 ticks), `needRepath` triggers, resets `stuckTicks = 0`, and calls `AStarPathfinder.findPath`.

2. **Static Determinism in Pathfinder**:
   - In `src/main/kotlin/foraginghelpermod/client/path/AStarPathfinder.kt:47-75`:
     A* search uses purely static graph edge evaluation:
     ```kotlin
     for ((neighbor, stepCost) in neighbors(world, current.pos, startStand, maxHorizontalRange)) {
         val g = current.g + stepCost
     ```
     The pathfinder has zero knowledge or memory of previous failed movements or blocked nodes. It deterministically returns the exact same path, causing an infinite stuck loop where the player continually walks into the obstacle.

3. **Architectural Contracts in `PROJECT.md:95-108`**:
   - Pathfinder interface specifies:
     ```kotlin
     interface Pathfinder {
         fun findPath(world: World, start: Vec3d, goal: Vec3d, allowedRange: Double = 1.0): PathResult
         fun penalizeNode(pos: BlockPos, penalty: Float = 50.0f)
         fun clearPenalties()
     }
     ```

4. **Code Layout & Allocation Budget**:
   - In `PROJECT.md:157-160`:
     - File destination: `src/main/kotlin/com/github/foragerhelper/path/NodePenaltyMap.kt`.
   - In `TEST_INFRA.md:21-22`:
     - Features F8 (Dynamic Node Penalization) and F9 (Multi-Tier Unstuck Maneuvers) require comprehensive Tiers 1-4 unit, boundary, integration, and workload tests.
   - High frequency A* graph expansions evaluate thousands of nodes per search; object creation (boxing `BlockPos` or allocating wrapper objects) in hot loops creates GC stutter.

---

## 2. Logic Chain

1. From **Observation 1**, `WalkController` detects player stagnation via `stuckTicks`, but simply invokes `AStarPathfinder.findPath` from the player's position without recording which node or waypoint blocked progress.
2. From **Observation 2**, because A* is deterministic and operates only on static block states, finding a path between the same start and goal under unchanged world state yields the identical blocked path. The player restarts walking toward the same obstacle, creating an infinite stuck loop.
3. Therefore, to break this infinite loop, the navigation engine requires a **stateful spatial memory** (`NodePenaltyMap`) that can register traversal penalties on failing coordinates.
4. From **Observation 3**, `Pathfinder` exposes `penalizeNode(pos: BlockPos, penalty: Float = 50.0f)` and `clearPenalties()`. By integrating `NodePenaltyMap` into `Pathfinder`, when `stuckTicks` accumulates, the movement controller can call `penalizeNode(blockedNode, 50.0f)`.
5. In A*, adding dynamic node penalty to the neighbor expansion $g$-score ($g(v) = g(u) + \text{stepCost} + \text{penalty}(v)$) guarantees that:
   - Since $\text{penalty}(v) \ge 0$, edge costs only increase, so Euclidean heuristic $h(v)$ remains strictly admissible ($h(v) \le \text{true remaining cost}$).
   - A penalty of $50.0\text{f}$ represents a 50-block virtual detour cost, mathematically compelling A* to select an alternative detour corridor whenever one exists.
6. If only the single point $(x, y, z)$ is penalized, dynamic obstacles with collision width $\ge 1.0\text{m}$ (e.g. mobs, 2-wide fence gates) will snag the player on adjacent nodes $(x+1, y, z)$ or $(x, y, z+1)$ ("corner-shimming"). Hence, spatial diffusion with radius 1 and $0.5\times$ falloff must be applied to surrounding horizontal and vertical neighbors.
7. Real-world obstacles in Minecraft are frequently temporary (e.g. wandering animals or opening doors). To prevent permanently poisoning routes, penalties must decay over a Time-To-Live (TTL) timer (default 20 seconds via linear decay).
8. From **Observation 4**, to eliminate garbage collection stutter during A* searches, `NodePenaltyMap` must provide primitive 64-bit lookups via `BlockPos.asLong()`, with bounded memory capacity (1024 nodes) and LRU eviction.
9. To complete the unstuck loop, a multi-tier recovery lifecycle is formulated: Tier 1 (jump/strafe micro-nudge at 4–8 ticks), Tier 2 (reverse backoff 4–6 ticks + penalize node + repath at 12–25 ticks), Tier 3 (compound penalty + Ether Warp teleport or abort at >30 ticks).

---

## 3. Caveats

1. **Unavoidable Bottlenecks**: In a 1-wide dead-end tunnel with zero alternative detours, A* will still eventually traverse the penalized node once all other open-set possibilities are exhausted (because the penalty is finite, e.g. $+50.0$, rather than infinity/blocked). This is intentional so navigation does not crash or surrender prematurely, allowing Tier 3 unstuck (Ether Warp / manual abort) to handle the dead-end.
2. **Mob Motion**: If an obstructing mob moves 5 blocks away, its old position remains penalized until the TTL decay elapses (up to 20 seconds). This slight conservatism is acceptable and anti-cheat safe.
3. **MovementController Implementation Boundary**: While the complete unstuck coordination protocol has been specified here, the physical WASD keypress generation and client options manipulation reside in Milestone 4 (`movement/MovementController.kt` and `movement/UnstuckHandler.kt`).

---

## 4. Conclusion

The architecture and implementation specification for `NodePenaltyMap.kt` is complete and documented in detail in `report.md`. It provides:
- A high-performance, primitive-indexed (`BlockPos.asLong()`) spatial penalty map with zero GC allocation in search loops.
- Configurable linear decay over a 20-second TTL and bounded memory (1024 nodes) with LRU eviction.
- Radius-1 spatial diffusion with $0.5\times$ falloff to prevent obstacle perimeter shimming.
- Mathematically proven admissible A* $g$-score integration ($g(v) = g(u) + \text{stepCost} + \text{penalty}(v)$).
- A 3-tier unstuck recovery protocol resolving the infinite stuck loop in `WalkController.kt:118-129`.
- A full 15-case test suite specification spanning Tiers 1-4 for headless CI verification.

---

## 5. Verification Method

### 5.1 Artifacts to Inspect
- Detailed architectural specification: `.agents/teamwork_preview_explorer_m2_3/report.md`
- Target production code location: `src/main/kotlin/com/github/foragerhelper/path/NodePenaltyMap.kt`
- Target test suite location: `src/test/kotlin/com/github/foragerhelper/path/NodePenaltyMapTest.kt`

### 5.2 Verification Commands
Once implemented by the worker agent, verify compilation and tests via:
1. `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"`
2. `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test --tests *NodePenaltyMap*"`

### 5.3 Invalidation Conditions
This specification is invalidated if:
- A* heuristic becomes inadmissible (e.g. if negative penalties are allowed).
- Querying node penalties allocates objects on the heap during A* graph search.
- Penalizing a node causes A* to fail completely when the only available route passes through that node.
- Penalties do not decay, causing memory to grow unboundedly over hours of navigation.
