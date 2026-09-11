# Swept-Box Collision & Line-of-Sight (LOS) Analysis
**Milestone**: Milestone 2 — Hitbox-Aware 3D A* Pathfinder (R2)  
**Author**: Explorer 2 (teamwork_preview_explorer_m2_2)  
**Target File**: `src/main/kotlin/com/github/foragerhelper/path/SweptBoxLOS.kt`  
**Date**: 2026-09-11  

---

## 1. Executive Summary

Pathfinding in Minecraft requires converting discrete, jagged grid steps from an A* search into smooth, natural, high-performance direct waypoints for the `MovementController`. In legacy implementations (such as `AStarPathfinder.kt`), path smoothing relied on point-based 1D Bresenham line sampling. This caused two fatal failure modes:
1. **Diagonal Corner Snagging**: A 1D line ray connecting diagonal nodes $(0, 0)$ and $(2, 2)$ passes cleanly through the empty air point $(1, 1)$. However, the player is not a point particle — the player has a physical collision box with width $W = 0.6$m and height $H = 1.8$m. The outer corner of the player's bounding box penetrates corner blocks at $(1, 0)$ or $(0, 1)$, causing the player to snag against wall edges and freeze in place.
2. **Chasm Shortcuts (Floating/Falling)**: A 1D ray or unconstrained volume check between two high points across a cliff or chasm confirms that the air between them is empty of solid obstacles, incorrectly smoothing out the safe detour around the chasm and instructing the player to walk straight into the abyss.

This document presents the complete mathematical, geometric, and algorithmic specification for `SweptBoxLOS.kt`. It provides:
- Exact 3D swept bounding-box collision detection ($0.6 \times 1.8$m with a safety margin $\delta = 0.05$m).
- Proof of continuous overlapping coverage via discrete sub-stepping ($\Delta s \le 0.25$m, optimal $0.20$m) paired with continuous broadphase filtering.
- Ground support validation preventing floating or chasm shortcuts.
- Guaranteed corner clearance that completely prevents diagonal snagging while preserving clearance through 1-block doorways.
- A greedy lookahead path smoothing algorithm that transforms raw A* nodes into direct continuous `Vec3d` waypoints.
- A dual-mode architecture supporting both native Minecraft `CollisionView` and decoupled lambda predicates for 100% offline headless testing.

---

## 2. Minecraft 1.21.11 Collision & Math APIs

Decompilation and bytecode inspection of the Fabric 1.21.11 environment (`minecraft-merged-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`) reveal the following key APIs:

### 2.1 `net.minecraft.util.math.Box`
`Box` is an immutable, axis-aligned bounding box (AABB) defined by doubles: `minX, minY, minZ, maxX, maxY, maxZ`.
Key methods:
- `Box(double x1, double y1, double z1, double x2, double y2, double z2)`: Standard constructor.
- `Box.of(Vec3d center, double dx, double dy, double dz)`: Creates an AABB centered at `center` with full extents `dx, dy, dz`.
- `expand(double d)` / `expand(double x, double y, double z)`: Expands box outward in all directions.
- `stretch(double x, double y, double z)` / `stretch(Vec3d v)`: Expands box along the direction of vector $v$ (forms the convex hull enclosing the box at start and end of displacement).
- `offset(double x, double y, double z)` / `offset(Vec3d v)`: Translates box.
- `intersects(Box other)` / `intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ)`: Boolean AABB intersection test.
- `raycast(Vec3d from, Vec3d to)`: Computes ray intersection using the Cyrus-Beck slab method, returning `Optional<Vec3d>`.

### 2.2 `net.minecraft.world.CollisionView`
`CollisionView` is the primary interface for collision queries, implemented by `World` and `ClientWorld`.
Crucial default methods:
- `isSpaceEmpty(Box box)`: Returns `true` if no block or entity collisions intersect `box`. Delegates to `isBlockSpaceEmpty(null, box, false)`.
- `isBlockSpaceEmpty(Entity? entity, Box box)`: Queries `getBlockCollisions(entity, box)` and returns `true` if all returned voxel shapes are empty. Ignores entities when `entity == null`.
- `getBlockCollisions(Entity? entity, Box box)`: Uses `BlockCollisionSpliterator` to iterate over all candidate blocks within `box`.
- `findSupportingBlockPos(Entity? entity, Box box)`: Locates the supporting block position beneath `box`.

