import math
import random

def compute_step(sens, is_spyglass=False):
    s = max(0.0, min(2.0, 0.5 if math.isnan(sens) or sens < 0.0 else sens))
    f = s * 0.6000000238418579 + 0.20000000298023224
    f_cubed = f * f * f
    base = 1.0 if is_spyglass else 8.0
    return f_cubed * base * 0.15

def counts_to_delta(counts, sens, is_spyglass=False):
    s = max(0.0, min(2.0, 0.5 if math.isnan(sens) or sens < 0.0 else sens))
    f = s * 0.6000000238418579 + 0.20000000298023224
    f_cubed = f * f * f
    mult = f_cubed if is_spyglass else f_cubed * 8.0
    dx = float(counts) * mult
    return dx * 0.15

def nearest_count(total, step):
    return math.floor(total / step + 0.5)

class SensitivityGCD_Fixed:
    def __init__(self):
        self.yawRemainder = 0.0
        self.pitchRemainder = 0.0

    def reset(self):
        self.yawRemainder = 0.0
        self.pitchRemainder = 0.0

    def quantize(self, desiredDeltaYaw, desiredDeltaPitch, sensitivity, isSpyglass=False, currentPitch=None):
        safeSens = max(0.0, min(2.0, 0.5 if math.isnan(sensitivity) or sensitivity < 0.0 else sensitivity))
        step = compute_step(safeSens, isSpyglass)
        if step <= 1e-7:
            return 0, 0, 0.0, 0.0, self.yawRemainder, self.pitchRemainder, step

        var_pitchClampedAtBoundary = False

        # 1. Pitch Anti-Windup Clamping at hard limits
        effectiveDesiredPitch = desiredDeltaPitch
        if currentPitch is not None:
            if currentPitch >= 90.0 - 1e-4 and desiredDeltaPitch > 0.0:
                effectiveDesiredPitch = 0.0
                self.pitchRemainder = 0.0
                var_pitchClampedAtBoundary = True
            elif currentPitch <= -90.0 + 1e-4 and desiredDeltaPitch < 0.0:
                effectiveDesiredPitch = 0.0
                self.pitchRemainder = 0.0
                var_pitchClampedAtBoundary = True

        # 2. Accumulate desired delta + stored remainder
        totalYaw = desiredDeltaYaw + self.yawRemainder
        totalPitch = effectiveDesiredPitch + self.pitchRemainder

        # 3. Extract exact nearest integer mouse counts
        countsYaw = nearest_count(totalYaw, step)
        countsPitch = nearest_count(totalPitch, step)

        # 4. Instant camera reversal from boundaries:
        # If camera is pinned near boundary and user requests rotation away from boundary,
        # ensure at least 1 count in reversal direction so camera doesn't freeze or stall.
        if currentPitch is not None:
            if currentPitch >= 90.0 - step and desiredDeltaPitch < 0.0 and countsPitch >= 0:
                countsPitch = -1
                self.pitchRemainder = 0.0
                var_pitchClampedAtBoundary = True
            elif currentPitch <= -90.0 + step and desiredDeltaPitch > 0.0 and countsPitch <= 0:
                countsPitch = 1
                self.pitchRemainder = 0.0
                var_pitchClampedAtBoundary = True

        # 5. Pitch Boundary Over-Rotation Clamping
        if currentPitch is not None and countsPitch != 0:
            potentialPitchDelta = counts_to_delta(countsPitch, safeSens, isSpyglass)
            projectedPitch = currentPitch + potentialPitchDelta
            if projectedPitch > 90.0:
                allowedDelta = 90.0 - currentPitch
                countsPitch = max(0, int(allowedDelta / step))
                self.pitchRemainder = 0.0
                var_pitchClampedAtBoundary = True
            elif projectedPitch < -90.0:
                allowedDelta = -90.0 - currentPitch
                countsPitch = min(0, int(allowedDelta / step))
                self.pitchRemainder = 0.0
                var_pitchClampedAtBoundary = True

        # 6. Compute exact applied deltas
        appliedDeltaYaw = counts_to_delta(countsYaw, safeSens, isSpyglass)
        appliedDeltaPitch = counts_to_delta(countsPitch, safeSens, isSpyglass)

        # 7. Update remainders for next frame with half-step clamping
        halfStep = 0.5 * step
        self.yawRemainder = max(-halfStep, min(halfStep, totalYaw - (countsYaw * step)))
        if not var_pitchClampedAtBoundary:
            self.pitchRemainder = max(-halfStep, min(halfStep, totalPitch - (countsPitch * step)))
        else:
            self.pitchRemainder = 0.0

        return countsYaw, countsPitch, appliedDeltaYaw, appliedDeltaPitch, self.yawRemainder, self.pitchRemainder, step

