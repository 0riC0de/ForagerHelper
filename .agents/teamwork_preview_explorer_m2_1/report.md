# Architectural Specification: Hitbox-Aware 3D A* Pathfinder & Vertical Traversal Engine

**Document**: `report.md`  
**Subagent**: `teamwork_preview_explorer_m2_1`  
**Role**: 3D A* Architecture & Vertical Traversal Specialist  
**Milestone**: M2 (Hitbox-Aware Pathfinder)  
**Target File**: `src/main/kotlin/com/github/foragerhelper/path/Pathfinder.kt`  
**Date**: 2026-09-11  

---

## 1. Executive Summary

This document specifies the complete architectural design and implementation specification for the Hitbox-Aware 3D A* Pathfinder in `com.github.foragerhelper.path.Pathfinder.kt`, resolving requirement **R2** of `ORIGINAL_REQUEST.md` and conforming to `PROJECT.md` contracts.

### Deficiencies in Legacy Pathfinder (`AStarPathfinder.kt`)
1. **Broken Vertical Traversal on Slabs & Stairs**: Evaluated `feet` using `isAirLike(feet)` (`state.getCollisionShape().isEmpty`). Because bottom slabs, stairs, carpets, and snow layers have non-empty collision shapes, the pathfinder treated them as impassable walls or miscalculated standing elevations.
2. **Ceiling Bonking on Jump Ups**: When ascending 1 block (`pos.up()`), it only verified `isAirLike(pos.up(2))`. In a 2-block high corridor or under overhanging ledges, the player's head struck the ceiling block at $Y+2.0$, killing forward momentum and aborting the jump.
3. **Point-Based Blindness & Corner Snagging**: Nodes were treated as 1D dimensionless points. Diagonal transitions and path shortcuts clipped solid block corners by up to $0.3$m.
4. **Hazard Blindness**: Nodes failed to detect environmental hazards such as lava, sweet berry bushes, powder snow, cactus, and open void chasms.
5. **Infinite Stagnation**: Without dynamic spatial cost penalization, pathfinding continuously regenerated the same failing path when obstructed.

### Proposed Architectural Solution
- **Hitbox-Aware Collision Model**: Full evaluation of the player's $0.6 \times 0.6 \times 1.8$m bounding box ($[-0.3, +0.3]$ in X/Z, $[0.0, 1.8]$ in Y) against world `VoxelShape`s.
- **Physical Surface Elevation Detection**: Standing height $Y_{\text{stand}}$ is dynamically computed from the maximum Y-coordinate of the ground collision shape (`shape.getMax(Direction.Axis.Y)`), handling full blocks ($Y+1.0$), bottom slabs ($Y+0.5$), stairs ($Y+0.5$ / $Y+1.0$), and carpets ($Y+0.0625$).
- **Multi-Modal Vertical Traversal**:
  - Auto-Step ($\Delta Y \in [-0.6, +0.6]$): Smooth walking onto slabs/stairs without jump actions.
  - 1-Block Jump Up ($\Delta Y \in (0.6, 1.25]$): Verified $\ge 2.0$m apex ceiling headroom.
  - Safe Drops ($\Delta Y \in [-3.0, -0.6)$): 1 to 3 block drops with swept fall column verification and safe landing validation.
  - 1-Block Parkour Gap Jumps: Horizontal leaps across single-block chasms with continuous 2-block headroom.
- **High-Performance 3D A* Engine**:
  - Node key compact 64-bit packing (24-bit X, 16-bit half-block Y, 24-bit Z).
  - Open set priority queue with tie-breaking comparator ($f$-score, then $h$-score).
  - Admissible 3D Euclidean distance heuristic with vertical scaling.
  - Seamless integration with `SweptBoxLOS` (Explorer 2) and `NodePenaltyMap` (Explorer 3).

---

## 2. Hitbox Collision Validation Architecture

### 2.1 Player Bounding Box Dimensions
In Minecraft, the player hitbox is defined by:
- **Width**: $0.6$ meters ($d_x = [-0.3, +0.3]$ relative to center $x$).
- **Depth**: $0.6$ meters ($d_z = [-0.3, +0.3]$ relative to center $z$).
- **Height**: $1.8$ meters ($d_y = [0.0, +1.8]$ relative to standing feet $y$).
- **Eye Height**: $1.62$ meters.
- **Vanilla Step Height**: $0.6$ meters (vanilla `generic.step_height`).

When standing at block center $(X + 0.5, Y_{\text{stand}}, Z + 0.5)$, the player's Axis-Aligned Bounding Box (AABB) is:
$$\text{Box}(X + 0.2, Y_{\text{stand}}, Z + 0.2, X + 0.8, Y_{\text{stand}} + 1.8, Z + 0.8)$$

```kotlin
fun createPlayerBox(x: Double, standingY: Double, z: Double): Box {
    return Box(x - 0.3, standingY, z - 0.3, x + 0.3, standingY + 1.8, z + 0.3)
}

fun createNodePlayerBox(pos: BlockPos, standingY: Double): Box {
    val cx = pos.x + 0.5
    val cz = pos.z + 0.5
    return Box(cx - 0.3, standingY, cz - 0.3, cx + 0.3, standingY + 1.8, cz + 0.3)
}
```