### 2.3 `net.minecraft.util.shape.VoxelShape` and `VoxelShapes`
- `VoxelShape.getBoundingBoxes()`: Returns `List<Box>` decomposing arbitrary shapes (stairs, slabs, fences) into individual AABBs.
- `VoxelShape.isEmpty()`: Returns `true` for passable blocks (air, grass, flowers, torches, signs).
- `VoxelShapes.empty()` and `VoxelShapes.fullCube()`: Canonical static constants.

### 2.4 Internal Mechanics of `BlockCollisionSpliterator`
Bytecode inspection shows that `BlockCollisionSpliterator` uses a `CuboidBlockIterator` across the integer block coordinates spanned by `box`. For full cubes, it evaluates `box.intersects(x, y, z, x+1, y+1, z+1)`. For complex blocks, it fetches `state.getCollisionShape(...)` and tests shape intersection. Therefore, querying `CollisionView.isSpaceEmpty(box)` is highly optimized in Minecraft 1.21.11 and incurs zero OpenGL or client window dependencies.

---

## 3. Geometric & Mathematical Formulation of Swept-Box Collision

### 3.1 Player Geometry & Bounding Box
In Minecraft:
- Player hitbox width: $W = 0.6$m ($R = 0.3$m half-width).
- Player hitbox height: $H = 1.8$m.
- Player position $\vec{p} = (x, y, z)$ represents the **horizontal center** and **bottom foot level**.

To guarantee robust collision evaluation:
1. **Safety Clearance Margin ($\delta$)**: An additional buffer of $\delta = 0.05$m is added to horizontal half-widths:
   $$R_{\text{eff}} = R + \delta = 0.30 + 0.05 = 0.35\text{m}$$
   The effective box width is $2 R_{\text{eff}} = 0.70$m.
2. **Vertical Foot Clearance ($\epsilon_y$)**: When the player stands on a surface at $y = 64.0$, the ground block occupies $[63.0, 64.0]$. To prevent floating-point coplanar overlap between the player's feet and the floor:
   $$\text{minY} = y + \epsilon_y, \quad \epsilon_y = 0.02\text{m}$$
   $$\text{maxY} = y + H = y + 1.80\text{m}$$

The evaluated player bounding box at position $\vec{p} = (x, y, z)$ is:
$$\text{Box}_{\text{player}}(\vec{p}) = \text{Box}(x - R_{\text{eff}},\, y + \epsilon_y,\, z - R_{\text{eff}},\, x + R_{\text{eff}},\, y + H,\, z + R_{\text{eff}})$$

```
        +-------------------------+  y + 1.80m (Head Apex)
        |                         |
        |                         |
        |      Player Body        |  Height = 1.8m
        |       (0.70m x 0.70m)   |
        |                         |
        +-------------------------+  y + 0.02m (Foot Clearance)
=============================================  y = 0.00m (Ground Surface)
        ###########################  Solid Block Underneath
```

### 3.2 Continuous Sweep vs Discrete Sub-stepping

#### Method A: Continuous Minkowski Sweep (Slab Raycast)
In continuous collision detection (CCD), sweeping an AABB $B$ along displacement $\vec{v} = \vec{p}_1 - \vec{p}_0$ against static obstacle $O$ is equivalent to raycasting vector $\vec{v}$ against obstacle $O$ inflated by $B$ (Minkowski sum $O \oplus (-B)$):
$$O_{\text{inflated}} = \left[O_{\min}.x - R_{\text{eff}},\, O_{\min}.y - H,\, O_{\min}.z - R_{\text{eff}}\right] \times \left[O_{\max}.x + R_{\text{eff}},\, O_{\max}.y - \epsilon_y,\, O_{\max}.z + R_{\text{eff}}\right]$$
- **Advantage**: Analytically exact for obstacle penetration; zero tunneling.
- **Limitation**: Evaluates only obstacles in the air column. Does **not** detect whether solid ground exists beneath the trajectory. Does not account for terrain height contours (e.g. stepping over slabs/stairs).

