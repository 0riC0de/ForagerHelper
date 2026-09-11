# BRIEFING — 2026-09-11T13:33:15Z

## Mission
Clean-slate rewrite and re-architecture of the core navigation, rotation, and target-selection engine for ForagerHelper Fabric 1.21.11 Minecraft mod into 4 cleanly decoupled modules (RotationEngine, Pathfinder, Target Framework, MovementController) while preserving UI, keybinds, and HUD.

## 🔒 My Identity
- Archetype: orchestrator
- Roles: orchestrator, user_liaison, human_reporter, successor
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\orchestrator_1
- Original parent: parent (880f56e5-a85d-45de-a590-22f9f8ebdb95)
- Original parent conversation ID: 880f56e5-a85d-45de-a590-22f9f8ebdb95

## 🔒 My Workflow
- **Pattern**: Project Pattern (Dual Track: Implementation + E2E Testing)
- **Scope document**: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md
1. **Decompose**: Survey (3 parallel Explorers) -> Feature Inventory & Milestones in PROJECT.md -> Decompose into milestones
2. **Dispatch & Execute**:
   - Implementation Track: Milestone sub-orchestrator or Explorer -> Worker -> Reviewer -> Challenger -> Auditor iteration loop
   - E2E Testing Track: E2E Testing Orchestrator / test writers
3. **On failure** (in this order):
   - Retry: nudge stuck agent or re-send task
   - Replace: spawn fresh agent with partial progress
   - Skip: proceed without (only if non-critical; auditor is NEVER skippable)
   - Redistribute: split stuck agent's remaining work
   - Redesign: re-partition decomposition
   - Escalate: (top-level orchestrator redesigns, does not escalate)
4. **Succession**: Managed via state persistence; orchestrator drives milestone progress.
- **Work items**:
  1. Survey & Codebase Exploration [done]
  2. Project Architecture & Specification (`PROJECT.md` & `TEST_INFRA.md`) [done]
  3. M1: Rotation Engine (R1) [DONE - 55 tests pass, Auditor CLEAN]
  4. M2: Hitbox-Aware 3D Pathfinder (R2) [in-progress: 3 Explorers running]
  5. M3: Universal Target Framework (R3) [pending]
  6. M4: Decoupled Movement Controller (R4) & Integration [pending]
  7. Final Milestone: E2E Verification & Adversarial Hardening [pending]
- **Current phase**: Phase 3 (Milestone 2 - Hitbox-Aware 3D A* Pathfinder)
- **Current focus**: 3 parallel Explorers mapping 3D A* search, swept-box LOS, and node penalization memory

## 🔒 Key Constraints
- NEVER write, modify, or create source code files directly.
- NEVER run build/test commands yourself — require workers to do so.
- NEVER investigate or explore the problem at the code level — dispatch Explorers for technical investigation.
- You MAY use file-editing tools ONLY for metadata/state files (.md) in your .agents/ folder.
- AUDIT ENFORCEMENT: Binary veto. Violation means failure unconditionally.
- Never reuse a subagent after it has delivered its handoff — always spawn fresh.
- Always include ORIGINAL_REQUEST.md path in every dispatch.

## Current Parent
- Conversation ID: 880f56e5-a85d-45de-a590-22f9f8ebdb95
- Updated: 2026-09-11T12:21:19Z

## Key Decisions Made
- M1 officially complete and certified CLEAN.
- M2 dispatched to 3 specialized Explorers for 3D A*, Swept-Box LOS, and Dynamic Node Penalization.

## Team Roster
| Agent | Type | Work Item | Status | Conv ID |
|-------|------|-----------|--------|---------|
| explorer_m2_1 | teamwork_preview_explorer | M2 3D A* Search & Verticals | in-progress | 9281c456-fbef-4b2e-9150-eead80d157ec |
| explorer_m2_2 | teamwork_preview_explorer | M2 Swept-Box LOS Smoothing | in-progress | 8c50a430-fac2-409f-91fc-2773f8f79dea |
| explorer_m2_3 | teamwork_preview_explorer | M2 Node Penalization & Unstuck | in-progress | 32dea19c-f9d4-46d8-900b-e125b80b3f48 |

## Succession Status
- Succession required: no
- Spawn count: 20
- Pending subagents: 9281c456-fbef-4b2e-9150-eead80d157ec, 8c50a430-fac2-409f-91fc-2773f8f79dea, 32dea19c-f9d4-46d8-900b-e125b80b3f48
- Predecessor: none
- Successor: none

## Active Timers
- Heartbeat cron: task-183
- Safety timer: none
- On succession: kill all timers before spawning successor
- On context truncation: run `manage_task(Action="list")` — re-create if missing

## Artifact Index
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md — Authoritative User Request
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md — Global Architecture & Feature Inventory
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\TEST_INFRA.md — E2E Test Suite Architecture
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\orchestrator_1\GATE_STATUS.md — Milestone Gate Status
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\orchestrator_1\DISPATCH.md — Incoming Dispatch Record
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\orchestrator_1\BRIEFING.md — Working memory & identity
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\orchestrator_1\progress.md — Progress & liveness tracking
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\orchestrator_1\handoff.md — Handoff documentation
- c:\Users\משתמש\source\repos\ForagerHelper\.agents\orchestrator_1\plan.md — Detailed execution plan
