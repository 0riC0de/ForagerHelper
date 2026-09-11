# Handoff Report: 3D A* Pathfinder Architecture & Vertical Traversal Specification

**Subagent**: `teamwork_preview_explorer_m2_1`  
**Working Directory**: `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_1`  
**Role**: 3D A* Architecture & Vertical Traversal Specialist  
**Milestone**: M2 (Hitbox-Aware Pathfinder)  
**Target Specification File**: `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_1\report.md`  
**Date**: 2026-09-11  

---

## 1. Observation

1. **Legacy Standing Surface Check**:
   In `src/main/kotlin/foraginghelpermod/client/path/AStarPathfinder.kt:80-88`:
   ```kotlin
   fun canStandAt(world: ClientWorld, feet: BlockPos): Boolean {
       if (!isAirLike(world, feet) || !isAirLike(world, feet.up())) return false
       if (!world.getFluidState(feet).isEmpty || !world.getFluidState(feet.up()).isEmpty) return false
       val ground = feet.down()
       val groundState = world.getBlockState(ground)
       if (groundState.isAir) return false
       if (TreeScanner.isLogLike(groundState)) return false
       return !groundState.getCollisionShape(world, ground).isEmpty
   }
   ```
   And lines 119-123:
   ```kotlin
   private fun isAirLike(world: ClientWorld, pos: BlockPos): Boolean {
       val state = world.getBlockState(pos)
       if (TreeScanner.isLogLike(state)) return false
       return state.getCollisionShape(world, pos).isEmpty
   }
   ```
   *Observed Effect*: Bottom slabs, stairs, carpets, and snow layers possess non-empty collision shapes (`getCollisionShape(world, pos).isEmpty == false`). When placed at `feet`, `isAirLike(feet)` returns `false`. Consequently, slabs and stairs are treated as impassable walls.

2. **Legacy Jump Up Ceiling Check**:
   In `src/main/kotlin/foraginghelpermod/client/path/AStarPathfinder.kt:242-246`:
   ```kotlin
   val up = flat.up()
   if (!tooFar(up, origin, maxRange) && canStandAt(world, up) &&
       isAirLike(world, pos.up(2))) {
       result.add(up.toImmutable() to 1.45)
   }
   ```
   *Observed Effect*: When jumping up 1 block, it checks `isAirLike(world, pos.up(2))`, but does not verify headroom at the jump apex trajectory or destination ceiling at `flat.up(2)`. In a 2-block high corridor with an upper ceiling, a jump up causes the player to collide head-first into the ceiling block at $Y+2.0$, killing vertical and horizontal velocity and aborting the jump.

3. **Legacy Diagonal Corner Clipping**:
   In `src/main/kotlin/foraginghelpermod/client/path/AStarPathfinder.kt:269-277`:
   ```kotlin
   for ((dx, dz) in arrayOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
       val diagonal = pos.add(dx, 0, dz)
       val sideA = pos.add(dx, 0, 0)
       val sideB = pos.add(0, 0, dz)
       if (!tooFar(diagonal, origin, maxRange) && canStandAt(world, diagonal) &&
           canStandAt(world, sideA) && canStandAt(world, sideB)) {
           result.add(diagonal.toImmutable() to 1.414)
       }
   }
   ```
   *Observed Effect*: If `sideA` or `sideB` has a solid wall at head height (e.g. wall at $Y$ or $Y+1$) or if path smoothing connects two waypoints across an inner corner, the player's $0.6$m wide bounding box ($[-0.3, +0.3]$ from center) clips up to $0.3$m into the solid block corner, causing the player to snag against the wall.

4. **Target Interface Contract in Scope**:
   In `.agents/PROJECT.md:95-114`:
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

5. **Build Baseline Command & Result**:
   - Command: `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"`
   - Result: `BUILD SUCCESSFUL in 15s, exit code 0`.
   - Test command: `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"`
   - Result: `BUILD SUCCESSFUL in 15s, exit code 0`.

---

## 2. Logic Chain

1. **Derivation of Ground Surface and Slabs (linking Observation 1)**:
   - Observation 1 proved that testing `isAirLike(feet)` fails on bottom slabs and stairs because their collision box resides in the lower half of the block (`[0.0, 0.5]`).
   - By querying `shape = state.getCollisionShape(world, groundPos)` and extracting `maxY = shape.getMax(Direction.Axis.Y)`, the exact physical standing surface elevation $Y_{\text{stand}} = \text{groundPos.y} + maxY$ is determined.
   - For a bottom slab at $Y=64$, $maxY = 0.5 \implies Y_{\text{stand}} = 64.5$.
   - The player's bounding box $\text{Box}(x-0.3, 64.5, z-0.3, x+0.3, 66.3, z+0.3)$ does not intersect the slab $[64.0, 64.5]$.
   - Because vanilla player step height is $0.6$m (`generic.step_height`), any step transition with $|\Delta Y| \le 0.6$m is executed as a continuous auto-step without jumping.

2. **Derivation of Jump Up Clearance and Apex Bonk Prevention (linking Observation 2)**:
   - Observation 2 showed that jumping up 1 block under a 2-block ceiling bonks the player's head.
   - A jump in Minecraft launches the player on an arc peaking at $+1.25$m above takeoff. With player height $1.8$m, total head height reaches $Y_{\text{takeoff}} + 3.05$m.
   - To guarantee ceiling clearance:
     a) Takeoff column must have $\ge 2.0$m vertical clearance (`takeoff.up(2)` clear).
     b) Landing column must have $\ge 1.8$m clearance above landing elevation $Y_{\text{land}}$.
     c) The horizontal swept volume across takeoff and landing from $Y_1 + 1.8$ to $Y_1 + 2.0$ must be completely empty (`world.isSpaceEmpty(ApexBox) == true`).

