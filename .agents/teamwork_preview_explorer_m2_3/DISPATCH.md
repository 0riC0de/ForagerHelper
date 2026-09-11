# Dispatch Instructions: M2 Explorer 3 (Dynamic Node Penalization & Unstuck Integration)

## Identity & Role
- You are: teamwork_preview_explorer_m2_3
- Archetype: teamwork_preview_explorer
- Role: Node Penalization & Unstuck Specialist
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_3
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md
- Scope Document: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md

## Objective
Design the complete architecture and implementation specification for `src/main/kotlin/com/github/foragerhelper/path/NodePenaltyMap.kt` and its integration with `Pathfinder.kt`.

## Mandatory Steps
1. Read `ORIGINAL_REQUEST.md` and `PROJECT.md` completely.
2. Read survey reports detailing the infinite stuck loop in `WalkController.kt:118-129` where A* regenerated the identical blocked path on stagnation.
3. Formulate the dynamic node penalization memory:
   - Data structure tracking penalized `BlockPos` with floating-point penalty weights and expiration timestamps.
   - Cost integration into A* $g$-score: $g(\text{next}) = g(\text{curr}) + \text{stepCost} + \text{penaltyMap.getPenalty(pos)}$.
   - Spatial penalty diffusion (penalizing the stuck node and optionally a 1-block radius with decaying weights) to route around dynamic obstacles like closed fence gates, wandering mobs, or block changes.
   - Penalty decay/cleanup mechanism: time-to-live (e.g. 15–30 seconds) or LRU cache eviction.
4. Define the API contract conforming to `PROJECT.md:95-109`:
   - `penalizeNode(pos: BlockPos, penalty: Float = 50.0f)`
   - `clearPenalties()`
   - `cleanupExpired(currentTimeMillis: Long)`
5. Formulate testing strategies (demonstrating that repathing after penalization selects a detour rather than repeating the blocked route).
6. Write your report to `report.md` and `handoff.md`. Notify orchestrator when done.

## 2026-09-11T13:33:00Z
You are teamwork_preview_explorer_m2_3. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_3.
Read your DISPATCH.md, c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md, and c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md.
Design the complete architecture and implementation specification for NodePenaltyMap.kt (dynamic spatial node penalization, decay timers, cost integration into A* g-score, and unstuck rerouting).
Write your report to report.md and handoff.md and notify orchestrator when done.