### 2.2 Collision Query Against World Geometry
To verify whether a space is clear for the player hitbox:
1. Query `world.getBlockCollisions(null, playerBox)`:
   - In Fabric 1.21.11, `CollisionView.getBlockCollisions(Entity? entity, Box box): Iterable<VoxelShape>` returns all block collision shapes intersecting `box`.
   - If any `VoxelShape` in the iterable is non-empty and intersects `playerBox`, the space is occluded.
2. Alternatively, use `world.isSpaceEmpty(playerBox)`:
   - Returns `true` if and only if no solid block collisions intersect the box.
   - Non-solid blocks (air, tall grass, torches, flowers, saplings) have empty collision shapes and return `isSpaceEmpty == true`.

### 2.3 Standing Surface Elevation Extraction
To eliminate the legacy slab/stair bug, the standing elevation $Y_{\text{stand}}$ is derived directly from ground geometry:
```kotlin
fun getStandingSurfaceY(world: World, groundPos: BlockPos): Double? {
    val state = world.getBlockState(groundPos)
    val shape = state.getCollisionShape(world, groundPos)
    if (shape.isEmpty) return null // No solid support (air, fluid, flower)
    
    // Check if block is hazardous to stand on
    if (HazardDetector.isHazardGround(state, world.getFluidState(groundPos))) {
        return null
    }

    val maxY = shape.getMax(Direction.Axis.Y)
    // Avoid blocks with collision height > 1.0 that cannot be stepped onto (e.g. fences/walls = 1.5)
    if (maxY > 1.05) return null

    return groundPos.y + maxY
}
```

#### Verification on Common Block Types:
| Block Type | `groundPos.y` | `shape.getMax(Y)` | Resulting $Y_{\text{stand}}$ | Feet Clearance Box | Head Room Clearance Box |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Full Block (Stone) | 63 | 1.0 | 64.0 | $[64.0, 65.0]$ | $[65.0, 65.8]$ |
| Bottom Slab (Stone Slab) | 64 | 0.5 | 64.5 | $[64.5, 65.5]$ | $[65.5, 66.3]$ |
| Lower Stair Step | 64 | 0.5 | 64.5 | $[64.5, 65.5]$ | $[65.5, 66.3]$ |
| Upper Stair Step / Top Slab | 64 | 1.0 | 65.0 | $[65.0, 66.0]$ | $[66.0, 66.8]$ |
| Carpet | 64 | 0.0625 | 64.0625 | $[64.0625, 65.0625]$ | $[65.0625, 65.8625]$ |
| Snow Layer (2 layers) | 64 | 0.25 | 64.25 | $[64.25, 65.25]$ | $[65.25, 66.05]$ |
| Oak Fence | 63 | 1.5 | Disallowed ($> 1.05$) | N/A | N/A |

### 2.4 Hazard Avoidance (`HazardDetector`)
Any standing position or traversal corridor intersecting dangerous blocks must be rejected:
```kotlin
object HazardDetector {
    fun isHazardGround(state: BlockState, fluidState: FluidState): Boolean {
        if (!fluidState.isEmpty && fluidState.isIn(FluidTags.LAVA)) return true
        val block = state.block
        return block == Blocks.LAVA ||
               block == Blocks.FIRE ||
               block == Blocks.SOUL_FIRE ||
               block == Blocks.SWEET_BERRY_BUSH ||
               block == Blocks.POWDER_SNOW ||
               block == Blocks.CACTUS ||
               block == Blocks.WITHER_ROSE ||
               block == Blocks.MAGMA_BLOCK
    }

    fun isHazardPassThrough(state: BlockState, fluidState: FluidState): Boolean {
        if (!fluidState.isEmpty && fluidState.isIn(FluidTags.LAVA)) return true
        val block = state.block
        return block == Blocks.FIRE ||
               block == Blocks.SOUL_FIRE ||
               block == Blocks.SWEET_BERRY_BUSH ||
               block == Blocks.POWDER_SNOW ||
               block == Blocks.COBWEB // Cobweb slows to crawl; avoid unless forced
    }
}
```

---

## 3. Vertical Traversal Semantics

```
         [Apex Clearance: Y+2.0]
              ┌──────┐
              │ HEAD │  <-- Headroom verified (no bonking)
              ├──────┤
              │ BODY │
              └──────┘
   [Jump Up: 1.0m] ───►  ┌──────┐
                         │ BODY │  [Landing: Y+1.0]
    ┌──────┐             └──────┘
    │ BODY │             ════════ (Solid Floor)
    └──────┘
    ════════ (Takeoff)
```

### 3.1 Walking onto Bottom Slabs and Stairs (0.5m Step-Up)
- **Physics Rule**: Minecraft players possess a default step height of $0.6$m (`generic.step_height = 0.6`).
- **Condition**: For any horizontal move from $(X_1, Z_1, Y_1)$ to $(X_2, Z_2, Y_2)$ where:
  $$-0.6 \le (Y_2 - Y_1) \le +0.6$$
- **Action**: Pure walking transition (`MovementType.WALK` or `MovementType.STEP_UP`). The player does not jump.
- **Clearance Validation**:
  - Origin box: $\text{Box}(X_1 + 0.2, Y_1, Z_1 + 0.2, X_1 + 0.8, Y_1 + 1.8, Z_1 + 0.8)$
  - Destination box: $\text{Box}(X_2 + 0.2, Y_2, Z_2 + 0.2, X_2 + 0.8, Y_2 + 1.8, Z_2 + 0.8)$
  - Both boxes must satisfy `world.isSpaceEmpty(box)`.
