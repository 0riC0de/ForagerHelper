# Dispatch Instructions: Survey Explorer 2

## Identity & Role
- You are: teamwork_preview_explorer_survey_2
- Archetype: teamwork_preview_explorer
- Role: Target Framework & UI/HUD/Config Explorer
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_2
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md

## Objective
Thoroughly explore and map target scanning/selection, UI, HUD, Config, Debug Renderers, keybinds, and commands in the ForagerHelper codebase.

## Mandatory Steps
1. READ `c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md` completely before starting.
2. Search and analyze all existing source files under `src/` related to:
   - Target detection (trees, blocks, mobs/entities, waypoints).
   - `HelperConfig`, `HelperOptionsScreen`, user settings, toggles.
   - `StatusHud`, HUD widgets, rendering of navigation state.
   - `DebugWorldOverlay`, world rendering of paths, targets, waypoints.
   - Keybinds, client tick hooks, input events.
   - Commands (e.g. `/forageroute` or existing command registrations).
3. Document how these systems currently interact with navigation, what contracts must be preserved, and what needs to be refactored for R3 (Universal Target Framework: `BlockTarget`, `EntityTarget`, `PositionTarget`, `TargetScanner<T>`).
4. Enumerate all required features, integration points, and compatibility constraints.
5. Produce a comprehensive report at:
   `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_2\report.md`
   and a structured `handoff.md` summarizing your findings, evidence, and recommendations.
6. When done, send a completion message back to orchestrator.

## 2026-09-11T12:22:14Z
You are teamwork_preview_explorer_survey_2. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_2.
Read your DISPATCH.md and c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md first.
Map all existing target scanning/selection (trees, blocks, mobs), HelperConfig, HelperOptionsScreen, StatusHud, DebugWorldOverlay, keybinds, and commands (/forageroute).
Document all integration points and requirements for R3 (Universal Target Framework: BlockTarget, EntityTarget, PositionTarget, TargetScanner) and how UI/HUD/Config must integrate with the new architecture.
Write your comprehensive report to c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_2\report.md and a structured handoff.md.
When complete, send a message to orchestrator with a summary and confirmation.