3. **Derivation of Diagonal Double-Pillar Clearance (linking Observation 3)**:
   - Observation 3 showed that cutting corners diagonally clips wall edges.
   - For any diagonal transition from $(X_0, Z_0)$ to $(X_0 + dx, Z_0 + dz)$, the player's $0.6 \times 1.8$m hitbox extends $0.3$m into both orthogonal columns $(X_0 + dx, Z_0)$ and $(X_0, Z_0 + dz)$.
   - Requiring both orthogonal pillars to satisfy `world.isSpaceEmpty(pillarBox) == true` strictly guarantees zero collision along the diagonal move.

4. **Admissible Heuristic Formulation**:
   - Let Euclidean distance be $D = \sqrt{\Delta x^2 + \Delta y^2 + \Delta z^2}$.
   - All physical move costs $\ge$ physical Euclidean distance: cardinal walk ($1.0 \ge 1.0$), diagonal walk ($1.414 \ge 1.414$), slab step-up ($1.25 \ge 1.118$), jump-up 1 block ($1.45 \ge 1.414$), safe drop ($2.0 \ge 1.414$), parkour gap ($3.0 \ge 2.0$).
   - Therefore, $h(n) = D + \max(0.0, y_{\text{goal}} - y) \times 0.1$ is strictly admissible ($h(n) \le h^*(n)$).

5. **Interface Architecture (linking Observation 4 & 5)**:
   - The architecture implements `Pathfinder` as an interface with `DefaultPathfinder` companion delegate.
   - Uses a 64-bit compact coordinate key `packKey(x, standingY, z)` to prevent hash collisions between full blocks and half slabs.
   - Integrates with `SweptBoxLOS` for path smoothing and `NodePenaltyMap` for dynamic obstacle avoidance.

---

## 3. Caveats

1. **Entities & Dynamic Hitboxes**:
   - `world.isSpaceEmpty(box)` or `world.getBlockCollisions(null, box)` evaluates block collisions. Dynamic entities (boats, shulkers, minecarts, players, mobs) can also obstruct movement. The `MovementController` unstuck loop handles dynamic entities by calling `Pathfinder.penalizeNode(stuckPos)` when obstructed.
2. **Open Iron Bars, Glass Panes, and Walls**:
   - Thin collision shapes (iron bars, glass panes, fences) have non-empty voxel shapes that are smaller than a full block. `getBlockCollisions` correctly catches them if they overlap the player's $0.6 \times 1.8$m box.
3. **Fluids & Swimming**:
   - The current specification focuses on ground traversal and safe water avoidance. Water swimming (swimming mechanics, breath depletion, 3D underwater swimming) is out of scope for land foraging and is treated as a high-cost deterrent.

---

## 4. Conclusion

The architectural design for `Pathfinder.kt` is fully specified in `report.md`. It directly resolves all legacy pathfinding defects:
- Slabs and stairs are recognized and traversed smoothly as auto-steps ($\le 0.6$m) without jumping.
- 1-block jumps are protected by strict $2.0$m apex ceiling headroom verification, eliminating ceiling bonks.
- 1 to 3 block safe drops are verified using vertical fall column swept boxes.
- 1-block parkour jumps across chasms are validated with continuous headroom.
- Diagonal transitions enforce double orthogonal pillar clearance, eliminating diagonal corner snags.
- Data structures feature compact 64-bit coordinate packing, tie-breaking priority queue, and admissible Euclidean heuristic.
- Contracts match `PROJECT.md:95-114` exactly.

---

## 5. Verification Method

### 5.1 Compilation Verification
Run from command line or terminal:
```powershell
cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"
```
*Expected*: Exit code 0, 0 compilation errors.

### 5.2 Unit & Integration Test Suite Verification
Run from command line or terminal:
```powershell
cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"
```
*Expected*: Exit code 0, all tests pass.

### 5.3 Behavioral Test Invariants to Implement in `PathfinderTest.kt`:
1. `testSlabWalkNoJump`: Start at $(0, 64, 0)$, bottom slab at $(1, 64, 0)$ ($Y_{\text{stand}} = 64.5$). Path is found, transition is `STEP_UP`, cost is $1.25$, no jump flag.
2. `testJumpApexCeilingHeadroomBonkPrevention`: Start at $(0, 64, 0)$, goal at $(1, 65, 0)$. Solid block ceiling at $(0, 66, 0)$ ($Y=66.0$, headroom $2.0$m above feet). Jump up must be **rejected**; when ceiling is removed, jump up **succeeds**.
3. `testSafeDrop3Blocks`: Start at $(0, 67, 0)$, drop to $(1, 64, 0)$ ($H=3$ blocks). Entire vertical column clear $\implies$ succeeds. Protruding block at $(1, 65, 0) \implies$ rejected.
4. `testDoublePillarDiagonalCornerClearance`: Start at $(0, 64, 0)$, goal at $(1, 64, 1)$. If $(1, 64, 0)$ is a solid wall, diagonal move is rejected; path routes around $(0, 64, 1) \to (1, 64, 1)$.
5. `testHazardAvoidanceLavaAndBerries`: Ground path through sweet berry bush or lava is rejected in favor of a detour.

### 5.4 Invalidation Conditions
This specification is invalidated if:
- Slabs or stairs fail to yield valid paths or cause the player to jump when stepping up $\le 0.6$m.
- A player bonks their head against a ceiling during an ascent in a 2-block high corridor.
- Diagonal movement clips into adjacent wall blocks.