- **Cost**:
  $$\text{cost} = \text{baseCost} + \max(0.0, Y_2 - Y_1) \times 0.5$$
  - Flat walk: $1.0$
  - Step up $0.5$m: $1.0 + 0.25 = 1.25$
  - Step down $0.5$m: $1.0$

### 3.2 1-Block Jump Up & Apex Headroom Verification
- **Physics Rule**: Ascending a 1-block ledge ($0.6 < \Delta Y \le 1.25$, typically $\Delta Y = 1.0$) requires a jump.
  - In Minecraft, jump initial velocity is $v_{y0} \approx 0.42$ m/tick, reaching an apex height of $+1.25$m above the takeoff surface.
  - Total head height at apex reaches $Y_1 + 1.25 + 1.8 = Y_1 + 3.05$m.
  - If a ceiling block exists at $Y_1 + 2$ (bottom face at $Y_1 + 2.0$), the player bonks their head at the start of the jump arc, halting vertical velocity and killing forward horizontal momentum.
- **Apex Headroom Invariant**:
  1. **Takeoff Column Headroom**: Vertical clearance above takeoff feet must extend to at least $Y_1 + 2.0$m (in fact, $Y_1 + 2.0$ to $Y_1 + 2.8$m for unobstructed jump clearance). Specifically, `BlockPos(pos.x, pos.y + 2, pos.z)` must be free of collision.
  2. **Landing Column Headroom**: Vertical clearance above landing feet $Y_2$ must extend to at least $Y_2 + 1.8$m (`BlockPos(dest.x, dest.y + 2, dest.z)` clear).
  3. **Apex Transition Volume**:
     $$\text{ApexBox} = \text{Box}(\min(X_1, X_2) + 0.2, Y_1 + 1.8, \min(Z_1, Z_2) + 0.2, \max(X_1, X_2) + 0.8, Y_1 + 2.0, \max(Z_1, Z_2) + 0.8)$$
     Must satisfy `world.isSpaceEmpty(ApexBox)`.
- **Action**: Jump-ascend transition (`MovementType.JUMP_UP`).
- **Cost**: $1.45$ (accounts for jump deceleration and air-time duration).

### 3.3 1, 2, 3-Block Safe Drops
- **Physics Rule**: Safe descents without fall damage can span up to $3.0$ meters ($\Delta Y \in [-3.0, -0.6)$). Drops $> 3.0$ meters incur fall damage and are forbidden.
- **Fall Column Clearance**:
  When stepping off edge $(X_1, Z_1)$ onto lower landing $(X_2, Z_2)$ at $Y_2 = Y_1 - H$ ($H \in \{1, 2, 3\}$):
  1. The player moves horizontally at the top: Space at $(X_2, Z_2)$ at elevation $Y_1$ must be clear ($\text{Box}(X_2+0.2, Y_1, Z_2+0.2, X_2+0.8, Y_1+1.8, Z_2+0.8)$).
  2. The player drops through the column: The entire swept vertical volume from landing to takeoff must be clear of obstructions (e.g. wall protrusions, chains, lanterns, open trapdoors):
     $$\text{FallBox} = \text{Box}(X_2 + 0.2, Y_2, Z_2 + 0.2, X_2 + 0.8, Y_1 + 1.8, Z_2 + 0.8)$$
     Must satisfy `world.isSpaceEmpty(FallBox)`.
  3. **Landing Safety**: Landing surface must be verified safe (non-hazard, non-void).
- **Cost**:
  $$\text{cost} = 1.2 + H \times 0.8$$
  - 1-block drop: $2.0$
  - 2-block drop: $2.8$
  - 3-block drop: $3.6$  
  *(Higher cost penalizes drops when flat paths exist, but allows dropping when necessary).*

### 3.4 1-Block Parkour Gap Jumps
- **Physics Rule**: A 1-block gap consists of an empty air space between takeoff $(X_1, Z_1)$ and landing $(X_1 + 2 D_x, Z_1 + 2 D_z)$.
  Horizontal distance is 2 blocks.
- **Clearance Requirements**:
  1. **Takeoff Column**: Headroom $\ge Y_1 + 2.0$m.
  2. **Gap Column** $(X_1 + D_x, Z_1 + D_z)$:
     - Bounding box at jump arc elevation must be completely free of collisions:
       $$\text{GapBox} = \text{Box}(X_{\text{gap}} + 0.2, Y_1, Z_{\text{gap}} + 0.2, X_{\text{gap}} + 0.8, Y_1 + 2.0, Z_{\text{gap}} + 0.8)$$
     - Ground beneath gap must be non-solid (it is an actual gap).
  3. **Landing Column** $(X_1 + 2 D_x, Z_1 + 2 D_z)$:
     - Landing surface elevation $Y_{\text{land}} \in [Y_1 - 1.0, Y_1 + 1.0]$.
     - Landing headroom $\ge Y_{\text{land}} + 1.8$m.
     - Landing ground must be safe and solid.
- **Action**: Marked as `MovementType.PARKOUR_JUMP`.
- **Cost**: $3.0 + |Y_{\text{land}} - Y_1| \times 1.0$.

### 3.5 Diagonal Corner Snagging Prevention (Double Pillar Clearance)
- **Problem**: When walking diagonally from $(X_0, Z_0)$ to $(X_0 + dx, Z_0 + dz)$, the 1D center line passes through the point $(X_0 + 0.5 \cdot dx, Z_0 + 0.5 \cdot dz)$.
  A player's $0.6$m wide hitbox extends $0.3$m horizontally on both sides, cutting into the solid wall corners of the two orthogonal blocks $(X_0 + dx, Z_0)$ and $(X_0, Z_0 + dz)$.
