# Execution Plan — ForagerHelper Core Navigation Rewrite

## Objectives
Execute a clean-slate rewrite and re-architecture of the core navigation, rotation, and target-selection engine for ForagerHelper Fabric 1.21.11 Minecraft mod into 4 cleanly decoupled modules while preserving existing UI, keybinds, and HUD shell.

## Phases

### Phase 0: Survey & Codebase Exploration
- Spawn 3 parallel Explorers to thoroughly investigate:
  1. Explorer 1: Current navigation, pathfinding, movement, and rotation controllers (`MovementController`, `CameraController`, `ForagerNavigator`, etc.), identifying coupling, jitter causes, and math.
  2. Explorer 2: Existing UI, Keybinds, Config (`HelperConfig`, `HelperOptionsScreen`), HUD (`StatusHud`), Render overlay (`DebugWorldOverlay`), commands (`/forageroute`), and Fabric lifecycle hooks.
  3. Explorer 3: Build environment, Gradle setup, dependencies, Minecraft Fabric 1.21.11 mappings/APIs (Vec3d, Box, BlockPos, ClientPlayerEntity, RenderTickCounter, etc.), and unit test capabilities.

### Phase 1: Global Architecture & Specification
- Synthesize explorer findings into `PROJECT.md` at project root.
- Define feature inventory, module boundaries, interface contracts (`RotationEngine`, `Pathfinder`, `NavigationTarget`, `MovementController`), and code layout.
- Initialize E2E Testing architecture (`TEST_INFRA.md`).

### Phase 2: Implementation & Verification (Milestones 1 to 4)
- **M1 (R1)**: Standalone Humanized Rotation Engine (`rotation/RotationEngine.kt`).
- **M2 (R2)**: Hitbox-Aware 3D A* Pathfinder (`path/Pathfinder.kt`).
- **M3 (R3)**: Universal Target Framework (`target/`).
- **M4 (R4)**: Decoupled Movement Controller (`movement/MovementController.kt`) and UI/HUD/Command Integration.

### Phase 3: Final Verification & Adversarial Coverage Hardening
- Complete test suite verification (Tier 1-4).
- Adversarial hardening with Challenger (Tier 5).
- Forensic audit clean verdict verification.
- Report completion to Sentinel.
