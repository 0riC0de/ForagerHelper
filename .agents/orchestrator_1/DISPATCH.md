## 2026-09-11T12:21:19Z

Execute a clean-slate rewrite and re-architecture of the core navigation, rotation, and target-selection engine for the ForagerHelper Fabric 1.21.11 Minecraft mod. Preserve the existing functional UI, keybinds, and HUD shell while replacing the tangled, jittery controllers with 4 cleanly decoupled modules:
1. R1: Standalone Humanized Rotation Engine (`rotation/RotationEngine.kt`) with render-frame interpolation and mouse-sensitivity GCD quantization.
2. R2: Hitbox-Aware 3D A* Pathfinder (`path/Pathfinder.kt`) with swept-box line-of-sight, vertical traversal, dynamic penalization, and unstuck maneuvers.
3. R3: Universal Target Framework (`target/`) with extensible NavigationTarget (BlockTarget, EntityTarget, PositionTarget) and pluggable TargetScanner.
4. R4: Decoupled Movement Controller (`movement/MovementController.kt`) with input translation operating in tandem with RotationEngine.

Build Environment:
- Java 23 is located at: `C:\Users\D0AF~1\JDKS~1\OPENJD~1` (8.3 short path to avoid Windows Hebrew username encoding issues).
- Build command:
  `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"`
  and full build:
  `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 build"`