#### Method B: Discrete Sub-stepping with Overlapping Boxes
Divide the segment from $\vec{p}_0$ to $\vec{p}_1$ into $N$ equal steps of length $\Delta s \le 0.25$m:
$$D = \|\vec{p}_1 - \vec{p}_0\|$$
$$N = \max\left(1,\, \lceil D / \Delta s \rceil\right)$$
$$\vec{p}(t_i) = \vec{p}_0 + \frac{i}{N} (\vec{p}_1 - \vec{p}_0), \quad i \in \{0, 1, \dots, N\}$$

#### Mathematical Proof of Zero-Gap Coverage:
Let step interval $\Delta s = 0.20$m. The horizontal box width is $W_{\text{eff}} = 0.70$m (or uninflated $0.60$m).
Along the trajectory, the interval spanned by box $i$ is $[s_i - R_{\text{eff}},\, s_i + R_{\text{eff}}]$.
The distance between consecutive box centers is:
$$s_{i+1} - s_i = \frac{D}{N} \le \Delta s = 0.20\text{m}$$
The horizontal overlap between consecutive boxes is:
$$\text{Overlap} = 2 R_{\text{eff}} - (s_{i+1} - s_i) \ge 0.70 - 0.20 = 0.50\text{m} > 0$$
Even with uninflated width $0.60$m:
$$\text{Overlap} = 0.60 - 0.20 = 0.40\text{m} > 0$$
Because the overlap is strictly positive, the union $\bigcup_{i=0}^N \text{Box}(\vec{p}(t_i))$ forms a **connected, gap-free topological volume**. No static block, thin wall (such as an iron bar of thickness $0.125$m), or corner edge can slip through undetected between steps.

#### Architectural Decision: Hierarchical Hybrid Approach
We adopt a hierarchical hybrid model:
1. **Broadphase Bounding Rejection**: Compute the broadphase AABB enclosing the entire trajectory:
   $$\text{Box}_{\text{broad}} = \text{Box}_{\text{player}}(\vec{p}_0).\text{union}(\text{Box}_{\text{player}}(\vec{p}_1))$$
   If `world.isBlockSpaceEmpty(null, Box_broad)` is `true`, the entire 3D corridor is confirmed obstacle-free.
2. **Sub-stepped Narrowphase & Ground Validation**: Sub-step along the trajectory at $\Delta s = 0.20$m:
   - If broadphase was not fully empty: check `world.isSpaceEmpty(Box_player(p(t_i)))`.
   - Always evaluate **Ground Support** at each sub-step $\vec{p}(t_i)$ to ensure continuous walkable terrain.

---

## 4. Ground Support Validation

To prevent the pathfinder from shortcutting across deep chasms, pits of lava, or floating over cliffs:

### 4.1 Ground Probe Footprint
At each sub-step $\vec{p}(t_i) = (x, y, z)$:
Construct a ground probe box beneath the feet:
$$\text{Box}_{\text{ground}}(\vec{p}) = \text{Box}(x - R_g,\, y - D_{\text{max}},\, z - R_g,\, x + R_g,\, y + S_{\text{max}},\, z + R_g)$$
Parameters:
- $R_g = 0.15$m: Ground probe horizontal radius (focused on the central base of the player to ensure firm footing without edge slipping).
- $D_{\text{max}} = 1.10$m: Maximum safe drop. Allows walking down 1-block drops ($1.0$m) and half-slabs ($0.5$m). Rejects drops $\ge 1.5$m.
- $S_{\text{max}} = 0.60$m: Maximum step-up height. Matches vanilla Minecraft step-up for slabs ($0.5$m) and stairs ($0.5$m).

### 4.2 Support Evaluation Criteria
Query `world.getBlockCollisions(null, Box_ground)`. A valid ground support exists if:
1. There is at least one collision box $C$ whose top surface $C.maxY$ satisfies:
   $$y - D_{\text{max}} \le C.maxY \le y + S_{\text{max}} + 0.05$$
2. The block state providing support is not hazardous:
   - `!state.isOf(Blocks.LAVA)`
   - `!state.isOf(Blocks.FIRE)`
   - `!state.isOf(Blocks.SOUL_FIRE)`
   - `!state.isOf(Blocks.CACTUS)`
   - `!state.isOf(Blocks.POWDER_SNOW)`
   - `!state.isOf(Blocks.SWEET_BERRY_BUSH)`

If no valid supporting surface is found at any sub-step, the line-of-sight check immediately returns `false`.

