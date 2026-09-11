# Milestone 2 Technical Analysis: Terrain Traversal, Swept-Box Collision & Offline Test Strategy

**Author**: Explorer 3 (Milestone 2 - Terrain Traversal & Testability)  
**Date**: 2026-09-11  
**Target Environment**: Minecraft 1.21.11, Fabric Loader 0.19.3, Yarn Mappings 1.21.11+build.6, Java 23 (target JVM 21), Kotlin 2.4.10  

---

## 1. Executive Summary

Milestone 2 requires implementing a hitbox-aware 3D A* pathfinder (`path/Pathfinder.kt`) respecting the player bounding box ($0.6 \times 1.8$m), continuous swept bounding-box line-of-sight (`path/SweptBoxLOS.kt`), vertical traversal mechanics (slabs, stairs, fences, ceilings, drops, parkour), and spatial node penalties (`path/NodePenaltyMap.kt`).

This investigation provides:
1. **Offline Test Execution Audit**: Verification of how Gradle executes tests, what Minecraft classes are usable offline, and why direct `World` / `ClientWorld` instantiation fails without a decoupled abstraction.
2. **Exhaustive 1.21.11 Movement & Collision Mechanics**: Precise mathematical and physical specifications for slabs, stairs, carpets, trapdoors, 1.5m fences/walls, static 1.8m headroom, 2.5m jump-apex ceiling clearance, and hazard penalization.
3. **Minkowski Swept-Box Collision Algorithm**: Mathematical derivation and implementation blueprint of the continuous ray-AABB (Slab method) collision check replacing defective 1D Bresenham smoothing.
4. **Offline Testing Strategy**: An elegant `PathEnvironment` / `TestWorldGrid` test harness enabling comprehensive 5-Tier headless unit tests in `PathfinderTest.kt` via `gradlew test` without requiring external mocking frameworks (Mockito/MockK) or a running Minecraft client.

---

## 2. Offline Gradle Test Environment & Class Instantiation

### 2.1 Current Test Execution Audit
Existing tests in `src/test/kotlin/com/github/foragerhelper/rotation/` (`RotationEngineTest`, `SensitivityGCDAdversarialTest`, `SpringSmootherAdversarialTest`) execute via:
```cmd
cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"
```
- **Results**: 55 tests pass cleanly in 3.68 seconds, 0 failures, 0 errors.
- **Classpath Structure**: Fabric Loom mounts the remapped Minecraft 1.21.11 JAR (`minecraft-merged-2ae02fda0f-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`) directly onto both compile and test runtime classpaths.

### 2.2 Minecraft Class Viability Matrix (Offline vs Online)
| Class | Offline Status | Failure Mode if Unbootstrapped | Recommended Handling |
|---|---|---|---|
| `net.minecraft.util.math.Vec3d` | **100% Offline Safe** | None (pure math class) | Instantiate directly (`Vec3d(x, y, z)`) |
| `net.minecraft.util.math.BlockPos` | **100% Offline Safe** | None (pure coordinate class) | Instantiate directly (`BlockPos(x, y, z)`) |
| `net.minecraft.util.math.Box` | **100% Offline Safe** | None (pure AABB math class) | Instantiate directly (`Box(minX, minY, minZ, maxX, maxY, maxZ)`) |
| `net.minecraft.util.math.MathHelper` | **100% Offline Safe** | None (pure math utility) | Use directly |
| `net.minecraft.block.Blocks` | **Conditional** | `NullPointerException` / uninitialized registry if accessed before `Bootstrap.initialize()` | Call `SharedConstants.createGameVersion(); Bootstrap.initialize()` or use `TestWorldGrid` |
| `net.minecraft.world.World` | **Abstract / Online Only** | Abstract class with ~100 methods, requires `MutableWorldProperties`, `Profiler`, `DynamicRegistryManager`, `DamageSources`, etc. | Decouple via `CollisionView` or `PathEnvironment` interface |
| `net.minecraft.client.world.ClientWorld` | **Online Only** | Constructor requires `ClientPlayNetworkHandler`, `WorldRenderer`, client connection, etc. Throws NPE on instantiation. | **NEVER** instantiate in unit tests |
| `net.minecraft.world.CollisionView` | **Interface (Offline Implementable)** | Extends `BlockView`, requires only ~8 abstract methods (`getBlockCollisions`, `isSpaceEmpty`, `getBlockState`, `getFluidState`, etc.) | Implement via `TestWorldGrid` |

---

