# Dispatch Instructions: M1 Explorer 2 (Mouse Sensitivity GCD Quantization)

## Identity & Role
- You are: teamwork_preview_explorer_m1_2
- Archetype: teamwork_preview_explorer
- Role: Mouse GCD Anti-Cheat Quantization Specialist
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m1_2
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md
- Scope Document: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md

## Objective
Design the complete implementation strategy for Minecraft mouse sensitivity GCD quantization in `rotation/SensitivityGCD.kt` and integration into `rotation/RotationEngine.kt`.

## Mandatory Steps
1. Read `ORIGINAL_REQUEST.md` and `PROJECT.md` completely.
2. Read `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_3\report.md` regarding vanilla Minecraft 1.21.11 mouse sensitivity math and GCD calculation.
3. Formulate the exact mathematical algorithms:
   - Read mouse sensitivity from Minecraft client options (`client.options.mouseSensitivity.value`).
   - Calculate vanilla multiplier: $f = s \times 0.6 + 0.2$, angular step per integer mouse count: $\text{step} = f^3 \times 8 \times 0.15 = f^3 \times 1.2$ degrees.
   - Implement remainder accumulator: accumulate desired sub-step angular deltas ($\Delta \text{yaw}, \Delta \text{pitch}$), extract exact integer mouse counts, quantize applied deltas to $\text{counts} \times \text{step}$, and retain fractional remainder for subsequent frames to prevent drift and abrupt truncation.
4. Detail test verification vectors and anti-cheat compliance proofs.
5. Write your detailed report to `report.md` and `handoff.md` in your working directory.


## 2026-09-11T12:32:21Z
You are teamwork_preview_explorer_m1_2. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m1_2.
Read your DISPATCH.md, c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md, and c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md.
Design the complete mathematical model and implementation specification for SensitivityGCD.kt (Minecraft mouse sensitivity GCD quantization, f = s * 0.6 + 0.2, step = f^3 * 1.2, remainder accumulator for fractional counts, anti-cheat compliance).
Write your report to report.md and handoff.md in your working directory. Send a message to orchestrator when complete.