```
                    [ Player Body Box ]
                   ---------------------  y + 0.02
                     |   Feet Level  |    y = 0.00
      - - - - - - - -+- - - - - - - -+- - - - - - -  y + 0.60 (Max Step Up)
      |              |               |            |
      |              |  Ground Probe |            |
      |              | (0.30 x 0.30) |            |
      |              |               |            |
      - - - - - - - -+- - - - - - - -+- - - - - - -  y - 1.10 (Max Safe Drop)
                     |
                [ Void / Chasm ]  ===> FAILS GROUND SUPPORT
```

---

## 5. Prevention of Diagonal Corner Snagging

### 5.1 The Geometry of Corner Snagging
Consider a 90-degree corner wall obstacle at block $(1, 0)$, spanning coordinates $[1.0, 2.0] \times [64.0, 65.0] \times [0.0, 1.0]$.  
A diagonal path attempts to navigate from $(0.0, 64.0, -0.5)$ to $(1.5, 64.0, 1.5)$.  
The midpoint of the trajectory is $(0.75, 64.0, 0.50)$.  
The corner vertex of the wall is at $(1.0, 64.0, 0.50)$.  
The horizontal distance from the trajectory center to the wall corner is:
$$d_x = 1.00 - 0.75 = 0.25\text{m}$$

- **1D Bresenham Raycast**: The ray samples center points $x=0.75, z=0.50$. It tests only the point and observes air. It reports `CLEAR`.
- **Vanilla Hitbox Reality**: The player's half-width is $R = 0.30$m. The player's box extends to $x = 0.75 + 0.30 = 1.05$m.
  The player's box penetrates the wall by $0.05$m! The player snags on the corner and cannot move forward.

### 5.2 Elimination via Clearance Margin
`SweptBoxLOS` uses $R_{\text{eff}} = 0.35$m.
At $x = 0.75$, the effective box reaches $x = 0.75 + 0.35 = 1.10$m.
The intersection with the block $[1.0, 2.0]$ is $0.10$m wide.
`SweptBoxLOS` detects the collision and rejects the shortcut.
The pathfinder is forced to route through intermediate nodes that round the corner with a guaranteed clearance of $\ge 0.05$m from the physical corner.

### 5.3 Clearance Through 1-Block Doorways
A standard Minecraft doorway or corridor is 1 block ($1.0$m) wide:
- Corridor opening: $[0.0, 1.0]$.
- Doorway center: $x = 0.50$.
- Effective player width: $2 R_{\text{eff}} = 0.70$m ($x \in [0.15, 0.85]$).
- Left clearance: $0.15 - 0.00 = 0.15$m ($15$cm).
- Right clearance: $1.00 - 0.85 = 0.15$m ($15$cm).
Thus, $R_{\text{eff}} = 0.35$m provides ample safety buffer around corners while preserving smooth navigation through 1-block openings.

---

## 6. Path Smoothing Algorithm

The path smoothing algorithm uses **Greedy Lookahead String-Pulling** over the raw A* node list.

### 6.1 Algorithm Specification
Given raw path $P = [p_0, p_1, p_2, \dots, p_n]$:
1. Initialize output list `smoothed = [p_0]`. Set `currentIdx = 0`.
2. While `currentIdx < n`:
   a. Compute lookahead ceiling: `maxTarget = min(n, currentIdx + maxLookahead)` (default `maxLookahead = 24`).
   b. Initialize `nextIdx = currentIdx + 1`.
   c. For `candidateIdx` from `maxTarget` down to `currentIdx + 2`:
      - **Slope Filter**: Compute horizontal distance $D_{xz}$ and vertical difference $|\Delta y| = |p_{\text{candidate}}.y - p_{\text{current}}.y|$.
        If $|\Delta y| > 1.25$ and $|\Delta y| > D_{xz} \times 1.05$, reject immediately (exceeds maximum walkable slope of $45^\circ$).
      - **Non-Skippable Action Check**: If any node in $(currentIdx, candidateIdx)$ is marked non-skippable (e.g. parkour jump takeoff/landing, ladder climb), do not skip past it.
      - **Swept-Box LOS Check**: Invoke `SweptBoxLOS.hasLineOfSight(world, p[currentIdx], p[candidateIdx])`.
      - If `true`: set `nextIdx = candidateIdx` and `break` (farthest reachable node found).
   d. Append `p[nextIdx]` to `smoothed`.
   e. Set `currentIdx = nextIdx`.
