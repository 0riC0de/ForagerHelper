# Dispatch Instructions: Worker M1 Remediation (Rotation Engine Bug Fixes)

## Identity & Role
- You are: teamwork_preview_worker_m1_rem
- Archetype: teamwork_preview_worker
- Role: Rotation Engine Remediation Worker
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_worker_m1_rem
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md
- Master Architecture: c:\Users\משתמש\source\repos\ForagerHelper\.agents\PROJECT.md

## MANDATORY INTEGRITY WARNING
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

## Objective
Apply the verified fixes from M1 Remediation Explorers 1, 2, and 3:
1. `src/main/kotlin/com/github/foragerhelper/rotation/SensitivityGCD.kt`: Apply 4-tier pitch boundary anti-windup from `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m1_rem_1\proposed_SensitivityGCD.kt`.
2. `src/main/kotlin/com/github/foragerhelper/rotation/SpringSmoother.kt`: Apply defensive NaN/Inf input sanitization and zero-delta rejection from `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m1_rem_2\proposed_SpringSmoother.kt`.
3. `src/test/kotlin/com/github/foragerhelper/rotation/SpringSmootherAdversarialTest.kt`: Update test assertions for NaN resilience per Remediation Explorer 2 & 3 handoffs (asserting clean rejection of NaN instead of asserting NaN output).
4. `build.gradle.kts`: Configure `jvmArgs("-Dfile.encoding=UTF-8")` under `tasks.withType<Test>` per Remediation Explorer 3 handoff.

## Verification Requirements
Run the full test suite and build commands via Gradle:
1. `cmd /c "set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 && cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"`
2. `cmd /c "set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 && cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test"`

Every single test (all 55 tests across `RotationEngineTest`, `SensitivityGCDAdversarialTest`, and `SpringSmootherAdversarialTest`) MUST pass with 0 failures and 0 errors.

Write your report to `report.md` and `handoff.md` in your working directory. Send a message to orchestrator when finished.
