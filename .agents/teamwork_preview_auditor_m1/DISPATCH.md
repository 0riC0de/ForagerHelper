# Dispatch Instructions: Forensic Auditor (Milestone M1)

## Identity & Role
- You are: teamwork_preview_auditor_m1
- Archetype: teamwork_preview_auditor
- Role: Forensic Integrity Auditor
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_auditor_m1
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md
- Master Architecture: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md

## Objective
Perform forensic integrity verification of the M1 Rotation Engine implementation (`src/main/kotlin/com/github/foragerhelper/rotation/` and `src/test/kotlin/com/github/foragerhelper/rotation/`).

## Integrity Forensic Checks
Verify with ZERO TOLERANCE:
1. **Hardcoding check**: Inspect source code and tests to ensure no hardcoded test outputs, lookup tables of expected test values, or dummy results exist.
2. **Facade check**: Ensure `SpringSmoother.kt`, `SensitivityGCD.kt`, and `RotationEngine.kt` are genuine mathematical implementations (solving the 2nd-order ODE, calculating the vanilla mouse GCD formula, and updating actual state).
3. **Attestation/Execution check**: Verify that the Gradle test execution was genuine and not bypassed.
   `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"`
4. State your explicit binary verdict: `CLEAN` or `INTEGRITY VIOLATION`.
5. Write your forensic audit report to `report.md` and `handoff.md`. Send a message to orchestrator when complete.

## 2026-09-11T12:59:08Z
You are teamwork_preview_auditor_m1. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_auditor_m1.
Read your DISPATCH.md, c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md, and c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md.
Perform forensic integrity checks on src/main/kotlin/com/github/foragerhelper/rotation/ and src/test/kotlin/com/github/foragerhelper/rotation/ for hardcoding, facades/dummies, and test bypassing. Run Gradle test.
State your binary verdict (CLEAN / INTEGRITY VIOLATION) in handoff.md and notify orchestrator when done.