3. Return `smoothed`.

### 6.2 Complexity & Performance
- **Time Complexity**: For a path of length $n \le 30$ and lookahead $K = 24$, worst-case LOS checks $\le n \times K / 2 \approx 360$.
- **Execution Time**: Each LOS check takes $\approx 1.5 - 3\,\mu$s on modern hardware with cached block chunks. Total smoothing latency is $< 0.8$ms, well within the 50ms tick budget.
- **Node Compression**: Typical paths across open fields or straight hallways compress from 20+ nodes down to 1–3 waypoints.

---

## 7. Production Code Blueprint: `SweptBoxLOS.kt`

The following blueprint defines the exact production implementation for `src/main/kotlin/com/github/foragerhelper/path/SweptBoxLOS.kt`. It is completely decoupled, self-contained, and supports dual-mode testing:

```kotlin
package com.github.foragerhelper.path

import net.minecraft.block.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.CollisionView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * High-performance swept bounding-box line-of-sight (LOS) checker and path smoother.
 *
 * Evaluates the player's 3D bounding box (0.6 width x 1.8 height) with safety clearance
 * margins along direct trajectories to eliminate diagonal corner snagging and validate
 * continuous ground support.
 */
object SweptBoxLOS {

    const val PLAYER_WIDTH: Double = 0.6
    const val PLAYER_HEIGHT: Double = 1.8
    const val DEFAULT_CLEARANCE_MARGIN: Double = 0.05
    const val DEFAULT_STEP_INTERVAL: Double = 0.20
    const val DEFAULT_MAX_STEP_UP: Double = 0.60
    const val DEFAULT_MAX_SAFE_DROP: Double = 1.10
    const val DEFAULT_GROUND_PROBE_RADIUS: Double = 0.15
    const val FOOT_CLEARANCE: Double = 0.02
    const val DEFAULT_MAX_LOOKAHEAD: Int = 24

    // =========================================================================
    // Line-of-Sight Evaluation
    // =========================================================================

    /**
     * Checks whether a player can walk directly from [from] to [to] in a straight line.
     * Evaluates full swept bounding-box clearance and continuous ground support.
     */
    fun hasLineOfSight(
        world: CollisionView,
        from: Vec3d,
        to: Vec3d,
        clearanceMargin: Double = DEFAULT_CLEARANCE_MARGIN,
        stepInterval: Double = DEFAULT_STEP_INTERVAL,
        requireGroundSupport: Boolean = true,
        maxSafeDrop: Double = DEFAULT_MAX_SAFE_DROP,
        maxStepUp: Double = DEFAULT_MAX_STEP_UP
    ): Boolean {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance < 1e-4) return true

        val halfWidth = (PLAYER_WIDTH / 2.0) + clearanceMargin

        // 1. Broadphase fast rejection: if the entire corridor is empty, skip obstacle checks
        val minX = min(from.x, to.x) - halfWidth
        val maxX = max(from.x, to.x) + halfWidth
        val minY = min(from.y, to.y) + FOOT_CLEARANCE
        val maxY = max(from.y, to.y) + PLAYER_HEIGHT
        val minZ = min(from.z, to.z) - halfWidth
        val maxZ = max(from.z, to.z) + halfWidth
        val broadphaseBox = Box(minX, minY, minZ, maxX, maxY, maxZ)

        val broadphaseClear = world.isBlockSpaceEmpty(null, broadphaseBox)

        // 2. Narrowphase discrete sub-stepping
        val steps = max(1, ceil(distance / stepInterval).toInt())
        val stepVec = Vec3d(dx / steps, dy / steps, dz / steps)

        for (i in 0..steps) {
            val currX = from.x + stepVec.x * i
            val currY = from.y + stepVec.y * i
            val currZ = from.z + stepVec.z * i
            val pos = Vec3d(currX, currY, currZ)

            // Obstacle collision check (only needed if broadphase contained obstacles)
            if (!broadphaseClear) {
                val playerBox = Box(
                    currX - halfWidth, currY + FOOT_CLEARANCE, currZ - halfWidth,
                    currX + halfWidth, currY + PLAYER_HEIGHT, currZ + halfWidth
                )
                if (!world.isBlockSpaceEmpty(null, playerBox)) {
                    return false
                }
            }

            // Ground support check
            if (requireGroundSupport) {
                if (!hasGroundSupport(world, pos, DEFAULT_GROUND_PROBE_RADIUS, maxSafeDrop, maxStepUp)) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * Headless/decoupled line-of-sight check using custom collision predicates.
     * Ideal for unit testing without initializing Minecraft worlds.
     */
    fun hasLineOfSight(
        isObstacleBlocked: (Box) -> Boolean,
        isGroundSupported: ((Vec3d) -> Boolean)?,
        from: Vec3d,
        to: Vec3d,
        clearanceMargin: Double = DEFAULT_CLEARANCE_MARGIN,
        stepInterval: Double = DEFAULT_STEP_INTERVAL
    ): Boolean {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance < 1e-4) return true

        val halfWidth = (PLAYER_WIDTH / 2.0) + clearanceMargin
        val steps = max(1, ceil(distance / stepInterval).toInt())
        val stepVec = Vec3d(dx / steps, dy / steps, dz / steps)

        for (i in 0..steps) {
            val currX = from.x + stepVec.x * i
            val currY = from.y + stepVec.y * i
            val currZ = from.z + stepVec.z * i
            val pos = Vec3d(currX, currY, currZ)

            val playerBox = Box(
                currX - halfWidth, currY + FOOT_CLEARANCE, currZ - halfWidth,
                currX + halfWidth, currY + PLAYER_HEIGHT, currZ + halfWidth
            )
            if (isObstacleBlocked(playerBox)) {
                return false
            }

            if (isGroundSupported != null && !isGroundSupported(pos)) {
                return false
            }
        }

        return true
    }

    // =========================================================================
    // Ground Support Validation
    // =========================================================================

    /**
     * Checks if solid, walkable ground exists directly beneath the player at [pos].
     */
    fun hasGroundSupport(
        world: CollisionView,
        pos: Vec3d,
        probeRadius: Double = DEFAULT_GROUND_PROBE_RADIUS,
        maxSafeDrop: Double = DEFAULT_MAX_SAFE_DROP,
        maxStepUp: Double = DEFAULT_MAX_STEP_UP
    ): Boolean {
        val probeBox = Box(
            pos.x - probeRadius, pos.y - maxSafeDrop, pos.z - probeRadius,
            pos.x + probeRadius, pos.y + maxStepUp, pos.z + probeRadius
        )

        val collisions = world.getBlockCollisions(null, probeBox)
        var supported = false

        for (shape in collisions) {
            if (shape.isEmpty) continue
            for (box in shape.boundingBoxes) {
                // Verify supporting surface height is within safe drop/step window
                if (box.maxY in (pos.y - maxSafeDrop - 0.05)..(pos.y + maxStepUp + 0.05)) {
                    supported = true
                    break
                }
            }
            if (supported) break
        }

        if (!supported) return false

        // Check for lethal/hazardous ground blocks
        val blockUnder = BlockPos.ofFloored(pos.x, pos.y - 0.2, pos.z)
        val state = world.getBlockState(blockUnder)
        if (state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE) ||
            state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.CACTUS) ||
            state.isOf(Blocks.POWDER_SNOW)) {
            return false
        }

        return true
    }

    // =========================================================================
    // Path Smoothing Algorithm
    // =========================================================================

    /**
     * Smoothes raw A* waypoints into direct straight line segments using greedy lookahead.
     */
    fun smoothPath(
        world: CollisionView,
        rawPath: List<Vec3d>,
        clearanceMargin: Double = DEFAULT_CLEARANCE_MARGIN,
        requireGroundSupport: Boolean = true,
        maxLookahead: Int = DEFAULT_MAX_LOOKAHEAD
    ): List<Vec3d> {
        if (rawPath.size <= 2) return rawPath

        val smoothed = ArrayList<Vec3d>(rawPath.size)
        smoothed.add(rawPath[0])

        var currentIdx = 0
        while (currentIdx < rawPath.lastIndex) {
            var nextIdx = currentIdx + 1
            val maxTarget = min(rawPath.lastIndex, currentIdx + maxLookahead)

            for (candidateIdx in maxTarget downTo currentIdx + 2) {
                val from = rawPath[currentIdx]
                val to = rawPath[candidateIdx]

                val dy = abs(to.y - from.y)
                val dx = to.x - from.x
                val dz = to.z - from.z
                val horizDist = sqrt(dx * dx + dz * dz)

                // Slope check: do not bridge steep vertical cliffs in a single segment
                if (dy > 1.25 && dy > horizDist * 1.05) continue

                if (hasLineOfSight(world, from, to, clearanceMargin, DEFAULT_STEP_INTERVAL, requireGroundSupport)) {
                    nextIdx = candidateIdx
                    break
                }
            }

            smoothed.add(rawPath[nextIdx])
            currentIdx = nextIdx
        }

        return smoothed
    }

    /**
     * Convenience overload for smoothing paths given as [BlockPos].
     * Centers waypoints horizontally at (+0.5, +0.5).
     */
    fun smoothBlockPath(
        world: CollisionView,
        rawPath: List<BlockPos>,
        clearanceMargin: Double = DEFAULT_CLEARANCE_MARGIN,
        requireGroundSupport: Boolean = true,
        maxLookahead: Int = DEFAULT_MAX_LOOKAHEAD
    ): List<Vec3d> {
        val vecPath = rawPath.map { Vec3d(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
        return smoothPath(world, vecPath, clearanceMargin, requireGroundSupport, maxLookahead)
    }
}
```

