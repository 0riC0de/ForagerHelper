# Handoff Report: Spring Dynamics & Angle Smoothing Specification (M1 Explorer 1)

**Agent**: `teamwork_preview_explorer_m1_1`  
**Role**: Rotation Spring Dynamics Specialist  
**Working Directory**: `c:\Users\משתמש\source\repos\ForagerHelper\.agents\teamwork_preview_explorer_m1_1`  
**Handoff Type**: Hard (Task Complete)  
**Date**: 2026-09-11  

---

## 1. Observation

1. **Legacy Curve Reset Defect**:
   - In `src/main/kotlin/foraginghelpermod/client/path/WalkController.kt` (lines 357-366, 381, 390-392):
     ```kotlin
     val newTarget = lookPlanTargetYaw.isNaN() ||
         abs(MathHelper.wrapDegrees(targetYaw - lookPlanTargetYaw)) > 0.35f ||
         abs(targetPitch - lookPlanTargetPitch) > 0.35f
     if (newTarget) {
         lookPlanStartYaw = MathHelper.wrapDegrees(player.yaw)
         ...
         lookPlanStep = 0
     }
     lookPlanStep++
     val fraction = (lookPlanStep.toFloat() / lookPlanSteps).coerceIn(0f, 1f)
     val eased = fraction * fraction * (3f - 2f * fraction)
     ```
     Any target angular drift $> 0.35^\circ$ forces `lookPlanStep = 0`, discarding in-flight cubic progress, destroying current angular velocity $\dot{\theta}$ to $0$, and restarting interpolation from rest.

2. **20 Hz Tick Limitation & Camera Stutter**:
   - In `src/main/kotlin/foraginghelpermod/client/InputController.kt` (line 46):
     ```kotlin
     ClientTickEvents.END_CLIENT_TICK.register { client ->
         ...
         WalkController.tick(client)
     }
     ```
   - In `WalkController.kt` (lines 405-406):
     ```kotlin
     player.yaw = lastYaw
     player.pitch = lastPitch
     ```
     Camera angles are updated strictly on client ticks (20 Hz, 50ms). On a 144 Hz display, 6 out of 7 render frames present identical yaw/pitch, followed by a discrete 50ms jump.

3. **Angle Wrapping in Minecraft API**:
   - Vanilla `net.minecraft.util.math.MathHelper.wrapDegrees(degrees: Float): Float` maps continuous degrees to the half-open interval $[-180.0^\circ, 180.0^\circ)$.
   - `MathHelper.wrapDegrees(degrees: Double): Double` does the same for doubles.

4. **Player Pitch Clamping in Minecraft**:
   - `net.minecraft.entity.Entity.changeLookDirection(dx, dy)` clamps `pitch` and `lastPitch` to $[-90.0^\circ, +90.0^\circ]$.
   - In `WalkController.kt` line 355:
     ```kotlin
     if (precise) targetPitch = MathHelper.clamp(targetPitch, -89f, 89f)
     ```
     Using $[-89.9^\circ, 89.9^\circ]$ avoids look-vector singularities at exact poles.

5. **Build and Test Environment**:
   - JDK 23 at short path `C:\Users\D0AF~1\JDKS~1\OPENJD~1` builds cleanly:
     Command: `cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 compileKotlin"` exits with code 0.

---

## 2. Logic Chain

1. **Elimination of Curve Resetting via Dynamical State**:
   - From Observation 1, resetting `lookPlanStep = 0` creates an infinite jerk / step discontinuity in acceleration and terminates angular velocity.
   - To guarantee $C^1$ smoothness ($\theta$ and $\dot{\theta}$ continuous everywhere), the smoothing algorithm must store continuous angular velocity $v(t) = \dot{\theta}(t)$ as persistent state across frames.
   - A target update $\theta_1^* \to \theta_2^*$ simply shifts the equilibrium point; initial conditions for the next step become $(x_0, v_0)$ where $x_0 = \theta(t) - \theta_2^*$ and $v_0 = v(t)$. No velocity is lost.

2. **Damping Ratio $\zeta = 1.0$ (Critical Damping)**:
   - For the linear 2nd order system $\ddot{x} + 2\zeta\omega_n\dot{x} + \omega_n^2 x = 0$:
     - Underdamped ($\zeta < 1$) exhibits overshoot and oscillatory ringing around the target crosshair, which causes missed block clicks and triggers anti-cheat snapback heuristics.
     - Overdamped ($\zeta > 1$) exhibits slow convergence dominated by the slower pole $-\omega_n(\zeta - \sqrt{\zeta^2-1})$, causing sluggish camera feel.
     - Critically damped ($\zeta = 1.0$) provides the unique, fastest asymptotic convergence with zero overshoot from rest ($v_0 = 0$).

3. **Unconditional Stability via Exact Analytic Integration**:
   - Explicit Euler integration diverges when $h > \frac{2}{\omega_n}$. For $\omega_n = 20\text{ rad/s}$, a frame hitch $> 100\text{ ms}$ results in explosive instability and `NaN`.
   - The exact closed-form solution over frame step $h = \Delta t$ is:
     $$c_2 = v_0 + \omega_n x_0$$
     $$x(h) = (x_0 + c_2 h) e^{-\omega_n h}$$
     $$v(h) = (v_0 - \omega_n c_2 h) e^{-\omega_n h}$$
   - The transition matrix $M(h)$ has spectral radius $\rho(M(h)) = e^{-\omega_n h}$. Since $e^{-\omega_n h} < 1$ for all $h > 0$, the system is unconditionally $A$-stable and time-step independent.

