# Dispatch Instructions: Survey Explorer 3

## Identity & Role
- You are: teamwork_preview_explorer_survey_3
- Archetype: teamwork_preview_explorer
- Role: Build Environment & Fabric/Minecraft API Explorer
- Working directory: c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_3
- Project root: c:\Users\משתמש\source\repos\ForagerHelper
- Authoritative User Request: c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md

## Objective
Thoroughly explore build configuration, Gradle dependencies, Fabric 1.21.11 client API hooks, and testing infrastructure in the ForagerHelper codebase.

## Mandatory Steps
1. READ `c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md` completely before starting.
2. Investigate build files (`build.gradle.kts` / `build.gradle`, `gradle.properties`, fabric loom setup, kotlin version).
3. Investigate the exact Fabric 1.21.11 APIs and Loom mappings available for:
   - Render tick / frame events (e.g. `WorldRenderEvents`, `ClientTickEvents`, `RenderTickCounter`, `Camera`, or mixin targets into camera orientation).
   - Mouse sensitivity settings in MinecraftClient (`client.options.mouseSensitivity.value`), mouse GCD quantization formula and how yaw/pitch are applied.
   - 3D collision checking in Fabric 1.21.11 (`World.getBlockCollisions`, `BlockView`, `VoxelShape`, `Box`, `ShapeContext.of(player)`).
   - Existing test setup (if any) and how unit tests can be written and run under Gradle without needing a live Minecraft window.
4. Run/test the Gradle build command using `run_command`:
   `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"`
   Verify that current code builds cleanly or identify any existing build warnings/errors.
5. Produce a comprehensive report at:
   `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_3\report.md`
   and a structured `handoff.md` summarizing your findings, evidence, and recommendations.
6. When done, send a completion message back to orchestrator.

## 2026-09-11T12:22:14Z
You are teamwork_preview_explorer_survey_3. Your working directory is c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_3.
Read your DISPATCH.md and c:\Users\משתמש\source\repos\ForagerHelper\.agents\ORIGINAL_REQUEST.md first.
Map the build configuration, Gradle dependencies, and Fabric 1.21.11 client APIs available for:
- Render frame tick/events (WorldRenderEvents, ClientTickEvents, RenderTickCounter, camera orientation hooks/mixins).
- Minecraft client mouse sensitivity and mouse GCD calculation.
- 3D collision checking (World.getBlockCollisions, Box, VoxelShape, ShapeContext).
- Unit testing setup under Gradle and how offline tests can be executed.
Write your comprehensive report to c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_survey_3\report.md and a structured handoff.md.
When complete, send a message to orchestrator with a summary and confirmation.