## 3. Minecraft 1.21.11 Vertical Traversal & Collision Mechanics

### 3.1 Player Geometry & Stepping Parameters
- **Player Width**: $0.6$m ($x \pm 0.3$, $z \pm 0.3$).
- **Player Height**: $1.80$m (feet $y$ to head $y + 1.8$).
- **Eye Height**: $1.62$m.
- **Vanilla Step Height**: $0.60$m (`generic.step_height` attribute default).
  - Any elevation increase $\le 0.60$m is traversed as a **continuous step-up without jumping**.
  - Any elevation increase $> 0.60$m and $\le 1.25$m requires a **jump**.
  - Any elevation increase $> 1.25$m is **impassable** in a single vertical step.

### 3.2 Slabs (`SlabBlock`)
| Slab Type | Collision Box $[x, y, z]$ | Standing Surface | Step Transition from Ground ($y=0$) | Headroom Clearance Required |
|---|---|---|---|---|
| `BOTTOM` | $[0.0, 0.0, 0.0] \to [1.0, 0.5, 1.0]$ | $y + 0.5$ | $\Delta y = 0.5 \le 0.6$ (**No Jump**) | Space from $y + 0.5$ to $y + 2.3$ must be clear |
| `TOP` | $[0.0, 0.5, 0.0] \to [1.0, 1.0, 1.0]$ | $y + 1.0$ | $\Delta y = 1.0 > 0.6$ (**Jump Required**) | Space from $y + 1.0$ to $y + 2.8$ must be clear |
| `DOUBLE` | $[0.0, 0.0, 0.0] \to [1.0, 1.0, 1.0]$ | $y + 1.0$ | $\Delta y = 1.0 > 0.6$ (**Jump Required**) | Space from $y + 1.0$ to $y + 2.8$ must be clear |

*Key Nuance*: Underneath a `TOP` slab, the gap from $0.0$ to $0.5$ is open air, but has only $0.5$m clearance. A player (height $1.8$m) cannot walk underneath a top slab.

### 3.3 Stairs (`StairsBlock`)
- **Ascending along stair orientation** (e.g. `FACING = EAST`, moving from West to East):
  - Lower step: $y + 0.5$ ($\Delta y = 0.5 \le 0.6$, auto-stepped without jump).
  - Upper step: $y + 1.0$ ($\Delta y = 0.5 \le 0.6$, auto-stepped without jump).
  - **Result**: The player ascends a full $1.0$m block height across stairs **without executing a single jump**.
  - **A* Cost**: Cost should be $1.15 \times$ flat walking (cheaper than jumping, reflecting forward walking speed).
- **Ascending from high side or back**:
  - The back face is a solid $1.0$m wall. Player cannot step up; must jump or cannot enter.
- **Headroom over stairs**:
  - Over lower step ($y+0.5$), head reaches $y+2.3$. Block at $y+1$ must be air, block at $y+2$ must have no collision in the lower $0.3$m.
  - Over upper step ($y+1.0$), head reaches $y+2.8$. Blocks at $y+1$ and $y+2$ must both be completely clear.

### 3.4 Carpets & Trapdoors
- **Carpet** (`Box(0, 0, 0, 1, 0.0625, 1)`):
  - Walking surface: $y + 0.0625$.
  - Step-up: $0.0625 \le 0.6$ (smooth step-up).
  - Standing under a standard 2-block tunnel ($y=0$ floor, $y=2$ ceiling):
    $$\text{Clear height} = 2.0 - 0.0625 = 1.9375\text{m} > 1.80\text{m} \implies \textbf{Passable}$$
- **Trapdoors** (`Thickness = 0.1875` = $3/16$m):
  - Closed on floor (`HALF=BOTTOM`): surface $y + 0.1875$. Step-up $0.1875 \le 0.6$ (smooth).
  - Closed on ceiling (`HALF=BOTTOM`, attached to block at $y=2$): hangs down into tunnel by $0.1875$m (collision at $[1.8125, 2.0]$).
    $$\text{Clear height} = 2.0 - 0.1875 = 1.8125\text{m} > 1.80\text{m} \implies \textbf{Passable (margin: 1.25cm)}$$
  - **Adversarial Combination (Trapdoor + Carpet)**:
    - Floor has carpet ($+0.0625$m).
    - Ceiling has hanging trapdoor ($-0.1875$m).
    $$\text{Effective Clear Height} = 2.0 - 0.0625 - 0.1875 = 1.75\text{m} < 1.80\text{m} \implies \textbf{BLOCKED / IMPASSABLE}$$
    The pathfinder must recognize this exact sub-block clearance violation!

