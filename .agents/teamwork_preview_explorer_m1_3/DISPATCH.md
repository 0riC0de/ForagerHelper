# Dispatch Instructions: M1 Explorer 3 (Render Hooks & Head Orientation)

## Identity & Role
- You are: teamwork_preview_explorer_m1_3
- Archetype: teamwork_preview_explorer
- Role: Render Lifecycle & Camera Orientation Specialist
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m1_3
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md
- Scope Document: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md

## Objective
Design the complete integration and lifecycle strategy for `rotation/RotationEngine.kt`, render-frame hookup (`WorldRenderEvents`), path tangent orientation, and target focus acquisition.

## Mandatory Steps
1. Read `ORIGINAL_REQUEST.md` and `PROJECT.md` completely.
2. Formulate the render frame event hook:
   - Identify how `WorldRenderEvents.START_MAIN` or `WorldRenderEvents.END_EXTRACTION` (or delta-time clock via `System.nanoTime()`) should invoke `RotationEngine.onRenderFrame(deltaTime, tickProgress)`.
   - Ensure camera angles are applied to player entity (`player.changeLookDirection` or setting `player.yaw` and `player.lastYaw` synchronously) to eliminate vanilla interpolation fight.
3. Formulate natural head movement:
   - Orient player view along path tangent when navigating.
   - Smoothly transition focus toward target interaction point when within reach distance.
4. Define the complete class structure, lifecycle methods (`start`, `stop`, `reset`, `onRenderFrame`), and interface implementation for `RotationEngine.kt`.
5. Write your detailed report to `report.md` and `handoff.md` in your working directory.
6. When done, send a message to orchestrator.

## 2026-09-11T12:32:21Z
<USER_REQUEST>
You are teamwork_preview_explorer_m1_3. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m1_3.
Read your DISPATCH.md, c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md, and c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md.
Design the complete integration and lifecycle specification for RotationEngine.kt (Fabric WorldRenderEvents render frame hooks, synchronized yaw/lastYaw camera orientation, natural head movement along path tangent, and target focus blending).
Write your report to report.md and handoff.md in your working directory. Send a message to orchestrator when complete.
</USER_REQUEST>
