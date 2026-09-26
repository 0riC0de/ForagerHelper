# Minecraft Fabric Client Navigation & Input Invariants

## 1. Screen Input Lifecycle & Key State Isolation
- In Fabric, `ClientPlayerEntity.tickMovement()` executes *before* `ClientTickEvents.END_CLIENT_TICK`.
- To prevent physical movement key leakage while in screens (Inventory, Chat, Options GUI):
  - Always release and clear key states in `ClientTickEvents.START_CLIENT_TICK`.
  - Never poll or re-assert physical GLFW hardware keys repeatedly during idle ticks in `clearScan()`.
  - Check `client.currentScreen != null` as an early exit before applying any synthetic inputs.

## 2. Step-Up Geometry (Slabs, Stairs, and Elevation)
- Vanilla Minecraft automatically steps up elevations $\le 0.60\text{m}$ (slabs and stairs) without jumping.
- Obstacle Avoidance:
  - Do NOT classify adjacent full blocks as side walls when standing on a slab ($Y+0.5\text{m}$).
  - Lateral obstacle probes must evaluate $Y-1$, $Y$, and $Y+1$ stand heights relative to the player's foot elevation before flagging a block as a blocking wall.
- Jumping Behavior:
  - Suppress jumping when walking up stairs or onto slabs (`isStepUpBlock`).
  - Restrict sprint-jumping exclusively to long, flat, straight stretches ($\ge 5.0\text{m}$) and disable jumping when running speed exceeds jumping speed ($> 0.20\text{m/s}$).

## 3. Parkour Gap Traversal & Repath Throttling
- Suppress periodic repaths while approaching, jumping, or in mid-air across parkour gaps (`hasActiveParkourAhead` and `!env.isOnGround`).
- Edge Timing:
  - Multi-range probe offsets ($0.3\text{m}$ to $1.5\text{m}$) must be used to trigger takeoff right at the platform edge.
- Bridge Search & BFS Limits:
  - Deep path searches (`findBridgeRoute`) must be strictly capped (e.g. $\le 400$ nodes with a 15ms deadline) to prevent client tick freezes (1 FPS lag spikes).

## 4. Taunahi-Inspired GUI Design Standards
- Color Palette: Orange (`#FF6B00` / `#FF8800`) accents on dark charcoal (`#161315`, `#1E1E23`).
- Navigation: Left sidebar category list with modern pill badges; right content area with two-column cards (left: control + label, right: multi-line description).
- Motion: Slower cubic sliding transitions with subtle trailing motion blur passes.
- Window Opening: Smooth upward slide from viewport bottom (`height * 0.65f` to `0f`).
- Input Sanitization: Strip delimiters (`|`, `\r`, `\n`) when saving user-named waypoints to file.