### 3.5 Fences & Walls (1.5-Block Height Barrier)
- **Collision Box**:
  - Wooden fences (`FenceBlock`), Nether brick fences, and stone walls (`WallBlock`) have visual height $1.0$, but collision shape extends to $y = 1.50$!
- **Jump Apex Limit**:
  - Minecraft jump trajectory: initial $v_{y0} = 0.42$, gravity $0.08$, air drag $0.98$.
  - Maximum apex height: $\Delta y_{apex} \approx 1.252$ blocks.
  - Because $1.252 < 1.500$, a player **CANNOT jump over a fence or wall from the same ground level**.
- **Pathfinder Rule**:
  - Any node transition attempting to jump onto or over a fence/wall from $y_{source} \le y_{fence}$ must be **strictly rejected**.
  - Traversing over a fence is ONLY valid if jumping down from an elevated platform ($y_{source} \ge y_{fence} + 0.5$) or using carpet on top of the fence.
- **Fence Gates**:
  - When closed: collision height $1.5$ (same as fence).
  - When open: collision shape is `VoxelShapes.empty()` (freely passable).

### 3.6 Headroom & Jump Apex Dynamics
- **Static Headroom**:
  - Standing player box: $[x - 0.3, y, z - 0.3] \to [x + 0.3, y + 1.8, z + 0.3]$.
  - Every standing node must have $1.80$m vertical clearance.
- **Jump Apex Headroom**:
  - When jumping up $1.0$m (from $y=0$ to $y=1$):
    - Jump apex is $y \approx 1.252$.
    - Player head reaches $y_{head} = 1.252 + 1.80 = 3.052$m.
    - If a ceiling block exists at $y = 2$ (collision from $2.0$ to $3.0$), the player's head collides with the ceiling at $y = 2.0$ when feet are only at $y = 0.2$!
    - When head collides with ceiling, Minecraft sets $v_y = 0$. Forward momentum stalls, and player falls back down to $y = 0$, failing to reach the $y = 1.0$ ledge.
  - **Rule**: Jumping up $1$ block requires at least **$2.5$m clear vertical space above the takeoff block** (meaning $y+1$ and $y+2$ must be completely clear of solid collision).

### 3.7 Parkour Gaps
- A 1-block horizontal gap (e.g. jump from $(0, 64, 0)$ to $(2, 64, 0)$ over gap at $(1, 63, 0)$):
  - Requires clear ground at takeoff and landing.
  - Requires jump clearance above takeoff ($y+1, y+2$).
  - Requires airborne clearance above the gap ($y+1, y+2$).
  - Landing at same elevation ($y$) or 1 block down ($y-1$).

### 3.8 Hazardous Blocks
| Block Type | Danger Mechanism | Pathfinder Action / Penalty |
|---|---|---|
| **Lava** (`Blocks.LAVA`) | Fatal fire damage, extreme viscosity | Cost = $\infty$ (Forbidden node) |
| **Powder Snow** (`Blocks.POWDER_SNOW`) | Sinking, freeze damage, jumps disabled | Cost = $\infty$ (Forbidden node) |
| **Cactus** (`Blocks.CACTUS`) | Contact damage ($0.0625$ inset box) | Cost = $+50.0$ for adjacent nodes, swept box must maintain $0.3$m clearance |
| **Sweet Berry Bush** (`Blocks.SWEET_BERRY_BUSH`) | Slows to $20\%$ speed, damage on move | Cost = $+30.0$ (penalized, routes around) |
| **Water** (`Blocks.WATER`) | Slows movement, floating state | Cost = $+15.0$ (still), $+25.0$ (flowing) |
| **Campfire / Magma Block** | Stepping fire damage | Cost = $+40.0$ (penalized, avoid) |

---

## 4. Swept-Box Line-of-Sight Algorithm (`SweptBoxLOS.kt`)

### 4.1 Deficiency of Bresenham 1D Raycast
Vanilla / legacy pathfinders use 1D raycasts connecting block centers. When turning a $90^\circ$ corner past an obstacle at $(1, 64, 0)$ from $(0.5, 64.0, -0.5) \to (1.5, 64.0, 0.5)$:
- The 1D ray passes through $(1.0, 64.0, 0.0)$ (air). Bresenham declares the segment clear.
- But the player is $0.6$m wide! The player's bounding box sweeps through the corner of the solid block at $(1, 64, 0)$ by up to $0.3$m.
- The player snags on the corner and becomes permanently stuck.