def run_tests():
    # Test A: Stress test 1000 frames pushing into +90 limit
    print("Test A: Stress test pushing into +90...")
    for sens in [0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0]:
        gcd = SensitivityGCD_Fixed()
        step = compute_step(sens)
        pitch = 89.5
        for frame in range(1, 1001):
            cY, cP, dY, dP, rY, rP, st = gcd.quantize(0.0, 1.0, sens, currentPitch=pitch)
            pitch += dP
            assert pitch <= 90.0 + 1e-5, f"Pitch exceeded 90.0: {pitch}"
            assert abs(rP) <= 0.5 * step + 1e-9, f"Remainder exploded at frame {frame}: {rP}"
    print("  -> PASS")

    # Test B: Stress test pushing into -90 limit
    print("Test B: Stress test pushing into -90...")
    for sens in [0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0]:
        gcd = SensitivityGCD_Fixed()
        step = compute_step(sens)
        pitch = -89.5
        for frame in range(1, 1001):
            cY, cP, dY, dP, rY, rP, st = gcd.quantize(0.0, -1.0, sens, currentPitch=pitch)
            pitch += dP
            assert pitch >= -90.0 - 1e-5, f"Pitch below -90.0: {pitch}"
            assert abs(rP) <= 0.5 * step + 1e-9, f"Remainder exploded at frame {frame}: {rP}"
    print("  -> PASS")

    # Test C: Approach and reversal across all sensitivities
    print("Test C: Approach & immediate reversal across sensitivities...")
    for sens in [0.0, 0.1, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0]:
        gcd = SensitivityGCD_Fixed()
        pitch = 80.0
        # Approach down
        for _ in range(1000):
            cY, cP, dY, dP, rY, rP, st = gcd.quantize(0.0, 0.5, sens, currentPitch=pitch)
            pitch += dP
            assert pitch <= 90.0 + 1e-5
            assert pitch >= -90.0 - 1e-5

        # Reversal 1
        cY, cP, dY, dP, rY, rP, st = gcd.quantize(0.0, -1.0, sens, currentPitch=pitch)
        assert cP < 0, f"sens={sens}: Camera must reverse upward immediately: cP={cP}, pitch={pitch}"
        pitch += dP

        # Approach up
        for _ in range(1000):
            cY, cP, dY, dP, rY, rP, st = gcd.quantize(0.0, -0.5, sens, currentPitch=pitch)
            pitch += dP
            assert pitch >= -90.0 - 1e-5
            assert pitch <= 90.0 + 1e-5

        # Reversal 2
        cY, cP, dY, dP, rY, rP, st = gcd.quantize(0.0, 1.0, sens, currentPitch=pitch)
        assert cP > 0, f"sens={sens}: Camera must reverse downward immediately: cP={cP}, pitch={pitch}"
    print("  -> PASS")

    # Test D: Micro-deltas at boundary (small movements away)
    print("Test D: Micro-deltas at boundary...")
    for sens in [0.0, 0.5, 1.0, 2.0]:
        gcd = SensitivityGCD_Fixed()
        pitch = 89.9
        # Reverse with very small delta -0.01
        cY, cP, dY, dP, rY, rP, st = gcd.quantize(0.0, -0.01, sens, currentPitch=pitch)
        assert cP < 0, f"Micro-delta reversal failed for sens={sens}: cP={cP}"
    print("  -> PASS")

    # Test E: Unconstrained pitch and yaw free tracking (no currentPitch)
    print("Test E: Free tracking without currentPitch (10000 frames)...")
    gcd = SensitivityGCD_Fixed()
    step = compute_step(0.5)
    for f in range(10000):
        cY, cP, dY, dP, rY, rP, st = gcd.quantize(0.123, 0.087, 0.5)
        assert abs(rY) <= 0.5 * step + 1e-9
        assert abs(rP) <= 0.5 * step + 1e-9
    print("  -> PASS")

    print("\nALL EDGE CASE TESTS PASSED CLEANLY!")

if __name__ == '__main__':
    run_tests()