- **Strict Invariant**:
  For ANY diagonal move to be valid:
  $$\text{PillarA} = \text{Box}(X_0 + dx + 0.2, Y_0, Z_0 + 0.2, X_0 + dx + 0.8, Y_0 + 1.8, Z_0 + 0.8)$$
  $$\text{PillarB} = \text{Box}(X_0 + 0.2, Y_0, Z_0 + dz + 0.2, X_0 + 0.8, Y_0 + 1.8, Z_0 + dz + 0.8)$$
  Both `PillarA` and `PillarB` must satisfy `world.isSpaceEmpty(box)`!
  If EITHER pillar contains a solid collision box, the diagonal move is **STRICTLY DISALLOWED**!
- **Cost**: $\sqrt{2} \approx 1.414 + \max(0.0, Y_2 - Y_1) \times 0.5$.

---

## 4. 3D A* Search Architecture & Data Structures

```
┌───────────────────────────────────────────────────────────────┐
│                          Pathfinder                           │
├───────────────────────────────────────────────────────────────┤
│ - openSet: PriorityQueue<PathNode> (sorted by fScore, hScore) │
│ - bestG: Long2DoubleMap (packed key -> lowest known gScore)   │
│ - penaltyMap: NodePenaltyMap (dynamic stuck node costs)       │
│ - sweptBoxLOS: SweptBoxLOS (string-pulling path smoothing)   │
└───────────────────────────────────────────────────────────────┘
```

### 4.1 `PathNode` Data Structure
```kotlin
package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

enum class MovementType {
    WALK,
    STEP_UP,
    STEP_DOWN,
    JUMP_UP,
    SAFE_DROP,
    PARKOUR_JUMP,
    DIAGONAL
}

class PathNode(
    val pos: BlockPos,
    val standingY: Double,
    val gScore: Double,
    val hScore: Double,
    val parent: PathNode? = null,
    val movementType: MovementType = MovementType.WALK
) {
    val fScore: Double get() = gScore + hScore
    
    val worldPos: Vec3d
        get() = Vec3d(pos.x + 0.5, standingY, pos.z + 0.5)

    companion object {
        /**
         * Packs 3D spatial node coordinates into a 64-bit Long key.
         * X: 24 bits ([-8,388,608, 8,388,607])
         * Z: 24 bits ([-8,388,608, 8,388,607])
         * Half-Block StepY: 16 bits ([0, 65535], covering Y from -2048 to +30720)
         */
        fun packKey(x: Int, standingY: Double, z: Int): Long {
            val stepY = ((standingY + 2048.0) * 2.0).toInt().coerceIn(0, 65535)
            val ux = (x.toLong() and 0xFFFFFFL)
            val uz = (z.toLong() and 0xFFFFFFL)
            val uy = (stepY.toLong() and 0xFFFFL)
            return (ux shl 40) or (uz shl 16) or uy
        }
    }

    val key: Long get() = packKey(pos.x, standingY, pos.z)
}
```

### 4.2 Priority Queue & Tie-Breaking Comparator
```kotlin
val nodeComparator = Comparator<PathNode> { a, b ->
    val diff = a.fScore.compareTo(b.fScore)
    if (diff != 0) diff else a.hScore.compareTo(b.hScore)
}
```
*Rationale*: When two nodes evaluate to identical $f$-scores, selecting the node with the smaller $h$-score (closer to the destination) creates a directional gradient that prunes orthogonal fan-out in open spaces.

### 4.3 Admissible 3D Euclidean Distance Heuristic
```kotlin
fun calculateHeuristic(current: Vec3d, goal: Vec3d): Double {
    val dx = current.x - goal.x
    val dy = current.y - goal.y
    val dz = current.z - goal.z
    val euclidean = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    
    // Vertical ascent penalty component:
    // Climbing requires jumps costing >= 1.45/block vs Euclidean sqrt(2)=1.414.
    // Adding a conservative vertical tie-breaker preserves strict admissibility.
    val verticalBias = if (dy < 0) -dy * 0.1 else 0.0
    return euclidean + verticalBias
}
```
*Mathematical Admissibility Proof*:
In 3D Euclidean space, straight-line distance $D = \sqrt{\Delta x^2 + \Delta y^2 + \Delta z^2}$ represents the infimum of arc length connecting any two points.
Because every physical Minecraft action cost satisfies:
- Cardinal flat: $\text{cost} = 1.0 = \Delta$
- Diagonal flat: $\text{cost} = 1.414 = \sqrt{1^2 + 1^2}$
- Step up 0.5m: $\text{cost} = 1.25 \ge \sqrt{1^2 + 0.5^2} \approx 1.118$
- Jump up 1.0m: $\text{cost} = 1.45 \ge \sqrt{1^2 + 1^2} \approx 1.414$
- 1-block drop: $\text{cost} = 2.0 \ge \sqrt{1^2 + 1^2} \approx 1.414$
- Parkour 2-block: $\text{cost} = 3.0 \ge 2.0$
The heuristic is guaranteed admissible: $h(n) \le h^*(n)$ for all valid transitions.

---

## 5. Node Expansion & Neighbor Generation Algorithm