### 4.2 Mathematical Derivation of Swept AABB Continuous Collision
Let player half-width $hw = 0.3$, player height $h = 1.8$.
At position $\vec{P}$, the player AABB is $B(\vec{P}) = [x_P - hw, y_P, z_P - hw] \times [x_P + hw, y_P + h, z_P + hw]$.

Checking whether $B(\vec{P}(t))$ intersects a static obstacle box $O = [O_{min}, O_{max}]$ for $t \in [0, 1]$ (where $\vec{P}(t) = \vec{A} + t(\vec{B} - \vec{A})$) is equivalent by the Minkowski Difference to checking whether the **line segment** $\vec{A} \to \vec{B}$ intersects the **expanded obstacle AABB**:
$$O_{exp} = [O_{minX} - hw, O_{minY} - h, O_{minZ} - hw] \times [O_{maxX} + hw, O_{maxY}, O_{maxZ} + hw]$$

#### Ray-AABB Intersection (Slab Method):
For ray $\vec{A} + t \vec{D}$ with $\vec{D} = \vec{B} - \vec{A}$:
For each axis $i \in \{x, y, z\}$:
$$t_{1, i} = \frac{O_{exp, min, i} - A_i}{D_i}, \quad t_{2, i} = \frac{O_{exp, max, i} - A_i}{D_i}$$
$$t_{near, i} = \min(t_{1, i}, t_{2, i}), \quad t_{far, i} = \max(t_{1, i}, t_{2, i})$$
$$t_{enter} = \max(t_{near, x}, t_{near, y}, t_{near, z}), \quad t_{exit} = \min(t_{far, x}, t_{far, y}, t_{far, z})$$

**Collision Condition**:
Collision occurs if and only if:
$$t_{enter} \le t_{exit} \quad \text{and} \quad t_{exit} \ge 0 \quad \text{and} \quad t_{enter} \le 1$$

#### Ground Support Validation:
For walking motion, in addition to ceiling/wall collision, the swept volume must maintain ground support. If the segment crosses an open pit (drop $> 1.0$m) without an intentional jump, line-of-sight is rejected.

---

## 5. Offline Unit Testing Strategy & Architecture

### 5.1 The `PathEnvironment` Decoupling Pattern
To satisfy `PROJECT.md`'s `Pathfinder` contract:
```kotlin
interface Pathfinder {
    fun findPath(world: World, start: Vec3d, goal: Vec3d, allowedRange: Double = 1.0): PathResult
    fun penalizeNode(pos: BlockPos, penalty: Float = 50.0f)
    fun clearPenalties()
}
```
We define an internal query interface `PathEnvironment` that abstracts world collision:
```kotlin
interface PathEnvironment {
    fun isSpaceEmpty(box: Box): Boolean
    fun getBlockCollisions(box: Box): List<Box>
    fun canStandAt(feetPos: BlockPos): Boolean
    fun getStandingSurfaceY(pos: BlockPos): Double?
    fun isHazard(pos: BlockPos): Boolean
    fun getHazardPenalty(pos: BlockPos): Float
}
```

In production:
- `WorldPathEnvironment(world: World)` implements `PathEnvironment` by querying `world.getBlockCollisions(null, box)` and `world.getBlockState(pos)`.
- `findPath(world: World, ...)` simply delegates to `findPath(WorldPathEnvironment(world), ...)`.

In offline unit tests:
- `TestWorldGrid` implements `PathEnvironment` directly!
- Zero mock frameworks required (no Mockito, no MockK).
- Runs in milliseconds, 100% deterministic, zero network or client dependencies.

