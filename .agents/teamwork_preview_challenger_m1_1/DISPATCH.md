# Dispatch Instructions: Challenger 1 (Milestone M1)

## Identity & Role
- You are: teamwork_preview_challenger_m1_1
- Archetype: teamwork_preview_challenger
- Role: Adversarial Stress Tester (Spring Dynamics & Extreme Inputs)
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_challenger_m1_1
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md
- Master Architecture: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md

## Objective
Adversarially challenge and stress-test the M1 Rotation Engine implementation.

## Adversarial Verification Tasks
1. Read `ORIGINAL_REQUEST.md`, `PROJECT.md`, and `src/main/kotlin/com/github/foragerhelper/rotation/`.
2. Subject `SpringSmoother.kt` to stress tests:
   - Extreme delta times: $\Delta t = 0$, $\Delta t = 10.0\text{s}$, $\Delta t = 10^{-6}\text{s}$.
   - Huge angle wraps: $179.9^\circ \to -179.9^\circ$ (must traverse $0.2^\circ$, never $359.8^\circ$).
   - Multiple wraps: $>3600^\circ$ inputs, negative angles, sudden reversals mid-convergence.
   - NaN, Infinity inputs: verify robustness or rejection without process crash.
3. Run or write adversarial tests executing via Gradle:
   `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"`
4. State your explicit verdict: `APPROVE` (passes all stress tests) or `REJECT` (found failure/instability).
5. Write report to `report.md` and `handoff.md`. Send a message to orchestrator when complete.

## 2026-09-11T12:59:08Z
You are teamwork_preview_challenger_m1_1. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_challenger_m1_1.
Read your DISPATCH.md, c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md, and c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md.
Adversarially stress-test SpringSmoother.kt with extreme delta times (0s, 10s, 1e-6s), large angle wraps (>3600 deg, 179.9 to -179.9), and boundary conditions. Run Gradle test.
Record your verdict (APPROVE / REJECT) in your handoff.md and notify orchestrator when done.
