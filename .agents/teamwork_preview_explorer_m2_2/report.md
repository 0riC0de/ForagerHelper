# Architectural Specification: Swept-Box Line-of-Sight Raycast & Path Smoothing Engine

**Document**: `report.md`  
**Subagent**: `teamwork_preview_explorer_m2_2`  
**Role**: Swept-Box Collision & LOS Specialist  
**Milestone**: M2 (Hitbox-Aware Pathfinder & Swept-Box Smoothing)  
**Target File**: `src/main/kotlin/com/github/foragerhelper/path/SweptBoxLOS.kt`  
**Date**: 2026-09-11  

---

## 1. Executive Summary & Problem Boundary

### 1.1 Context & Historical Root Cause
In legacy navigation (`AStarPathfinder.kt:306-338`), path post-processing used a 1D discrete Bresenham raycast algorithm to smooth waypoints:
```kotlin
private fun hasLineOfSight(world: ClientWorld, from: BlockPos, to: BlockPos): Boolean {
    val steps = maxOf(abs(to.x - from.x), abs(to.y - from.y), abs(to.z - from.z))
    for (i in 1..steps) {
        val t = i.toDouble() / steps
        val pos = BlockPos(
            round(from.x + (to.x - from.x) * t).toInt(),
            round(from.y + (to.y - from.y) * t).toInt(),
            round(from.z + (to.z - from.z) * t).toInt(),
        )
        if (!canStandAt(world, pos)) return false
    }
    return true
}
```

This 1D center-point check suffered from three fatal geometric deficiencies:
1. **Dimensionless Raycast & Diagonal Corner Snagging**: The algorithm sampled only integer center points $(x, y, z)$. In Minecraft, a player is not a dimensionless point; the player possesses an Axis-Aligned Bounding Box (AABB) of dimensions **0.6m (width) $\times$ 1.8m (height) $\times$ 0.6m (depth)**. When shortcutting past a 90° inner wall corner from $(0.5, 64, 1.5)$ to $(1.5, 64, 0.5)$ where $(1, 64, 0)$ is solid wall, the center line passes through $(1.0, 64, 1.0)$. Since block $(1, 64, 1)$ is air, `canStandAt` returned `true`. Path smoothing removed the corner waypoint. When the player walked this diagonal shortcut, their shoulder edge clipped $0.3$m deep into the solid corner of $(1, 64, 0)$, halting movement, inducing wall friction, and permanently trapping the bot.
2. **Ground Support Blindness (Floating Over Pits & Chasms)**: If a shortcut spanned an open chasm, 1-block floor hole, or ravine where intermediate air blocks were clear above floor height, the old check evaluated body space but failed to evaluate whether ground existed under feet at fractional coordinates, causing the player to walk off ledges into pits or lava.
3. **Vertical Transition Corruption**: Straight-line smoothing connected waypoints across vertical level changes (e.g. jump takeoff to landing, or ledge to pit drop), flattening parabolic jump arcs and drop columns into diagonal lasers that guided the player directly into face blocks or ceiling edges.

### 1.2 Core Architectural Mandate
To fulfill requirement **R2** of `ORIGINAL_REQUEST.md` and conform to `PROJECT.md:31, 46`:
1. Implement `com.github.foragerhelper.path.SweptBoxLOS.kt` replacing 1D Bresenham checks with **swept bounding-box raycasts** evaluating the player's full $0.6 \times 1.8$m hitbox against world `VoxelShape` geometry.
2. Subdivide straight-line segments into small sub-steps ($\le 0.35$m) and query `world.isSpaceEmpty(sweptBox)` / `world.getBlockCollisions(null, sweptBox)` to guarantee zero corner clipping.
3. Verify continuous, walkable, hazard-free ground support under feet throughout the entire shortcut.
4. Implement string-pulling path smoothing that collapses redundant collinear and zigzag waypoints while strictly preserving critical vertical anchors (jumps, drops, parkour).
5. Ensure 100% headless offline testability via a decoupled `CollisionEnvironment` abstraction so that all corner snagging and smoothing invariants run in headless CI test runners without Minecraft window dependencies.

---

## 2. Mathematical Formulation of Swept Bounding-Box Raycast

### 2.1 Player Bounding Box Definition
In Minecraft, for an entity standing at foot coordinate $\vec{P} = (x, y, z)$:
- Horizontal half-width: $R = 0.30$m ($W = 0.60$m, $D = 0.60$m).
- Vertical height: $H = 1.80$m (extending from $y$ to $y + 1.80$).
- Eye height: $y_{\text{eye}} = y + 1.62$m.

The player's instantaneous Axis-Aligned Bounding Box (AABB) is defined as:
$$\text{AABB}(\vec{P}) = [x - 0.3, y, z - 0.3] \times [x + 0.3, y + 1.8, z + 0.3]$$