### 5.2 `TestWorldGrid` Specification
```kotlin
class TestWorldGrid : PathEnvironment {
    private val solidBlocks = HashSet<BlockPos>()
    private val slabs = HashMap<BlockPos, SlabType>()
    private val stairs = HashMap<BlockPos, Pair<Direction, BlockHalf>>()
    private val fences = HashSet<BlockPos>()
    private val hazards = HashMap<BlockPos, HazardType>()
    private val customBoxes = ArrayList<Box>()

    fun setSolid(pos: BlockPos) { solidBlocks.add(pos.toImmutable()) }
    fun setSlab(pos: BlockPos, type: SlabType) { slabs[pos.toImmutable()] = type }
    fun setStairs(pos: BlockPos, facing: Direction, half: BlockHalf = BlockHalf.BOTTOM) {
        stairs[pos.toImmutable()] = facing to half
    }
    fun setFence(pos: BlockPos) { fences.add(pos.toImmutable()) }
    fun setHazard(pos: BlockPos, type: HazardType) { hazards[pos.toImmutable()] = type }
    fun addCustomBox(box: Box) { customBoxes.add(box) }

    fun addFlatPlatform(minX: Int, maxX: Int, minZ: Int, maxZ: Int, y: Int) {
        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                setSolid(BlockPos(x, y, z))
            }
        }
    }

    override fun getBlockCollisions(box: Box): List<Box> {
        val result = mutableListOf<Box>()
        // Return colliding boxes from solid blocks, slabs, stairs, fences, customBoxes
        // ...
        return result
    }

    override fun isSpaceEmpty(box: Box): Boolean {
        return getBlockCollisions(box).none { it.intersects(box) }
    }
    // ...
}
```

---

## 6. Comprehensive Test Case Inventory for Milestone 2 (`PathfinderTest.kt`)

### Tier 1: Unit & Numerical Invariants ($\ge 5$ tests per feature)
- **Feature 5 (Hitbox-Aware 3D A* Node Expansion)**:
  1. `testFlatCardinalPathExpansion`: Cardinal path straight North/South/East/West on flat platform finds shortest path.
  2. `testFlatDiagonalPathExpansion`: Diagonal path expands safely with $0.6$m width clearance on both sides.
  3. `testNarrow1BlockCorridorExpansion`: $1.0$m wide corridor bounded by walls: player ($0.6$m wide) walks down center without collision.
  4. `testObstacleAvoidanceAroundCenterPillar`: Straight line blocked by $1 \times 1$ pillar $\to$ pathfinder routes around pillar.
  5. `testHeuristicAdmissibility`: Euclidean 3D distance heuristic never overestimates actual distance.
- **Feature 6 (Swept-Box Line-of-Sight Check)**:
  1. `testSweptBoxCollinearClearPath`: Straight ray with no obstacles returns clear line-of-sight.
  2. `testSweptBoxDirectObstacleCollision`: Obstacle directly centered on path returns blocked line-of-sight.
  3. `testSweptBoxDiagonalCornerSnagPrevention`: Obstacle corner protruding into $0.6$m swept corridor blocks line-of-sight even when center 1D ray passes through air.
  4. `testSweptBoxWallGrazeClearance`: Path parallel to flat wall at distance $> 0.3$m does not snag.
  5. `testSweptBoxZeroLengthSegment`: Start equals goal returns true without division by zero.
- **Feature 7 (Vertical Traversal)**:
  1. `testBottomSlabSmoothAscentWithoutJump`: Bottom slab ($+0.5$m) stepped up smoothly without jump action.
  2. `testTopSlabAscentWithCeilingClearance`: Top slab ($+1.0$m surface) evaluated with ceiling clearance.
  3. `testStairAscentInFacingDirectionWithoutJump`: Facing stair ascended via sequential $0.5$m step-ups without jump.
  4. `testSafe1To3BlockDrops`: Safe drops (1, 2, 3 blocks) permitted with incremental step cost.
  5. `testParkour1BlockGap`: 1-block horizontal gap navigated with takeoff, airborne, and landing headroom.
- **Feature 8 (Dynamic Node Penalization)**:
  1. `testPenalizeNodeIncreasesCost`: Penalized node cost increases by specified penalty.
  2. `testPenalizedNodeForcesAlternateRoute`: High penalty ($\ge 50$) causes pathfinder to route around node.
  3. `testClearPenaltiesRestoresOriginalPath`: Clearing penalties restores original direct path.
  4. `testMultiplePenalizedNodesRerouting`: Consecutive penalized nodes force complete rerouting around corridor.
  5. `testPenaltyMapSpatialLookup`: Spatial hash lookups and cleanup execute cleanly.

### Tier 2: Boundary & Corner Cases ($\ge 5$ tests per feature)
- **Feature 5**:
  1. `testStartEqualsGoalZeroDistance`: Start pos == Goal pos returns 1-waypoint success immediately.
  2. `testGoalInsideSolidBlockRoutesToAdjacent`: Goal in solid block routes to closest stand within `allowedRange`.
  3. `testUnreachableGoalEnclosedRoomGracefulFailure`: Enclosed room returns `success = false` with `blockedReason != null` without infinite loop.
  4. `testMaxSearchNodeLimitExhaustion`: Huge open world terminates gracefully at max node budget.
  5. `testSubBlockFloatingPointCoordinatesSnap`: Decimal coordinates (e.g. $x=5.37, z=12.82$) snap cleanly to valid standing state.