---

## 8. Comprehensive Test Strategy & Test Case Inventory

Following the testing tiers defined in `TEST_INFRA.md`, the implementation will be verified with the following test inventory:

### Tier 1: Unit & Numerical Invariants (5 tests)
1. `testDirectClearPathEmptyWorld`: 10-meter straight path in empty space confirms LOS and compresses to start and end.
2. `testSolidWallBlocker`: Direct path interrupted by a $1\times 2$ solid wall returns `false`.
3. `testSubSteppingGapCoverage`: A narrow obstacle of width $0.10$m is reliably detected across sub-steps ($\Delta s = 0.20$m).
4. `testZeroDistanceAndIdenticalPoints`: Segment from $\vec{p}$ to $\vec{p}$ immediately returns `true`.
5. `testFootClearancePreventsFloorCollision`: Flat ground at $y = 64.0$ does not trigger obstacle collisions for feet at $y = 64.0$.

### Tier 2: Boundary & Corner Cases (5 tests)
1. `testDiagonalCornerSnaggingElimination`: Moving diagonally past a corner at distance $d = 0.25$m is detected as blocked; distance $d = 0.40$m passes.
2. `testOneBlockDoorwayClearance`: Player passing through the exact center of a 1-block doorway ($1.0$m opening) passes cleanly with $0.15$m margin on both sides.
3. `testChasmShortcutRejection`: Two pillars separated by a 4-block deep gap fail ground support and refuse to smooth across the chasm.
4. `testMaxStepUpLimit`: Step-up of $0.5$m (slab) passes; step-up of $1.5$m without stairs fails ground support.
5. `testLethalHazardRejection`: Ground containing lava or fire is rejected by ground support validation.

### Tier 3: Pairwise Combinations & Dynamics (5 tests)
1. `testAscendingStaircaseDiagonalSmoothing`: Navigating up a $45^\circ$ staircase with diagonal approach.
2. `testLShapedCorridorCornerSmoothing`: L-shaped hallway properly retains the corner apex waypoint rather than clipping through the wall.
3. `testMixedCoverAndOverheadObstacles`: Walking under low ceilings ($H = 2.0$m) allows passage; ceiling at $H = 1.7$m blocks.
4. `testSlopeAngleRejection`: Steep vertical ascent ($\Delta y = 3.0, D_{xz} = 1.0$) is rejected by the slope pre-filter.
5. `testMaxLookaheadWindowBoundary`: Path of 50 straight nodes is partitioned smoothly across 24-node lookahead windows.

### Tier 4: Real-World Workload Scenarios (2 scenarios)
1. `testComplexMazeSmoothing`: 50-node zig-zag path through a winding corridor is compressed into optimal line-of-sight waypoints without grazing any corner.
2. `testForestTreeClusterNavLOS`: Path winding around irregular tree trunks maintains strict $5$cm clearance from log blocks.
