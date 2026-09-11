# Dispatch Instructions: M2 Explorer 1 (3D A* Search & Vertical Traversal)

## Identity & Role
- You are: teamwork_preview_explorer_m2_1
- Archetype: teamwork_preview_explorer
- Role: 3D A* Architecture & Vertical Traversal Specialist
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_1
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md
- Scope Document: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md

## Objective
Design the complete architecture and implementation specification for the Hitbox-Aware 3D A* Pathfinder in `src/main/kotlin/com/github/foragerhelper/path/Pathfinder.kt`.

## Mandatory Steps
1. Read `ORIGINAL_REQUEST.md` and `PROJECT.md` completely.
2. Read `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_1\report.md` regarding past flaws in `AStarPathfinder.kt`.
3. Design the 3D node expansion and validation:
   - Player bounding box: $0.6 \times 0.6 \times 1.8$m ($[-0.3, +0.3]$ around center).
   - Collision validation against world geometry (`CollisionView.getBlockCollisions` or bounding box intersection).
   - Vertical traversal semantics:
     - Walking onto bottom slabs and stairs (0.5m step-up without jumping).
     - 1-block jump up: verify apex headroom (2.0m clearance above jump apex to prevent ceiling bonking).
     - 1-block, 2-block, 3-block safe drops (avoiding hazard blocks like lava, sweet berry bushes, powder snow).
     - Basic 1-block parkour jumps across horizontal gaps.
4. Formulate the data structures: `PathNode`, priority queue, admissible Euclidean distance heuristic + vertical penalty, and path extraction.
5. Write your report to `report.md` and `handoff.md`. Notify orchestrator when done.

## 2026-09-11T13:33:00Z
<USER_REQUEST>
You are teamwork_preview_explorer_m2_1. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_1.
Read your DISPATCH.md, c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md, and c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md.
Design the complete architecture and implementation specification for Pathfinder.kt (3D A* search, 0.6x1.8m player hitbox collision, vertical traversal for slabs/stairs/1-block drops/apex ceiling headroom/parkour).
Write your report to report.md and handoff.md and notify orchestrator when done.
</USER_REQUEST>
