# Dispatch Instructions: M1 Explorer 1 (Spring Dynamics & Angle Smoothing)

## Identity & Role
- You are: teamwork_preview_explorer_m1_1
- Archetype: teamwork_preview_explorer
- Role: Rotation Spring Dynamics Specialist
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m1_1
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md
- Scope Document: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md

## Objective
Design the complete implementation strategy for continuous critically-damped spring / exponential smoothing for `rotation/SpringSmoother.kt` and `rotation/RotationEngine.kt`.

## Mandatory Steps
1. Read `ORIGINAL_REQUEST.md` and `PROJECT.md` completely.
2. Read `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_1\report.md` for historical root cause details.
3. Formulate the exact mathematical model for critically-damped spring angle smoothing:
   - Handle shortest angular distance on yaw across `[-180, 180]` wrap boundary (`MathHelper.wrapDegrees`).
   - Track angular velocity continuously across frames so that changing targets in flight does NOT reset velocity or cause abrupt angle snapping.
   - Formulate parameters (damping ratio $\zeta \approx 1.0$, angular natural frequency $\omega_n$, delta-time integration).
4. Provide complete class and method design for `SpringSmoother.kt` and how `RotationEngine.kt` uses it.
5. Write your detailed report to `report.md` and `handoff.md` in your working directory.
6. When done, send a message to orchestrator.

## 2026-09-11T12:32:21Z
<USER_REQUEST>
You are teamwork_preview_explorer_m1_1. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m1_1.
Read your DISPATCH.md, c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md, and c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md.
Design the complete mathematical model and implementation specification for SpringSmoother.kt (critically damped angular spring, delta-time integration, angle wrapping, continuous velocity across target updates).
Write your report to report.md and handoff.md in your working directory. Send a message to orchestrator when complete.
</USER_REQUEST>
