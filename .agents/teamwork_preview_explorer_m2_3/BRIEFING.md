# BRIEFING — 2026-09-11T13:33:00Z

## Mission
Design the complete architecture and implementation specification for NodePenaltyMap.kt (dynamic spatial node penalization, decay timers, cost integration into A* g-score, and unstuck rerouting).

## 🔒 My Identity
- Archetype: teamwork_preview_explorer
- Roles: Node Penalization & Unstuck Specialist
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_3
- Original parent: c19b23eb-08dd-4cd6-b5a9-8f12e36c1a4c
- Milestone: M2 (Hitbox-Aware Pathfinder)

## 🔒 Key Constraints
- Read-only investigation — do NOT implement production source code directly
- Write only to our own directory: .agents/teamwork_preview_explorer_m2_3/
- Pure Kotlin data structures with zero unnecessary tick overhead
- API contract must strictly conform to Pathfinder interface in PROJECT.md
- Address the infinite stuck loop in WalkController.kt:118-129

## Current Parent
- Conversation ID: c19b23eb-08dd-4cd6-b5a9-8f12e36c1a4c
- Updated: 2026-09-11T13:37:00Z

## Investigation State
- **Explored paths**: `ORIGINAL_REQUEST.md`, `PROJECT.md`, `DISPATCH.md`, `WalkController.kt:118-129, 207-215`, `AStarPathfinder.kt:47-75`, `DebugWorldOverlay.kt`, `TEST_INFRA.md`, peer explorer reports (`explorer_m2_1`, `explorer_m2_2`, `survey_1`).
- **Key findings**:
  - Legacy `WalkController` resets `stuckTicks` upon repathing, but A* deterministically reproduces the exact same blocked route due to lack of dynamic penalty memory.
  - Primitive `BlockPos.asLong()` lookups eliminate garbage collection pressure during hot A* search loops.
  - Spatial diffusion ($R=1, \alpha=0.5$) creates an obstacle repulsion field that prevents corner-shimming.
  - Linear TTL decay (default 20s) clears temporary obstacles cleanly.
  - Cost integration into $g$-score mathematically preserves A* admissibility while compelling detours.
- **Unexplored areas**: None for M2.3 scope.

## Key Decisions Made
- Structured `NodePenaltyMap` as an interface with `DefaultNodePenaltyMap` implementation.
- Zero-allocation primitive 64-bit lookup method `getPenalty(posLong: Long, currentTimeMs: Long): Float`.
- Linear decay over 20s TTL as default decay model.
- Chebyshev radius-1 diffusion with 0.5x falloff.
- Multi-tier unstuck recovery protocol coordinating `MovementController`, `UnstuckHandler`, and `Pathfinder`.
- Comprehensive 15-case test suite covering Tiers 1-4.

## Artifact Index
- `DISPATCH.md` — Received dispatch instructions
- `BRIEFING.md` — Persistent situational awareness
- `progress.md` — Liveness heartbeat
- `report.md` — Comprehensive architectural specification
- `handoff.md` — 5-component handoff report for orchestrator and implementers