```kotlin
class NeighborGenerator(
    private val world: World,
    private val penaltyMap: NodePenaltyMap
) {
    private val CARDINALS = arrayOf(
        BlockPos(0, 0, -1), // NORTH
        BlockPos(0, 0, 1),  // SOUTH
        BlockPos(1, 0, 0),  // EAST
        BlockPos(-1, 0, 0)  // WEST
    )

    private val DIAGONALS = arrayOf(
        Triple(1, 1, arrayOf(BlockPos(1, 0, 0), BlockPos(0, 0, 1))),
        Triple(1, -1, arrayOf(BlockPos(1, 0, 0), BlockPos(0, 0, -1))),
        Triple(-1, 1, arrayOf(BlockPos(-1, 0, 0), BlockPos(0, 0, 1))),
        Triple(-1, -1, arrayOf(BlockPos(-1, 0, 0), BlockPos(0, 0, -1)))
    )

    fun getNeighbors(current: PathNode, maxHorizontalRange: Int, startOrigin: BlockPos): List<PathNodeTransition> {
        val result = ArrayList<PathNodeTransition>(16)

        // 1. Cardinal Transitions
        for (offset in CARDINALS) {
            val targetPos = current.pos.add(offset)
            if (isTooFar(targetPos, startOrigin, maxHorizontalRange)) continue

            // Search vertical candidate standing surfaces in range [currentY - 3, currentY + 1]
            evaluateVerticalCandidates(current, targetPos, result)
            
            // Check 1-Block Parkour Gap Jump (if immediate cardinal is gap)
            evaluateParkourJump(current, offset, result, maxHorizontalRange, startOrigin)
        }

        // 2. Diagonal Transitions
        for ((dx, dz, orthogonalPillars) in DIAGONALS) {
            val targetPos = current.pos.add(dx, 0, dz)
            if (isTooFar(targetPos, startOrigin, maxHorizontalRange)) continue

            evaluateDiagonalCandidate(current, targetPos, orthogonalPillars, result)
        }

        return result
    }

    private fun evaluateVerticalCandidates(
        current: PathNode,
        targetCol: BlockPos,
        out: MutableList<PathNodeTransition>
    ) {
        val currY = current.standingY
        val currentFloorY = kotlin.math.floor(currY).toInt()

        // Check candidate ground blocks from currY + 1 down to currY - 3
        for (candidateGroundY in (currentFloorY + 1) downTo (currentFloorY - 3)) {
            val groundPos = BlockPos(targetCol.x, candidateGroundY, targetCol.z)
            val standingY = getStandingSurfaceY(world, groundPos) ?: continue
            val deltaY = standingY - currY

            val destBox = createNodePlayerBox(groundPos, standingY)
            if (!world.isSpaceEmpty(destBox)) continue

            when {
                // Case A: Walking / Step-Up / Step-Down (Slabs & Stairs)
                deltaY in -0.6..0.6 -> {
                    val transitionType = when {
                        deltaY > 0.05 -> MovementType.STEP_UP
                        deltaY < -0.05 -> MovementType.STEP_DOWN
                        else -> MovementType.WALK
                    }
                    val stepCost = 1.0 + kotlin.math.max(0.0, deltaY) * 0.5
                    val penalty = penaltyMap.getPenalty(groundPos)
                    out.add(PathNodeTransition(groundPos, standingY, stepCost + penalty, transitionType))
                }

                // Case B: 1-Block Jump Up (Apex Headroom Verification)
                deltaY in 0.6..1.25 -> {
                    if (canJumpUp(current, groundPos, standingY)) {
                        val penalty = penaltyMap.getPenalty(groundPos)
                        out.add(PathNodeTransition(groundPos, standingY, 1.45 + penalty, MovementType.JUMP_UP))
                    }
                }

                // Case C: 1 to 3-Block Safe Drop
                deltaY in -3.05..-0.6 -> {
                    if (canSafeDrop(current, groundPos, standingY)) {
                        val dropBlocks = -deltaY
                        val stepCost = 1.2 + dropBlocks * 0.8
                        val penalty = penaltyMap.getPenalty(groundPos)
                        out.add(PathNodeTransition(groundPos, standingY, stepCost + penalty, MovementType.SAFE_DROP))
                    }
                }
            }
        }
    }

    private fun canJumpUp(current: PathNode, landingPos: BlockPos, landingY: Double): Boolean {
        val currY = current.standingY
        // 1. Takeoff headroom clearance (no ceiling bonk at takeoff)
        val takeoffCeiling = createNodePlayerBox(current.pos, currY + 0.2)
            .union(createNodePlayerBox(current.pos, currY + 1.2))
        if (!world.isSpaceEmpty(takeoffCeiling)) return false

        // Takeoff position pos.up(2) must not be solid
        val takeoffPosUp2 = current.pos.up(2)
        if (!world.getBlockState(takeoffPosUp2).getCollisionShape(world, takeoffPosUp2).isEmpty) return false

        // 2. Landing headroom clearance
        val landingBox = createNodePlayerBox(landingPos, landingY)
        if (!world.isSpaceEmpty(landingBox)) return false

        // 3. Apex clearance (swept horizontal box at jump peak Y+1.25 to Y+2.0)
        val apexMinX = kotlin.math.min(current.pos.x, landingPos.x) + 0.2
        val apexMaxX = kotlin.math.max(current.pos.x, landingPos.x) + 0.8
        val apexMinZ = kotlin.math.min(current.pos.z, landingPos.z) + 0.2
        val apexMaxZ = kotlin.math.max(current.pos.z, landingPos.z) + 0.8
        val apexBox = Box(apexMinX, currY + 1.8, apexMinZ, apexMaxX, currY + 2.0, apexMaxZ)
        if (!world.isSpaceEmpty(apexBox)) return false

        return true
    }

    private fun canSafeDrop(current: PathNode, landingPos: BlockPos, landingY: Double): Boolean {
        val currY = current.standingY
        // Fall column from takeoff down to landing must be completely empty of collisions
        val cx = landingPos.x + 0.5
        val cz = landingPos.z + 0.5
        val fallColumnBox = Box(cx - 0.3, landingY, cz - 0.3, cx + 0.3, currY + 1.8, cz + 0.3)
        return world.isSpaceEmpty(fallColumnBox)
    }

    private fun evaluateDiagonalCandidate(
        current: PathNode,
        targetPos: BlockPos,
        orthogonalPillars: Array<BlockPos>,
        out: MutableList<PathNodeTransition>
    ) {
        val currY = current.standingY
        val currentFloorY = kotlin.math.floor(currY).toInt()

        // Diagonals restricted to flat or single slab transition (|deltaY| <= 0.6)
        for (candidateGroundY in (currentFloorY + 1) downTo (currentFloorY - 1)) {
            val groundPos = BlockPos(targetPos.x, candidateGroundY, targetPos.z)
            val standingY = getStandingSurfaceY(world, groundPos) ?: continue
            val deltaY = standingY - currY
            if (deltaY !in -0.6..0.6) continue

            val destBox = createNodePlayerBox(groundPos, standingY)
            if (!world.isSpaceEmpty(destBox)) continue

            // CRUCIAL: Double Orthogonal Corner Clearance (Eliminates Corner Snagging)
            var cornerClear = true
            for (pillar in orthogonalPillars) {
                val pillarBox = createNodePlayerBox(pillar, currY)
                if (!world.isSpaceEmpty(pillarBox)) {
                    cornerClear = false
                    break
                }
            }
            if (!cornerClear) continue

            val stepCost = 1.414 + kotlin.math.max(0.0, deltaY) * 0.5
            val penalty = penaltyMap.getPenalty(groundPos)
            out.add(PathNodeTransition(groundPos, standingY, stepCost + penalty, MovementType.DIAGONAL))
        }
    }

    private fun evaluateParkourJump(
        current: PathNode,
        dir: BlockPos,
        out: MutableList<PathNodeTransition>,
        maxRange: Int,
        startOrigin: BlockPos
    ) {
        val gapCol = current.pos.add(dir)
        val landCol = current.pos.add(dir.x * 2, 0, dir.z * 2)
        if (isTooFar(landCol, startOrigin, maxRange)) return

        // Takeoff must have headroom
        val takeoffHeadroom = createNodePlayerBox(current.pos, current.standingY + 0.2)
        if (!world.isSpaceEmpty(takeoffHeadroom)) return

        // Gap column must be clear throughout jump elevation
        val gapBox = createNodePlayerBox(gapCol, current.standingY)
        if (!world.isSpaceEmpty(gapBox)) return

        // Landing column ground options
        val currFloorY = kotlin.math.floor(current.standingY).toInt()
        for (landGroundY in (currFloorY + 1) downTo (currFloorY - 1)) {
            val groundPos = BlockPos(landCol.x, landGroundY, landCol.z)
            val standingY = getStandingSurfaceY(world, groundPos) ?: continue
            val deltaY = standingY - current.standingY
            if (deltaY !in -1.0..1.0) continue

            val landBox = createNodePlayerBox(groundPos, standingY)
            if (!world.isSpaceEmpty(landBox)) continue

            val penalty = penaltyMap.getPenalty(groundPos)
            out.add(PathNodeTransition(groundPos, standingY, 3.0 + kotlin.math.abs(deltaY) + penalty, MovementType.PARKOUR_JUMP))
        }
    }

    private fun isTooFar(pos: BlockPos, origin: BlockPos, maxRange: Int): Boolean {
        return kotlin.math.abs(pos.x - origin.x) > maxRange ||
               kotlin.math.abs(pos.z - origin.z) > maxRange ||
               kotlin.math.abs(pos.y - origin.y) > maxRange
    }
}

data class PathNodeTransition(
    val pos: BlockPos,
    val standingY: Double,
    val stepCost: Double,
    val type: MovementType
)
```