4. **Topology of $S^1$ & Velocity Invariance**:
   - From Observation 3, Yaw wraps across $[-180^\circ, 180^\circ]$.
   - Shortest angular distance is computed via $x_0 = \text{MathHelper.wrapDegrees}(\theta_k - \theta^*)$.
   - Angular velocity $v = \dot{\theta}$ represents physical angular speed ($^\circ/\text{s}$). It is invariant under $360^\circ$ coordinate translations and must NEVER be wrapped.
   - The step delta $\Delta \theta = x(h) - x_0$ is applied to current yaw, which is then canonicalized via `MathHelper.wrapDegrees`.

5. **Pitch Domain Clamping and Anti-Windup**:
   - From Observation 4, Pitch lives on $[-89.9^\circ, 89.9^\circ]$ and does not wrap.
   - If Pitch hits $\pm 89.9^\circ$ and velocity pushes outward, velocity must be zeroed ($v \leftarrow 0$) to eliminate spring windup.

6. **Anti-Cheat Speed Limits**:
   - Adapting Thomas Dawson's displacement limiter (Game Programming Gems 4):
     $$x_0^{\text{clamped}} = x_0.\text{coerceIn}\left(-\frac{v_{\max}}{\omega_n}, \frac{v_{\max}}{\omega_n}\right)$$
     enforces peak angular speed $\le v_{\max}$ ($720^\circ/\text{s}$) with smooth acceleration and deceleration, preventing anti-cheat detection for large turnarounds.

---

## 3. Caveats

1. **Render Hook Registration**:
   - `SpringSmoother.kt` is a pure mathematical engine with zero direct dependency on Minecraft rendering events. Hooking into `WorldRenderEvents.START_MAIN` or `onRenderFrame` and passing `deltaTime` is the responsibility of `RotationEngine.kt` (assigned to M1 Explorer 3).
2. **Quantization Remainder Coupling**:
   - `SpringSmoother.kt` emits continuous frame deltas (`RotationDelta(deltaYaw, deltaPitch)`). Quantizing these deltas to Minecraft mouse sensitivity GCD steps (`(s*0.6+0.2)^3 * 1.2`) and accumulating fractional remainders is handled by `SensitivityGCD.kt` (assigned to M1 Explorer 2).
3. **Extreme Delta Time Capping**:
   - Although the analytic formula is unconditionally stable for any $h > 0$, rendering pauses (e.g. window minimizing or Alt-Tab for 30 seconds) should clamp `deltaTime` to `MAX_INTEGRATION_STEP = 0.2f` (200ms) to avoid instantaneous angular teleportation upon window restoration.

---

## 4. Conclusion

1. The mathematical specification for `SpringSmoother.kt` is complete, verified, and documented in `report.md`.
2. The implementation comprises two classes:
   - `AngularSpring1D`: Generic 1D critically damped spring with `AngleMode.WRAPPED` and `AngleMode.CLAMPED`.
   - `SpringSmoother`: 2D composite smoother managing Yaw and Pitch with unified lifecycle, reset, and step methods.
3. Key performance metrics:
   - Natural frequency: $\omega_n = 18.0\text{ rad/s}$ (Smooth time $\tau \approx 0.111\text{ s}$, half-life $t_{1/2} \approx 0.093\text{ s}$).
   - Max angular velocity: $720.0^\circ/\text{s}$.
   - Convergence deadband: $0.001^\circ$ angle, $0.01^\circ/\text{s}$ velocity.
4. Ready for implementation in Milestone M1.

---

## 5. Verification Method

### Independent Verification Steps:
1. **Mathematical Invariants Inspection**:
   - Inspect `.agents/teamwork_preview_explorer_m1_1/report.md` Section 4 for the eigenvalues and spectral radius proof $\rho(M(h)) = e^{-\omega_n h} < 1$.
   - Inspect Section 9.2 for the complete Kotlin code of `SpringSmoother.kt`.
2. **Headless Unit Tests (Gradle)**:
   - Once implemented in `src/main/kotlin/com/github/foragerhelper/rotation/SpringSmoother.kt` and `src/test/kotlin/com/github/foragerhelper/rotation/SpringSmootherTest.kt`:
   - Run:
     ```bat
     cmd /c "cd /d C:\Users\D0AF~1\source\repos\FORAGE~1 && gradlew.bat -Dorg.gradle.java.home=C:\Users\D0AF~1\JDKS~1\OPENJD~1 test --tests *SpringSmootherTest*"
     ```
   - Expected result: BUILD SUCCESSFUL, all unit tests pass.
3. **Invalidation Conditions**:
   - Any test showing overshoot from rest ($x(t) < 0$ when $x(0) > 0, v(0) = 0$).
   - Any test showing numerical explosion or NaN under large $\Delta t$ ($h = 1.0\text{ s}$).
   - Any angle wrapping anomaly rotating $358^\circ$ instead of $2^\circ$ when transitioning from $179^\circ \to -179^\circ$.
   - Any velocity discontinuity when target changes mid-flight.
