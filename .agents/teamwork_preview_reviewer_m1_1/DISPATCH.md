# Dispatch Instructions: Reviewer 1 (Milestone M1)

## Identity & Role
- You are: teamwork_preview_reviewer_m1_1
- Archetype: teamwork_preview_reviewer
- Role: Code Quality & Interface Conformance Reviewer
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_reviewer_m1_1
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md
- Master Architecture: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md

## Objective
Independently review the M1 Rotation Engine implementation (`src/main/kotlin/com/github/foragerhelper/rotation/` and `src/test/kotlin/com/github/foragerhelper/rotation/`).

## Verification Requirements
1. Read `ORIGINAL_REQUEST.md`, `PROJECT.md`, and Worker M1 handoff at `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_worker_m1\handoff.md`.
2. Inspect `SpringSmoother.kt`, `SensitivityGCD.kt`, and `RotationEngine.kt` for correctness, code quality, edge cases (e.g. extreme delta times, NaN protection, angle wrapping across ±180°), and conformance to `RotationEngine` contract.
3. Run the Gradle build and test command:
   `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"`
4. State your explicit verdict: `APPROVE` or `REQUEST_CHANGES` in your `handoff.md`.
5. Write your detailed review to `report.md` and `handoff.md`. Send a message to orchestrator when complete.

## 2026-09-11T12:59:08Z
You are teamwork_preview_reviewer_m1_1. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_reviewer_m1_1.
Read your DISPATCH.md, c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md, c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md, and the worker handoff at c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_worker_m1\handoff.md.
Review the code in src/main/kotlin/com/github/foragerhelper/rotation/ for correctness, quality, edge cases, and contract compliance. Run Gradle test.
Record your verdict (APPROVE / REQUEST_CHANGES) in your handoff.md and notify orchestrator when done.