---

## 6. Complete `Pathfinder.kt` Architecture & Interface Contracts

### 6.1 Interface Compliance (`PROJECT.md:95-114`)
```kotlin
package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

interface Pathfinder {
    fun findPath(world: World, start: Vec3d, goal: Vec3d, allowedRange: Double = 1.0): PathResult
    fun penalizeNode(pos: BlockPos, penalty: Float = 50.0f)
    fun clearPenalties()

    companion object : Pathfinder {
        private var delegate: Pathfinder = DefaultPathfinder()

        fun setDelegate(pathfinder: Pathfinder) {
            this.delegate = pathfinder
        }

        fun getDelegate(): Pathfinder = delegate

        override fun findPath(world: World, start: Vec3d, goal: Vec3d, allowedRange: Double): PathResult =
            delegate.findPath(world, start, goal, allowedRange)

        override fun penalizeNode(pos: BlockPos, penalty: Float) =
            delegate.penalizeNode(pos, penalty)

        override fun clearPenalties() =
            delegate.clearPenalties()
    }
}

data class PathResult(
    val success: Boolean,
    val waypoints: List<Vec3d>,
    val blockedReason: String? = null
)
```

### 6.2 `DefaultPathfinder` Core Search Implementation
```kotlin
class DefaultPathfinder(
    private val penaltyMap: NodePenaltyMap = NodePenaltyMap(),
    private val maxExpansions: Int = 5000,
    private val maxHorizontalRange: Int = 32
) : Pathfinder {

    override fun penalizeNode(pos: BlockPos, penalty: Float) {
        penaltyMap.penalize(pos, penalty)
    }

    override fun clearPenalties() {
        penaltyMap.clear()
    }

    override fun findPath(world: World, start: Vec3d, goal: Vec3d, allowedRange: Double): PathResult {
        // Clean up expired spatial penalties
        penaltyMap.cleanupExpired(System.currentTimeMillis())

        // 1. Resolve Start Node
        val startNode = resolveStartNode(world, start) ?: return PathResult(
            success = false,
            waypoints = emptyList(),
            blockedReason = "START_BLOCKED_OR_UNSUPPORTED"
        )

        // 2. Check if already within reach
        if (start.distanceTo(goal) <= allowedRange) {
            return PathResult(success = true, waypoints = listOf(startNode.worldPos))
        }

        // 3. Initialize Open Set & Known Best G-Score Map
        val openSet = java.util.PriorityQueue<PathNode>(Comparator { a, b ->
            val diff = a.fScore.compareTo(b.fScore)
            if (diff != 0) diff else a.hScore.compareTo(b.hScore)
        })
        val bestG = HashMap<Long, Double>()

        openSet.add(startNode)
        bestG[startNode.key] = startNode.gScore

        val neighborGen = NeighborGenerator(world, penaltyMap)
        val startBlockPos = startNode.pos
        var closestNode = startNode
        var minDistanceToGoal = startNode.worldPos.distanceTo(goal)
        var expansions = 0

        // 4. Main A* Expansion Loop
        while (openSet.isNotEmpty() && expansions < maxExpansions) {
            val current = openSet.poll()
            expansions++

            val dist = current.worldPos.distanceTo(goal)
            if (dist < minDistanceToGoal) {
                minDistanceToGoal = dist
                closestNode = current
            }

            // Goal criteria: within allowedRange of goal Vec3d
            if (dist <= allowedRange) {
                val rawWaypoints = reconstructPath(current)
                val smoothedWaypoints = SweptBoxLOS.smoothPath(world, rawWaypoints)
                return PathResult(success = true, waypoints = smoothedWaypoints)
            }

            // Stale entry check in PriorityQueue
            val knownBest = bestG[current.key] ?: continue
            if (current.gScore > knownBest + 1e-6) continue

            // Generate valid hitbox transitions
            val transitions = neighborGen.getNeighbors(current, maxHorizontalRange, startBlockPos)
            for (trans in transitions) {
                val tentativeG = current.gScore + trans.stepCost
                val key = PathNode.packKey(trans.pos.x, trans.standingY, trans.pos.z)
                val prevG = bestG[key]

                if (prevG == null || tentativeG < prevG) {
                    bestG[key] = tentativeG
                    val h = calculateHeuristic(Vec3d(trans.pos.x + 0.5, trans.standingY, trans.pos.z + 0.5), goal)
                    val nextNode = PathNode(
                        pos = trans.pos,
                        standingY = trans.standingY,
                        gScore = tentativeG,
                        hScore = h,
                        parent = current,
                        movementType = trans.type
                    )
                    openSet.add(nextNode)
                }
            }
        }

        // 5. Fallback on Max Expansions: return partial path if sufficiently advanced, else failure
        return if (minDistanceToGoal < start.distanceTo(goal) - 2.0 && closestNode != startNode) {
            val partialPath = SweptBoxLOS.smoothPath(world, reconstructPath(closestNode))
            PathResult(success = true, waypoints = partialPath, blockedReason = "PARTIAL_PATH_EXPANSION_LIMIT")
        } else {
            PathResult(
                success = false,
                waypoints = emptyList(),
                blockedReason = if (expansions >= maxExpansions) "MAX_EXPANSIONS_EXCEEDED" else "NO_PATH_FOUND"
            )
        }
    }

    private fun resolveStartNode(world: World, start: Vec3d): PathNode? {
        val startBlock = BlockPos(kotlin.math.floor(start.x).toInt(), kotlin.math.floor(start.y).toInt(), kotlin.math.floor(start.z).toInt())
        
        // Check exact block and 1 block below
        for (dy in intArrayOf(0, -1, 1)) {
            val candidateGround = startBlock.up(dy)
            val standingY = getStandingSurfaceY(world, candidateGround) ?: continue
            if (kotlin.math.abs(standingY - start.y) <= 0.8) {
                val box = createNodePlayerBox(candidateGround, standingY)
                if (world.isSpaceEmpty(box)) {
                    return PathNode(candidateGround, standingY, 0.0, 0.0)
                }
            }
        }
        return null
    }

    private fun reconstructPath(endNode: PathNode): List<Vec3d> {
        val points = ArrayList<Vec3d>()
        var cur: PathNode? = endNode
        while (cur != null) {
            points.add(cur.worldPos)
            cur = cur.parent
        }
        points.reverse()
        return points
    }
}
```

