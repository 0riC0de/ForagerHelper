# Dispatch Instructions: Challenger 2 (Milestone M1)

## Identity & Role
- You are: teamwork_preview_challenger_m1_2
- Archetype: teamwork_preview_challenger
- Role: Adversarial Anti-Cheat & GCD Verifier
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_challenger_m1_2
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md
- Master Architecture: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md

## Objective
Adversarially challenge and stress-test `SensitivityGCD.kt` and `RotationEngine.kt` for anti-cheat compliance and precision loss.

## Adversarial Verification Tasks
1. Read `ORIGINAL_REQUEST.md`, `PROJECT.md`, and `src/main/kotlin/com/github/foragerhelper/rotation/SensitivityGCD.kt`.
2. Adversarially verify:
   - Every single non-zero applied $\Delta \text{yaw}$ and $\Delta \text{pitch}$ must be an exact integer multiple of $f^3 \times 1.2$ (within float ULP).
   - Test extreme sensitivities: $s = 0.0$ (minimum vanilla sensitivity), $s = 1.0$ (100%), $s = 2.0$ (hyper-speed / cinematic), $s = 0.5$ (default).
   - Test remainder drift across 10,000 frames: does the accumulator drift or explode? Total accumulated remainder must stay within $[-0.5 \times \text{step}, +0.5 \times \text{step}]$.
   - Verify pitch limits: pitch must never exceed $[-90^\circ, +90^\circ]$ regardless of remainder accumulation.
3. Run tests via Gradle:
   `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"`
4. State your explicit verdict: `APPROVE` or `REJECT`.
5. Write report to `report.md` and `handoff.md`. Send a message to orchestrator when complete.

## 2026-09-11T12:59:08Z
You are teamwork_preview_challenger_m1_2. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_challenger_m1_2.
Read your DISPATCH.md, c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md, and c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md.
Adversarially verify SensitivityGCD.kt: check that every applied delta is an exact multiple of the GCD step, test sensitivity range (0.0, 0.5, 1.0, 2.0), test 10,000-frame remainder accumulator stability, and verify pitch limits [-90, +90]. Run Gradle test.
Record your verdict (APPROVE / REJECT) in your handoff.md and notify orchestrator when done.
