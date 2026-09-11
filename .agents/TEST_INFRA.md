# E2E Test Infra: ForagerHelper Core Navigation Rewrite

## Test Philosophy
- Requirement-driven, opaque-box testing derived directly from `ORIGINAL_REQUEST.md` and module contracts.
- Independent decomposition by feature area and behavioral tiers (Tiers 1-4).
- Methodology: Category-Partition + Boundary Value Analysis (BVA) + Pairwise Combinatorial Testing + Real-World Workloads.
- Progressive testability: Unit & integration test runners execute headless offline via Gradle (`test` task), ensuring zero dependency on active graphical Minecraft windows for CI verification.

---

## Feature Inventory & Test Coverage Mapping
| # | Feature | Requirement Source | Tier 1 (Unit) | Tier 2 (Boundaries) | Tier 3 (Pairwise) | Tier 4 (Workload) |
|---|---------|-------------------|:-------------:|:-------------------:|:-----------------:|:-----------------:|
| 1 | Render-Frame Camera Panning | R1 §1 | 5 | 5 | ✓ | ✓ |
| 2 | Spring Angle Smoothing | R1 §2 | 5 | 5 | ✓ | ✓ |
| 3 | Mouse Sensitivity GCD Quantization | R1 §3 | 5 | 5 | ✓ | ✓ |
| 4 | Natural Head Movement & Focus | R1 §4 | 5 | 5 | ✓ | ✓ |
| 5 | Hitbox-Aware 3D A* Node Expansion | R2 §1 | 5 | 5 | ✓ | ✓ |
| 6 | Swept-Box Line-of-Sight Check | R2 §2 | 5 | 5 | ✓ | ✓ |
| 7 | Vertical Traversal (Slabs/Stairs/Jumps) | R2 §3 | 5 | 5 | ✓ | ✓ |
| 8 | Dynamic Node Penalization | R2 §4 | 5 | 5 | ✓ | ✓ |
| 9 | Multi-Tier Unstuck Maneuvers | R2 §4 | 5 | 5 | ✓ | ✓ |
| 10 | NavigationTarget Hierarchy | R3 §1 | 5 | 5 | ✓ | ✓ |
| 11 | Concrete Targets (Block/Entity/Pos) | R3 §1 | 5 | 5 | ✓ | ✓ |
| 12 | Pluggable TargetScanners | R3 §2 | 5 | 5 | ✓ | ✓ |
| 13 | Target Lifecycle & Focus Dispatch | R3 §2 | 5 | 5 | ✓ | ✓ |
| 14 | Decoupled WASD Vector Translation | R4 §1 | 5 | 5 | ✓ | ✓ |
| 15 | Movement Controller Lifecycle | R4 §1 | 5 | 5 | ✓ | ✓ |
| 16 | StatusHud & Gizmo Integration | R4 §3 | 5 | 5 | ✓ | ✓ |
| 17 | Config & Command Compatibility | R4 §3 | 5 | 5 | ✓ | ✓ |

---

## Test Architecture
- **Test Runner**:
  Command: `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"`
  Semantics: All tests must execute with exit code 0, reporting 0 failures and 0 errors.
- **Directory Layout**:
  - `src/test/kotlin/com/github/foragerhelper/rotation/`
    - `RotationEngineTest.kt` (Spring smoothing, frame delta-time, GCD compliance)
  - `src/test/kotlin/com/github/foragerhelper/path/`
    - `PathfinderTest.kt` (3D A*, swept-box LOS, vertical traversal, penalization)
  - `src/test/kotlin/com/github/foragerhelper/target/`
    - `TargetTest.kt` (Target types, focus points, reach validation, scanning)
  - `src/test/kotlin/com/github/foragerhelper/movement/`
    - `MovementControllerTest.kt` (WASD vector projection, unstuck triggering, lifecycle)
  - `src/test/kotlin/com/github/foragerhelper/e2e/`
    - `E2ENavigationScenarioTest.kt` (Full synthetic integration scenarios)

---

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | Tree Foraging Route with Corner Turn | F5, F6, F7, F10, F11, F14, F15 | High |
| 2 | Mob Tracking with Dynamic Repath | F4, F5, F8, F11, F13, F14 | High |
| 3 | Slabs, Stairs and Parkour Gap Traversal | F5, F6, F7, F14 | High |
| 4 | Stagnation & Multi-Tier Unstuck Recovery | F8, F9, F14, F15 | High |
| 5 | High-Refresh Render Frame Camera Interpolation | F1, F2, F3, F4 | High |
| 6 | Manual `/forageroute` Exact Waypoint Navigation | F11, F14, F15, F17 | Medium |

---

## Coverage Thresholds
- Tier 1: $\ge 5$ test cases per feature ($\ge 85$ test cases)
- Tier 2: $\ge 5$ boundary/corner cases per feature ($\ge 85$ test cases)
- Tier 3: Pairwise combinations of major feature interactions ($\ge 17$ test cases)
- Tier 4: $\ge 6$ real-world workload application scenarios
- Tier 5: Adversarial edge cases generated during coverage hardening