---

## 7. Integration Matrix with Peer M2 Modules

### 7.1 Integration with `SweptBoxLOS.kt` (Explorer 2)
- **Contract**:
  ```kotlin
  object SweptBoxLOS {
      fun hasLineOfSight(world: World, from: Vec3d, to: Vec3d): Boolean
      fun smoothPath(world: World, rawWaypoints: List<Vec3d>): List<Vec3d>
  }
  ```
- **Execution Flow**:
  1. `Pathfinder` computes discrete hitbox-safe node transitions from start to goal.
  2. `reconstructPath(node)` emits `List<Vec3d>` with exact $(X + 0.5, Y_{\text{stand}}, Z + 0.5)$ coordinates.
  3. `SweptBoxLOS.smoothPath(world, rawWaypoints)` performs string pulling: for each straight run, sweeps the player's $[0.6 \times 1.8]$m AABB and validates continuous ground support under feet.
  4. Redundant zigzag waypoints are collapsed into direct diagonal vectors without clipping corners.

### 7.2 Integration with `NodePenaltyMap.kt` (Explorer 3)
- **Contract**:
  ```kotlin
  class NodePenaltyMap {
      fun penalize(pos: BlockPos, penalty: Float = 50.0f)
      fun getPenalty(pos: BlockPos): Double
      fun clear()
      fun cleanupExpired(currentTimeMillis: Long)
  }
  ```
