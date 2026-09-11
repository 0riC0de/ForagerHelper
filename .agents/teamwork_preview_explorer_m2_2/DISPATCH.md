# Dispatch Instructions: M2 Explorer 2 (Swept-Box Line-of-Sight & Path Smoothing)

## Identity & Role
- You are: teamwork_preview_explorer_m2_2
- Archetype: teamwork_preview_explorer
- Role: Swept-Box Collision & LOS Specialist
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_2
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md
- Scope Document: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md

## Objective
Design the complete implementation specification for `src/main/kotlin/com/github/foragerhelper/path/SweptBoxLOS.kt` and path smoothing.

## Mandatory Steps
1. Read `ORIGINAL_REQUEST.md` and `PROJECT.md` completely.
2. Read survey reports detailing diagonal corner snagging caused by 1D center-point Bresenham checks.
3. Formulate the swept bounding-box raycast algorithm:
   - Player bounding box: width 0.6m, depth 0.6m, height 1.8m.
   - For a segment from point $A$ to point $B$, compute swept bounding box `Box.of(center, 0.6, 1.8, 0.6)` or `box.stretch(delta)`.
   - Subdivide long segments into swept steps (e.g. step length $\le 0.4$m) and query `world.getBlockCollisions(null, stepBox)`.
   - Ensure the ground under feet along the straight-line shortcut remains walkable (no floating over pits or hazards).
   - Completely prevent cutting corners across diagonal solid wall blocks.
4. Define path smoothing (string pulling) that eliminates redundant intermediate waypoints while strictly respecting swept-box clearance.
5. Write your report to `report.md` and `handoff.md`. Notify orchestrator when done.

## 2026-09-11T13:32:59Z
<USER_REQUEST>
You are teamwork_preview_explorer_m2_2. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m2_2.
Read your DISPATCH.md, c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md, and c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md.
Design the complete implementation specification for SweptBoxLOS.kt (swept bounding-box raycast line-of-sight check and path smoothing to eliminate diagonal corner snagging).
Write your report to report.md and handoff.md and notify orchestrator when done.
</USER_REQUEST>