- **Feature 6**:
  1. `testSweptBoxExactBoundaryContact`: Box edge at exactly $0.3000$m from block face handled without floating-point false collision.
  2. `testSweptBoxSubMillimeterPenetration`: $0.001$m overlap detected as collision.
  3. `testSweptBoxElevatedSlabTransition`: Continuous swept check across ascending slab.
  4. `testSweptBoxGroundDropoutDetection`: Swept check across open pit rejected for walking line-of-sight.
  5. `testSweptBoxExtremeDistanceStability`: 50m ray across open space executes stably without numeric overflow.
- **Feature 7**:
  1. `testFence15HeightBarrierRejectsJump`: Jump from same level over fence ($1.5$m) strictly rejected.
  2. `testHeadroom180BoundaryPasses179Fails`: $1.80$m clear height passes; $1.79$m clear height fails.
  3. `testJumpApexCeilingHeadroomConstraint`: 1-block ascent under 2.0m ceiling rejected (requires 2.5m apex clearance).
  4. `testDoubleSlabBehavesAsSolidFullBlock`: Double slab collision matches standard full block.
  5. `testCarpetPlusCeilingTrapdoorClearanceViolation`: Carpet ($+0.0625$) + ceiling trapdoor ($-0.1875$) leaves $1.75$m $\to$ blocked.
- **Feature 8**:
  1. `testPenalizeStartNodeHandledGracefully`: Penalizing start position does not crash search.
  2. `testMaximumPenaltySaturation`: Penalties clamped to prevent numeric overflow.
  3. `testNegativePenaltySanitized`: Negative penalty sanitized / clamped.
  4. `testPenaltyMemoryRetentionAcrossRepaths`: Penalty memory persists across multiple calls until explicitly cleared.

### Tier 3: Pairwise Combinations & Dynamics
- `testBottomSlabUnderLowCeiling`: Slab elevation under reduced ceiling.
- `testStairsImmediatelyIntoCornerTurn`: Vertical stair step directly into $90^\circ$ swept corner.
- `testFenceAdjacentToJumpDown`: Elevated fence with valid jump down landing.
- `testParkourGapWithCeilingClipping`: Gap jump under ceiling that clips apex $\to$ rejected.
- `testCactusAdjacentCorridorAvoidance`: Corridor next to cactus keeps $0.3$m safety margin.
- `testPenalizedStairsAlternateRoute`: Penalizing primary stair ascent forces pathfinder to find ramp or elevator.

### Tier 4: Real-World Workload Scenarios
- `testScenarioTreeForagingRouteAroundLeaves`: Navigating past leaf overhangs and tree trunks without corner snags.
- `testScenarioMultiElevationSlabsAndStairs`: Traversing natural hill terrain composed of mixed dirt, slabs, and stairs.
- `testScenarioParkourAcrossSteppingStones`: Multi-gap parkour course across water/lava.
- `testScenarioDynamicObstacleUnstuckRepath`: Node penalized during simulated stuck condition $\to$ immediate alternate route.
- `testScenarioHazardousLavaFieldNavigation`: Winding path through a field of lava pits and cacti.

### Tier 5: Adversarial & Stress Testing
- `test10000NodeStressTest`: Search across huge 3D maze terminates in $< 50$ms.
- `testNaNAndInfinityCoordinatesSanitization`: Degenerate coordinates rejected without process crash.
- `testChunkBoundaryCrossingCoordinates`: Traversal crossing $x = 0, 15, 16$ and $z = -1, 0, 1$.

---

## 7. Recommended Next Steps for Implementation Team

1. Implement `com.github.foragerhelper.path.SweptBoxLOS.kt` first, using the exact Minkowski Slab method derived in §4.2.
2. Implement `com.github.foragerhelper.path.NodePenaltyMap.kt` for spatial node memory.
3. Implement `com.github.foragerhelper.path.Pathfinder.kt` with `PathEnvironment` decoupling as specified in §5.1.
4. Author `src/test/kotlin/com/github/foragerhelper/path/PathfinderTest.kt` with `TestWorldGrid` executing all 5 Tiers.
5. Verify 100% pass via `gradlew test` with 0 failures and 0 errors.
