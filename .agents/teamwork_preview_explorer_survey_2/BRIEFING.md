# BRIEFING — 2026-09-11T12:22:30Z

## Mission
Map all existing target scanning/selection, HelperConfig, HelperOptionsScreen, StatusHud, DebugWorldOverlay, keybinds, and commands (/forageroute), and document integration requirements for R3 Universal Target Framework.

## 🔒 My Identity
- Archetype: teamwork_preview_explorer
- Roles: Target Framework & UI/HUD/Config Explorer
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_2
- Original parent: c19b23eb-08dd-4cd6-b5a9-8f12e36c1a4c
- Milestone: Survey & Architectural Mapping

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Map existing target scanning/selection, HelperConfig, HelperOptionsScreen, StatusHud, DebugWorldOverlay, keybinds, and commands (/forageroute)
- Document integration points and requirements for R3 (Universal Target Framework: BlockTarget, EntityTarget, PositionTarget, TargetScanner)
- Preserve UI/HUD/Keybinds shell while enabling clean integration with new architecture

## Current Parent
- Conversation ID: c19b23eb-08dd-4cd6-b5a9-8f12e36c1a4c
- Updated: 2026-09-11T12:26:30Z

## Investigation State
- **Explored paths**:
  - `src/main/kotlin/foraginghelpermod/ForagingHelperClient.kt`
  - `src/main/kotlin/foraginghelpermod/client/HelperConfig.kt`
  - `src/main/kotlin/foraginghelpermod/client/InputController.kt`
  - `src/main/kotlin/foraginghelpermod/client/ManualRouteCommand.kt`
  - `src/main/kotlin/foraginghelpermod/client/ModKeyBindings.kt`
  - `src/main/kotlin/foraginghelpermod/client/hud/StatusHud.kt`
  - `src/main/kotlin/foraginghelpermod/client/hud/DebugWorldOverlay.kt`
  - `src/main/kotlin/foraginghelpermod/client/path/WalkController.kt`
  - `src/main/kotlin/foraginghelpermod/client/path/ChopController.kt`
  - `src/main/kotlin/foraginghelpermod/client/path/EtherWarpPlanner.kt`
  - `src/main/kotlin/foraginghelpermod/client/path/AStarPathfinder.kt`
  - `src/main/kotlin/foraginghelpermod/client/scan/TreeCluster.kt`
  - `src/main/kotlin/foraginghelpermod/client/scan/TreeScanner.kt`
  - `src/main/kotlin/foraginghelpermod/client/scan/TreeScorer.kt`
  - `src/main/kotlin/foraginghelpermod/client/ui/HelperOptionsScreen.kt`
  - `src/main/kotlin/foraginghelpermod/client/ui/UiTheme.kt`
- **Key findings**:
  - Entire target pipeline is currently hardcoded to tree logs (`TreeCluster`) with `BlockPos` targets.
  - `StatusHud` and `DebugWorldOverlay` directly query `targetLog` and tree cluster size/counts.
  - `HelperConfig` (10 fields) and `HelperOptionsScreen` (9 checkboxes) can be 100% preserved.
  - R3 Universal Target Framework design defined: `NavigationTarget` with `BlockTarget`, `EntityTarget`, `PositionTarget`, `TargetScanner<T>`, and `TargetManager`.
  - Gradle `compileKotlin` verified building cleanly on JDK 23 in 15 seconds.
- **Unexplored areas**: None within survey scope.

## Key Decisions Made
- Designed polymorphic status reporting (`describeStatus()`) and debug rendering (`renderDebug()`) on `NavigationTarget` to completely decouple `StatusHud` and `DebugWorldOverlay` from concrete target types.
- Preserved all existing `HelperConfig` property accessors for complete backward compatibility.

## Artifact Index
- DISPATCH.md — Dispatch instructions and prompt log
- BRIEFING.md — Situational awareness and working memory
- progress.md — Liveness heartbeat and milestone tracker
- report.md — Comprehensive survey report on R3 and UI/HUD/Config integration
- handoff.md — 5-component handoff report for the team