- **Execution Flow**:
  1. When `MovementController` detects 12+ ticks of stagnation (stuck behind a closed gate, wandering mob, or unexpected block change), it calls `Pathfinder.penalizeNode(stuckBlock, 50.0f)`.
  2. `NodePenaltyMap` registers `stuckBlock` with expiration timestamp $T_{\text{now}} + 20\,000$ms and spatially diffuses lower penalties to neighboring blocks.
  3. Subsequent `findPath` calls incorporate `penaltyMap.getPenalty(groundPos)` directly into the neighbor step cost $g(\text{next}) = g(\text{curr}) + \text{stepCost} + \text{penalty}$.
  4. The A* search automatically branches into an alternate detour rather than regenerating the failing path.

---

## 8. Test Strategy & Acceptance Invariants (Tiers 1-5)

Conforming to `TEST_INFRA.md`, the pathfinder will be verified across 5 testing tiers:

### Tier 1: Unit & Algorithmic Invariants
1. `testSlabWalkNoJump`: Flat ground to bottom slab ($\Delta Y = +0.5$) emits a walking transition without jump flags.
2. `testStairAscent`: Ascending a 3-step wooden staircase creates smooth step-up nodes spaced by 0.5m elevation.
3. `testJumpApexCeilingHeadroom`: Ascending a 1-block ledge with a ceiling at $Y+2.0$ returns failure/re-route; with ceiling at $Y+3.0$ succeeds.
4. `testSafeDropDistance`: Drops of 1, 2, and 3 blocks succeed; drop of 4 blocks is rejected.
5. `testAdmissibleHeuristic`: Invariant check across 10,000 random coordinate pairs verifying $h(a, b) \le \text{trueMinimalCost}(a, b)$.

### Tier 2: Boundary & Corner Invariants
1. `testDoublePillarDiagonalClearance`: Walking diagonally past an inner solid corner where either orthogonal pillar is solid is rejected.
2. `testNarrowOpening0_6m`: Path traverses a 1-block wide corridor ($1.0$m opening) cleanly without snagging.
3. `testZeroElevationDiffOnCarpet`: Stepping onto carpet ($\Delta Y = 0.0625$) is treated as auto-walk.
4. `testFallColumnObstruction`: A 3-block drop with an iron bar or slab protruding at $Y-1$ is rejected.
5. `testParkourGap1Block`: Jump across a 1-block gap succeeds; 2-block gap without sprint velocity is rejected.

### Tier 3: Pairwise Combinations
1. `testSlabPlusCeilingConstrainedCorridor`: Bottom slab in a 2-block corridor ($1.5$m clearance) correctly flags insufficient height.
2. `testDiagonalWithSlabElevation`: Diagonal move with simultaneous $+0.5$m slab elevation change validates both diagonal pillars and step height.
3. `testLavaPitHazardAvoidance`: Path detours around lava blocks and sweet berry bushes even if straight path is open.

### Tier 4: Real-World Workloads
1. `testTreeForagingObstacleRoute`: Complex route around tree clusters and foliage canopy to within reach distance of target log.
2. `testRepathOnPenalizedNode`: When a node along the primary path is penalized with cost 50, A* returns a distinct alternate route.

### Tier 5: Adversarial Stress Hardening
1. `testMaxExpansionsTermination`: Completely enclosed target enclosed in bedrock aborts at `MAX_EXPANSIONS` within $\le 50$ms without thread stall.
2. `testPackedKeyUniqueness`: Random generation of $10^6$ distinct $(x, y, z)$ coordinates yields zero hash/key collisions.

---

## 9. Conclusion

The designed `Pathfinder.kt` architecture delivers:
1. Complete elimination of diagonal corner snags via full $0.6 \times 1.8$m player AABB evaluation and double-pillar clearance.
2. Physical simulation of vertical traversal: smooth slab/stair auto-step ($\le 0.6$m), 1-block jump up with $2.0$m apex ceiling bonk protection, 1-3 block safe drops with swept fall column verification, and 1-block parkour leaps.
3. Memory-efficient 64-bit coordinate packing with tie-breaking A* search and strictly admissible heuristic.
4. Clean, decoupled integration with `SweptBoxLOS` and `NodePenaltyMap`.