In Fabric / Minecraft Yarn mappings:
```kotlin
fun getPlayerBoxAt(pos: Vec3d): Box =
    Box(pos.x - 0.3, pos.y, pos.z - 0.3, pos.x + 0.3, pos.y + 1.8, pos.z + 0.3)
```

*(Note: Minecraft's `Box.of(center, dx, dy, dz)` centers vertically around `center.y`. If using `Box.of`, the vertical center must be placed at `pos.y + 0.9`: `Box.of(pos.add(0.0, 0.9, 0.0), 0.6, 1.8, 0.6)`. Direct constructor instantiation `Box(...)` avoids extra `Vec3d` allocations).*

---

### 2.2 Continuous Swept Volume vs. Sub-Step AABB Approximation
Given two 3D waypoints $A = (x_A, y_A, z_A)$ and $B = (x_B, y_B, z_B)$, the trajectory displacement is:
$$\vec{D} = B - A = (\Delta x, \Delta y, \Delta z)$$
The 3D Euclidean length is $L = \|\vec{D}\| = \sqrt{\Delta x^2 + \Delta y^2 + \Delta z^2}$.

The theoretical continuous swept volume is the Minkowski sum of the player box and the line segment:
$$\mathcal{S} = \text{AABB}(A) \oplus [0, \vec{D}] = \{ \vec{p} + t \vec{D} \mid \vec{p} \in \text{AABB}(A), \, t \in [0, 1] \}$$

Minecraft's collision engine (`CollisionView`) operates on Axis-Aligned Bounding Boxes (`Box`), not arbitrarily oriented 3D convex polyhedra. If one constructs a single bounding box enclosing the entire segment:
$$\text{AABB}(\mathcal{S}) = \text{AABB}(A).\text{stretch}(\vec{D})$$
For a 10-meter diagonal run, $\text{AABB}(\mathcal{S})$ spans $(10/\sqrt{2} + 0.6) \times 1.8 \times (10/\sqrt{2} + 0.6) \approx 7.67\text{m} \times 1.8\text{m} \times 7.67\text{m}$. Any obstacle within that large square—even 3 meters away from the actual line of travel—would falsely invalidate the line of sight!

#### The Sub-Step Subdivision Solution
To achieve exact swept volume coverage without false over-approximation, we subdivide the segment $A \to B$ into $N$ discrete sub-steps where the maximum step displacement is strictly bounded:
$$S_{\max} = 0.35\text{ m}$$
$$N = \max\left(1, \left\lceil \frac{L}{S_{\max}} \right\rceil\right)$$

For step $k \in \{0, 1, \dots, N-1\}$:
$$t_k = \frac{k}{N}, \quad t_{k+1} = \frac{k+1}{N}$$
$$P_k = A + t_k \vec{D}, \quad P_{k+1} = A + t_{k+1} \vec{D}$$
$$\vec{\delta} = P_{k+1} - P_k = \frac{\vec{D}}{N}, \quad \|\vec{\delta}\| \le 0.35\text{ m}$$

For each sub-step $k$, the sub-swept box is:
$$\text{sweptBox}_k = \text{Box}(P_k).\text{stretch}(\vec{\delta})$$
Explicitly:
$$\text{sweptBox}_k = \text{Box}\Big(\min(P_{k.x}, P_{k+1.x}) - 0.3, \, \min(P_{k.y}, P_{k+1.y}), \, \min(P_{k.z}, P_{k+1.z}) - 0.3, \, \max(P_{k.x}, P_{k+1.x}) + 0.3, \, \max(P_{k.y}, P_{k+1.y}) + 1.8, \, \max(P_{k.z}, P_{k+1.z}) + 0.3\Big)$$

#### Bounding Box Bulge Error Analysis
Because $\|\vec{\delta}\| \le 0.35$m, the maximum geometric discrepancy ("bulge") between the sub-step AABB $\text{sweptBox}_k$ and the continuous swept prism occurs at a $45^\circ$ angle:
$$\Delta_{\text{excess}} = \frac{\|\vec{\delta}\|}{2}\left(1 - \frac{1}{\sqrt{2}}\right) \approx 0.1464 \times 0.35\text{m} \approx 0.051\text{m} \quad (\approx 5.1\text{ cm})$$
- A $5.1$cm margin is less than $1/19$th of a Minecraft block.
- This minute margin provides a **natural physical clearance buffer**, preventing player shoulders from brushing against walls and getting caught on block edge friction.
- Along the trajectory axis, the sub-boxes overlap continuously with zero gaps ($P_{k+1}$ of step $k$ is $P_k$ of step $k+1$).

---

### 2.3 Mathematical Proof of Zero Corner Snagging

#### Scenario A: The 90° Orthogonal Inner Corner
- Solid wall block at $W = (1, 64, 0)$, spanning $[1.0, 2.0] \times [64.0, 65.0] \times [0.0, 1.0]$.
- Player start waypoint: $A = (0.5, 64.0, 1.5)$.
- Goal waypoint: $B = (1.5, 64.0, 0.5)$.
- Trajectory vector: $\vec{D} = (1.0, 0.0, -1.0)$, $L = \sqrt{2} \approx 1.414$m.
- Step count: $N = \lceil 1.414 / 0.35 \rceil = 5$ steps.
- At step $k = 2$ ($t \in [0.4, 0.6]$), the trajectory traverses the midpoint $\vec{M} = (1.0, 64.0, 1.0)$.
- The player box at $\vec{M}$ spans:
  $$X \in [0.70, 1.30], \quad Y \in [64.0, 65.8], \quad Z \in [0.70, 1.30]$$
- Intersection with block $W$:
  $$\Delta X = [0.70, 1.30] \cap [1.00, 2.00] = [1.00, 1.30] \quad (\text{overlap } 0.30\text{m})$$
  $$\Delta Z = [0.70, 1.30] \cap [0.00, 1.00] = [0.70, 1.00] \quad (\text{overlap } 0.30\text{m})$$
  $$\Delta Y = [64.0, 65.8] \cap [64.0, 65.0] = [64.0, 65.0] \quad (\text{overlap } 1.00\text{m})$$
- Collision volume: $0.30 \times 1.00 \times 0.30 = 0.090\text{ m}^3 > 0$.
- `world.isSpaceEmpty(sweptBox_2)` returns `false`.
- **Outcome**: `hasLineOfSight(A, B)` returns `false`. The corner shortcut is rejected. Waypoint $C = (0.5, 64.0, 0.5)$ is retained. Snagging is mathematically impossible.

#### Scenario B: 1-Block Wide Doorway / Hallway
- Hallway along the X-axis: walls at $Z \le 0.0$ and $Z \ge 1.0$.
- Walkable corridor: $Z \in (0.0, 1.0)$ (width $1.0$m).
- Player walks along center line: $Z = 0.5$.
- Player box: $Z \in [0.5 - 0.3, 0.5 + 0.3] = [0.2, 0.8]$.
- Left clearance: $0.2 - 0.0 = 0.20$m $> 0$.
- Right clearance: $1.0 - 0.8 = 0.20$m $> 0$.
- `sweptBox_k` for all steps has $Z \in [0.2, 0.8]$, strictly within $[0.0, 1.0]$.
- **Outcome**: `hasLineOfSight` returns `true`. The straight path through the 1-block doorway succeeds cleanly.

---

## 3. World Collision Querying & VoxelShape Integration

### 3.1 Fabric & Minecraft 1.21.11 API Mapping
In Minecraft Fabric 1.21.11 Yarn:
- `net.minecraft.world.CollisionView`:
  - `fun isSpaceEmpty(box: Box): Boolean`: Evaluates block collisions and entity collisions. Calls `isBlockSpaceEmpty(null, box, false)`.
  - `fun isBlockSpaceEmpty(entity: Entity?, box: Box): Boolean`: Iterates `BlockCollisionSpliterator`, returning `false` on the first non-empty `VoxelShape`.
  - `fun getBlockCollisions(entity: Entity?, box: Box): Iterable<VoxelShape>`: Returns colliding block voxel shapes.

```
                  ┌───────────────────────────────┐
                  │   sweptBox_k (AABB, <=0.35m)   │
                  └───────────────┬───────────────┘
                                  │
                                  ▼
                  ┌───────────────────────────────┐
                  │ world.isBlockSpaceEmpty(...)  │
                  └───────┬───────────────┬───────┘
                     true │               │ false
                          ▼               ▼
           ┌─────────────────────┐   ┌──────────────────────┐
           │ Pass-Through Hazard │   │ Collision Detected!  │
           │ Check (Cobweb, Fire)│   │ Return false (Exit)  │
           └──────┬──────────────┘   └──────────────────────┘
             pass │
                  ▼
           ┌─────────────────────┐
           │  Ground Walkability │
           │   & Hazard Check    │
           └─────────────────────┘
```

### 3.2 Pass-Through Non-Colliding Hazard Detection
Blocks with empty collision shapes (`shape.isEmpty == true`) are ignored by `isSpaceEmpty`. However, some non-colliding blocks represent lethal or movement-breaking hazards:
- **Lava** (`fluidState.isIn(FluidTags.LAVA)` or `Blocks.LAVA`)
- **Fire / Soul Fire** (`Blocks.FIRE`, `Blocks.SOUL_FIRE`)
- **Sweet Berry Bush** (`Blocks.SWEET_BERRY_BUSH` — damages and slows)
- **Powder Snow** (`Blocks.POWDER_SNOW` — sinks player, freezes)
- **Cobweb** (`Blocks.COBWEB` — reduces horizontal velocity to 0.05)
- **Cactus** (`Blocks.CACTUS`)

For each sub-step box `sweptBox_k`:
```kotlin
val minX = floor(sweptBox.minX).toInt()
val maxX = floor(sweptBox.maxX).toInt()
val minY = floor(sweptBox.minY).toInt()
val maxY = floor(sweptBox.maxY).toInt()
val minZ = floor(sweptBox.minZ).toInt()
val maxZ = floor(sweptBox.maxZ).toInt()

val mutPos = BlockPos.Mutable()
for (x in minX..maxX) {
    for (y in minY..maxY) {
        for (z in minZ..maxZ) {
            mutPos.set(x, y, z)
            val state = world.getBlockState(mutPos)
            val fluid = world.getFluidState(mutPos)
            if (isPassThroughHazard(state, fluid)) {
                return false // Hazard intercepts swept trajectory
            }
        }
    }
}
```

---

## 4. Continuous Ground Walkability & Floor Hazard Detection

### 4.1 Ground Support Verification Model
A shortcut cannot be traversed if the player floats over empty air, a ravine, or a pit. At each sub-step $k$, the ground directly beneath the player's feet must be verified.

#### Downward Ground Sample Box
At the sub-step midpoint $P_{\text{mid}} = (P_k + P_{k+1}) / 2.0$:
- The feet are at elevation $y = P_{\text{mid}}.y$.
- Horizontal footprint inset: $R_{\text{ground}} = 0.20$m (using $0.20$m instead of $0.30$m avoids clipping vertical walls adjacent to the player).
- Vertical inquiry depth: from $y - 0.60$m to $y + 0.05$m.

$$\text{groundBox} = \text{Box}(P_{\text{mid}}.x - 0.2, \, P_{\text{mid}}.y - 0.6, \, P_{\text{mid}}.z - 0.2, \, P_{\text{mid}}.x + 0.2, \, P_{\text{mid}}.y + 0.05, \, P_{\text{mid}}.z + 0.2)$$

#### Verification Conditions:
1. **Physical Support**: Query `world.getBlockCollisions(null, groundBox)`. If the iterable is empty, no solid surface exists within $0.6$m below the feet. The player is over a pit or drop $\implies$ return `false`.
2. **Floor Surface Elevation**: From the intersecting shapes, extract the maximum surface elevation:
   $$Y_{\text{surface}} = \max \{ \text{shape.getMax}(Y) + \text{pos.y} \}$$
   Verify that $Y_{\text{surface}} \in [P_{\text{mid}}.y - 0.6, P_{\text{mid}}.y + 0.15]$. If the floor is too low ($> 0.6$m drop) or too high ($> 0.15$m step without stair/slab transition), the shortcut is rejected.
3. **Ground Hazard Check**:
   The block directly beneath the feet:
   `val floorPos = BlockPos.ofFloored(P_mid.x, P_mid.y - 0.1, P_mid.z)`
   Verify:
   `!isHazardGround(world.getBlockState(floorPos), world.getFluidState(floorPos))`
   (Rejects lava, magma blocks, cacti, fire, powder snow, campfires).

---

## 5. Path Smoothing (String Pulling) Architecture

### 5.1 Greedy Backward-Search String Pulling
Given a raw path of discrete waypoints $\mathcal{W} = [W_0, W_1, \dots, W_{M-1}]$ produced by A*:
1. Maintain current index $i = 0$. Append $W_0$ to `smoothed`.
2. Search backward from candidate index $j = M - 1$ down to $i + 1$:
   - Check if the segment $W_i \to W_j$ is a valid smoothing candidate (respecting vertical anchor rules).
   - If candidate is valid, execute `hasLineOfSight(world, W_i, W_j)`.
   - If line of sight is clear, select $j$ as the next waypoint.
3. Append $W_j$ to `smoothed`.
4. Set $i = j$. Repeat until $i = M - 1$.

```
Raw A* Path (Zigzags & Corner Turn):
W0 ────► W1 ────► W2 ────► W3 ────► W4 ────► W5 (Goal)
 │                                            ▲
 └───────────────── (Blocked by Wall) ────────┘
 │
 └───────────────► W2 ────────────────────────► W5
                    (LOS Clear!)
Smoothed Path: [W0, W2, W5]
```

### 5.2 Critical Vertical Anchor Preservation
Straight-line string pulling must **never** smooth across discontinuous vertical maneuvers:
1. **Jumps ($\Delta Y > 0.6$m)**: A jump requires approaching the base of the block, jumping with horizontal momentum, and clearing apex ceiling headroom. If the takeoff node is smoothed away, the movement controller walks toward the landing coordinate diagonally, colliding into the wall face.
2. **Drops ($\Delta Y < -0.6$m)**: A drop requires walking over the edge and falling straight down. Smoothing diagonally creates a sloped trajectory through mid-air.
3. **Parkour Gaps**: Gaps over air have no ground support; smoothing across them is rejected by ground validation.

#### Smoothing Candidate Predicate:
```kotlin
fun isSmoothingCandidate(raw: List<Vec3d>, fromIdx: Int, toIdx: Int): Boolean {
    // 1. Cannot skip if distance exceeds maximum smooth range
    val distSq = raw[fromIdx].squaredDistanceTo(raw[toIdx])
    if (distSq > MAX_SMOOTH_DISTANCE * MAX_SMOOTH_DISTANCE) return false

    // 2. Cannot skip across any vertical jump or deep drop in the intermediate sequence
    for (k in fromIdx until toIdx) {
        val deltaY = raw[k + 1].y - raw[k].y
        if (deltaY > MAX_STEP_HEIGHT || deltaY < -MAX_STEP_HEIGHT) {
            // An intermediate step is a vertical jump or drop!
            // Candidate must not bypass this vertical transition.
            return false
        }
    }

    // 3. Slope check between endpoints: overall elevation change must be compatible with slabs/stairs
    val totalDeltaY = abs(raw[toIdx].y - raw[fromIdx].y)
    val horizDist = sqrt((raw[toIdx].x - raw[fromIdx].x).let { it * it } + (raw[toIdx].z - raw[fromIdx].z).let { it * it })
    if (horizDist < 1e-4) return totalDeltaY <= MAX_STEP_HEIGHT
    
    // Max slope: 0.6m vertical per 1.0m horizontal (standard stairs/slabs)
    return (totalDeltaY / horizDist) <= 0.65
}
```

---

## 6. Complete Implementation Specification: `SweptBoxLOS.kt`

```kotlin
package com.github.foragerhelper.path

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.Entity
import net.minecraft.fluid.FluidState
import net.minecraft.registry.tag.FluidTags
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Swept Bounding-Box Line-of-Sight & Path Smoothing Engine.
 *
 * Replaces flawed 1D Bresenham raycasts with continuous 3D swept AABB checks.
 * Completely eliminates diagonal corner snagging, guarantees ground walkability,
 * prevents clipping over hazards, and collapses redundant waypoints via string pulling.
 */
object SweptBoxLOS {

    const val PLAYER_WIDTH = 0.6
    const val PLAYER_DEPTH = 0.6
    const val PLAYER_HEIGHT = 1.8
    const val HALF_WIDTH = 0.3
    const val MAX_STEP_LENGTH = 0.35
    const val MAX_STEP_HEIGHT = 0.60
    const val MAX_SMOOTH_DISTANCE = 32.0
    const val GROUND_SAMPLE_INSET = 0.10 // 0.3 - 0.1 = 0.2m half-width for ground checks

    /**
     * Diagnostic result of a swept LOS check.
     */
    data class LOSResult(
        val hasLOS: Boolean,
        val failedStep: Int = -1,
        val failureReason: LOSFailureReason? = null,
        val failurePos: Vec3d? = null
    ) {
        companion object {
            val CLEAR = LOSResult(true)
        }
    }

    enum class LOSFailureReason {
        OBSTACLE_COLLISION,
        NO_GROUND_SUPPORT,
        HAZARD_PASS_THROUGH,
        HAZARD_GROUND,
        EXCESSIVE_VERTICAL_SLOPE,
        EXCEEDS_MAX_DISTANCE
    }

    /**
     * Checks swept bounding-box line-of-sight between two continuous 3D points.
     *
     * @param world The world instance (implements CollisionView).
     * @param from Start foot coordinate (Vec3d).
     * @param to Target foot coordinate (Vec3d).
     * @param entity Optional entity context (defaults to null for ShapeContext.absent()).
     * @return true if the swept box is completely clear and ground is walkable; false otherwise.
     */
    fun hasLineOfSight(world: World, from: Vec3d, to: Vec3d, entity: Entity? = null): Boolean =
        checkLineOfSight(world, from, to, entity).hasLOS

    /**
     * Detailed swept line-of-sight check with failure diagnostic data.
     */
    fun checkLineOfSight(world: World, from: Vec3d, to: Vec3d, entity: Entity? = null): LOSResult {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val distSq = dx * dx + dy * dy + dz * dz

        if (distSq > MAX_SMOOTH_DISTANCE * MAX_SMOOTH_DISTANCE) {
            return LOSResult(false, 0, LOSFailureReason.EXCEEDS_MAX_DISTANCE, from)
        }

        val distance = sqrt(distSq)
        if (distance < 1e-5) {
            // Trivial zero-length segment: check static standing clearance
            val box = createPlayerBox(from)
            if (!world.isSpaceEmpty(entity, box)) {
                return LOSResult(false, 0, LOSFailureReason.OBSTACLE_COLLISION, from)
            }
            return if (isGroundSupported(world, from, entity)) LOSResult.CLEAR
            else LOSResult(false, 0, LOSFailureReason.NO_GROUND_SUPPORT, from)
        }

        // Subdivide segment into small sub-steps (<= 0.35m)
        val steps = max(1, ceil(distance / MAX_STEP_LENGTH).toInt())
        val stepVec = Vec3d(dx / steps, dy / steps, dz / steps)

        var prevPos = from
        for (i in 0 until steps) {
            val nextPos = if (i == steps - 1) to else from.add(stepVec.multiply((i + 1).toDouble()))

            // 1. Compute swept bounding box for this sub-step
            val sweptBox = createSweptBox(prevPos, nextPos)

            // 2. Query world collision: must be empty of solid block geometry
            if (!world.isSpaceEmpty(entity, sweptBox)) {
                return LOSResult(false, i, LOSFailureReason.OBSTACLE_COLLISION, prevPos)
            }

            // 3. Inspect for non-colliding pass-through hazards (cobwebs, fire, sweet berry bushes)
            if (hasPassThroughHazard(world, sweptBox)) {
                return LOSResult(false, i, LOSFailureReason.HAZARD_PASS_THROUGH, prevPos)
            }

            // 4. Verify continuous walkable ground support beneath feet
            val midPos = prevPos.add(nextPos).multiply(0.5)
            if (!isGroundSupported(world, midPos, entity)) {
                return LOSResult(false, i, LOSFailureReason.NO_GROUND_SUPPORT, midPos)
            }

            prevPos = nextPos
        }

        // Final arrival check at destination
        if (!isGroundSupported(world, to, entity)) {
            return LOSResult(false, steps, LOSFailureReason.NO_GROUND_SUPPORT, to)
        }

        return LOSResult.CLEAR
    }

    /**
     * Performs string-pulling path smoothing on raw A* waypoints.
     * Eliminates redundant waypoints while strictly respecting swept-box clearance and vertical anchors.
     *
     * @param world The world instance.
     * @param rawWaypoints Ordered list of waypoints from A*.
     * @param entity Optional entity context.
     * @return Smoothed list of waypoints.
     */
    fun smoothPath(world: World, rawWaypoints: List<Vec3d>, entity: Entity? = null): List<Vec3d> {
        if (rawWaypoints.size <= 2) return rawWaypoints

        val smoothed = ArrayList<Vec3d>(rawWaypoints.size)
        var currentIdx = 0
        smoothed.add(rawWaypoints[0])

        while (currentIdx < rawWaypoints.lastIndex) {
            var nextIdx = currentIdx + 1

            // Greedy backward search for farthest reachable waypoint
            for (candidateIdx in rawWaypoints.lastIndex downTo currentIdx + 2) {
                if (canSmoothCandidate(rawWaypoints, currentIdx, candidateIdx)) {
                    if (hasLineOfSight(world, rawWaypoints[currentIdx], rawWaypoints[candidateIdx], entity)) {
                        nextIdx = candidateIdx
                        break
                    }
                }
            }

            smoothed.add(rawWaypoints[nextIdx])
            currentIdx = nextIdx
        }

        return smoothed
    }

    /**
     * Evaluates whether two waypoints in the sequence are permissible smoothing candidates.
     * Strictly preserves vertical jumps, drops, and steep slopes.
     */
    fun canSmoothCandidate(raw: List<Vec3d>, fromIdx: Int, toIdx: Int): Boolean {
        // 1. Distance ceiling
        val from = raw[fromIdx]
        val to = raw[toIdx]
        val distSq = from.squaredDistanceTo(to)
        if (distSq > MAX_SMOOTH_DISTANCE * MAX_SMOOTH_DISTANCE) return false

        // 2. Invariant: Cannot bridge across any intermediate vertical jump or drop
        for (k in fromIdx until toIdx) {
            val stepDy = raw[k + 1].y - raw[k].y
            if (stepDy > MAX_STEP_HEIGHT || stepDy < -MAX_STEP_HEIGHT) {
                return false
            }
        }

        // 3. Slope gradient check
        val totalDy = abs(to.y - from.y)
        val horizDist = sqrt((to.x - from.x).let { it * it } + (to.z - from.z).let { it * it })
        if (horizDist < 1e-4) return totalDy <= MAX_STEP_HEIGHT

        // Allow max 0.6m rise per 1.0m horizontal (stairs/slabs slope)
        return (totalDy / horizDist) <= 0.65
    }

    /**
     * Constructs instantaneous AABB for player feet at pos.
     */
    fun createPlayerBox(pos: Vec3d): Box =
        Box(pos.x - HALF_WIDTH, pos.y, pos.z - HALF_WIDTH, pos.x + HALF_WIDTH, pos.y + PLAYER_HEIGHT, pos.z + HALF_WIDTH)

    /**
     * Constructs the enclosing swept AABB for a sub-step from p1 to p2.
     */
    fun createSweptBox(p1: Vec3d, p2: Vec3d): Box {
        val minX = min(p1.x, p2.x) - HALF_WIDTH
        val maxX = max(p1.x, p2.x) + HALF_WIDTH
        val minY = min(p1.y, p2.y)
        val maxY = max(p1.y, p2.y) + PLAYER_HEIGHT
        val minZ = min(p1.z, p2.z) - HALF_WIDTH
        val maxZ = max(p1.z, p2.z) + HALF_WIDTH
        return Box(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /**
     * Validates continuous ground support and floor safety under pos.
     */
    fun isGroundSupported(world: World, pos: Vec3d, entity: Entity? = null): Boolean {
        // Inset horizontal ground footprint by GROUND_SAMPLE_INSET (0.2m half-width)
        val halfR = HALF_WIDTH - GROUND_SAMPLE_INSET
        val groundBox = Box(
            pos.x - halfR, pos.y - MAX_STEP_HEIGHT, pos.z - halfR,
            pos.x + halfR, pos.y + 0.05, pos.z + halfR
        )

        // Query block collisions within ground inquiry box
        val collisions = world.getBlockCollisions(entity, groundBox)
        val iterator = collisions.iterator()
        if (!iterator.hasNext()) {
            return false // Empty space beneath feet (open air / pit)
        }

        // Verify supporting block surface is safe and walkable
        val floorBlockPos = BlockPos.ofFloored(pos.x, pos.y - 0.1, pos.z)
        val floorState = world.getBlockState(floorBlockPos)
        val floorFluid = world.getFluidState(floorBlockPos)

        if (isHazardGround(floorState, floorFluid)) {
            return false
        }

        // Check if collision surface elevation is within reachable step height
        var maxCollisionY = Double.NEGATIVE_INFINITY
        while (iterator.hasNext()) {
            val shape = iterator.next()
            if (!shape.isEmpty) {
                val shapeMaxY = shape.boundingBox.maxY
                if (shapeMaxY > maxCollisionY) {
                    maxCollisionY = shapeMaxY
                }
            }
        }

        if (maxCollisionY == Double.NEGATIVE_INFINITY) return false
        val deltaY = pos.y - maxCollisionY
        // Foot elevation must rest within valid floor surface range
        return deltaY in -0.15..MAX_STEP_HEIGHT
    }

    /**
     * Detects non-colliding pass-through hazard blocks inside the swept volume.
     */
    fun hasPassThroughHazard(world: World, box: Box): Boolean {
        val minX = floor(box.minX).toInt()
        val maxX = floor(box.maxX).toInt()
        val minY = floor(box.minY).toInt()
        val maxY = floor(box.maxY).toInt()
        val minZ = floor(box.minZ).toInt()
        val maxZ = floor(box.maxZ).toInt()

        val mut = BlockPos.Mutable()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    mut.set(x, y, z)
                    val state = world.getBlockState(mut)
                    val fluid = world.getFluidState(mut)
                    if (isPassThroughHazard(state, fluid)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun isPassThroughHazard(state: BlockState, fluidState: FluidState): Boolean {
        if (!fluidState.isEmpty && fluidState.isIn(FluidTags.LAVA)) return true
        val b = state.block
        return b == Blocks.LAVA ||
               b == Blocks.FIRE ||
               b == Blocks.SOUL_FIRE ||
               b == Blocks.SWEET_BERRY_BUSH ||
               b == Blocks.POWDER_SNOW ||
               b == Blocks.COBWEB ||
               b == Blocks.WITHER_ROSE
    }

    fun isHazardGround(state: BlockState, fluidState: FluidState): Boolean {
        if (!fluidState.isEmpty && fluidState.isIn(FluidTags.LAVA)) return true
        val b = state.block
        return b == Blocks.LAVA ||
               b == Blocks.FIRE ||
               b == Blocks.SOUL_FIRE ||
               b == Blocks.SWEET_BERRY_BUSH ||
               b == Blocks.POWDER_SNOW ||
               b == Blocks.CACTUS ||
               b == Blocks.WITHER_ROSE ||
               b == Blocks.MAGMA_BLOCK ||
               b == Blocks.CAMPFIRE ||
               b == Blocks.SOUL_CAMPFIRE
    }
}
```

---

## 7. Headless Offline Testing Strategy

To ensure strict compliance with `PROJECT.md:58` and `TEST_INFRA.md` (all tests run headless via Gradle with zero dependency on active Minecraft client windows), we define a lightweight synthetic test harness:

### 7.1 Synthetic Environment Adapter (`CollisionEnvironment`)
For unit tests, `SweptBoxLOS` can be tested against a synthetic collision environment:
```kotlin
interface CollisionEnvironment {
    fun isSpaceEmpty(box: Box): Boolean
    fun isGroundSupported(pos: Vec3d): Boolean
    fun hasPassThroughHazard(box: Box): Boolean
}
```
In tests, a mock/synthetic grid populates solid blocks, air, slabs, and pits as bounding boxes.

### 7.2 Tier 1-4 Test Suite Mapping for Swept-Box LOS
| Tier | Test Identifier | Verification Condition | Expected Result |
| :--- | :--- | :--- | :--- |
| **Tier 1** | `testPlayerBoxDimensions` | `createPlayerBox(0, 64, 0)` | Bounds: $[-0.3, 64.0, -0.3] \to [0.3, 65.8, 0.3]$ |
| **Tier 1** | `testSubStepSubdivisionCount` | Segment length $1.4$m with $S_{\max} = 0.35$m | Exact $N = 4$ steps; step length $= 0.35$m |
| **Tier 1** | `testSweptBoxStretchMonotonicity` | Sweep $A(0,0,0) \to B(0.3, 0, 0.2)$ | Stretched box encloses both endpoint boxes with zero gap |
| **Tier 2** | `testOrthogonalCornerSnagging` | Solid block $(1, 64, 0)$, sweep $(0.5, 64, 1.5) \to (1.5, 64, 0.5)$ | `hasLineOfSight == false`, failure reason `OBSTACLE_COLLISION` |
| **Tier 2** | `testOneBlockDoorwayClearance` | Walls at $Z \le 0$ and $Z \ge 1$, sweep along $Z=0.5$ | `hasLineOfSight == true`, clearances $= 0.20$m |
| **Tier 2** | `testChasmPitFloorDetection` | 1-block floor hole at $(0, 63, 2)$, sweep $(0, 64, 0) \to (0, 64, 4)$ | `hasLineOfSight == false`, failure reason `NO_GROUND_SUPPORT` |
| **Tier 2** | `testHazardBlockInterception` | Sweet berry bush at $(0, 64, 2)$, sweep $(0, 64, 0) \to (0, 64, 4)$ | `hasLineOfSight == false`, failure reason `HAZARD_PASS_THROUGH` |
| **Tier 3** | `testZigzagStringPulling` | Open flat terrain, 10-node zigzag path | Collapses into 2 waypoints: `[start, goal]` |
| **Tier 3** | `testCornerWaypointPreservation` | Path around inner wall corner: $A \to C \to B$ | `smoothPath` strictly preserves corner waypoint $C$ |
| **Tier 3** | `testVerticalJumpAnchorPreservation`| Path with jump up: $[W_0, W_1, \text{takeoff}, \text{landing}, W_4]$ | Takeoff and landing nodes preserved; flat runs smoothed |
| **Tier 4** | `testComplexObstacleCourse` | Multi-room route with doors, corners, slabs, and pits | Zero corner clipping, zero pit falls, clean minimal waypoints |

---

## 8. Peer Module Alignment & Contracts

### 8.1 Contract with `Pathfinder.kt` (Explorer 1)
- In `Pathfinder.kt`, `findPath(...)` generates a raw list of hitbox-cleared nodes `List<Vec3d>`.
- `Pathfinder.kt` calls:
  `val smoothedWaypoints = SweptBoxLOS.smoothPath(world, rawWaypoints)`
- The smoothed waypoints are returned in `PathResult(success = true, waypoints = smoothedWaypoints)`.
- Because `SweptBoxLOS` operates on continuous `Vec3d` coordinates, waypoints match the player's exact standing coordinates $(X + 0.5, Y_{\text{stand}}, Z + 0.5)$.

### 8.2 Contract with `MovementController.kt` (Milestone 4)
- `MovementController` receives `PathResult.waypoints`.
- Because diagonal corners are guaranteed not to clip, `MovementController` can execute straight-line sprint-jumping and direct forward WASD vectors between waypoints without snagging on wall edges.
- Tangent look vectors computed by `RotationEngine` orient smoothly along the extended smoothed segments, eliminating frantic high-frequency camera jerking at every single grid block.

---

## 9. Conclusion
`SweptBoxLOS.kt` provides a mathematically complete, hitbox-aware collision and line-of-sight engine that resolves the long-standing corner snagging flaw in ForagerHelper. By unifying sub-step swept AABB testing ($\le 0.35$m), continuous ground support verification, pass-through hazard detection, and anchor-preserving string pulling, it delivers humanized, snag-free navigation fully compliant with Milestone 2 specifications.
