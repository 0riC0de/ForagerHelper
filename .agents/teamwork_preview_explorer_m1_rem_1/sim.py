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

class FixedSensitivityGCD_V2:
    def __init__(self):
        self.yawRemainder = 0.0
        self.pitchRemainder = 0.0

    def quantize(self, desiredDeltaYaw, desiredDeltaPitch, sensitivity, isSpyglass=False, currentPitch=None):
        safeSens = max(0.0, min(2.0, 0.5 if math.isnan(sensitivity) or sensitivity < 0.0 else sensitivity))
        step = compute_step(safeSens, isSpyglass)
        if step <= 1e-7:
            return 0, 0, 0.0, 0.0, self.yawRemainder, self.pitchRemainder, step

        var_pitchClampedAtBoundary = False

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

        totalYaw = desiredDeltaYaw + self.yawRemainder
        totalPitch = effectiveDesiredPitch + self.pitchRemainder

        countsYaw = nearest_count(totalYaw, step)
        countsPitch = nearest_count(totalPitch, step)

        # Instant camera reversal from boundaries:
        if currentPitch is not None:
            if currentPitch >= 90.0 - step and desiredDeltaPitch < 0.0 and countsPitch >= 0:
                countsPitch = -1
                self.pitchRemainder = 0.0
                var_pitchClampedAtBoundary = True
            elif currentPitch <= -90.0 + step and desiredDeltaPitch > 0.0 and countsPitch <= 0:
                countsPitch = 1
                self.pitchRemainder = 0.0
                var_pitchClampedAtBoundary = True

        # Pitch Boundary Over-Rotation Clamping
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

        appliedDeltaYaw = counts_to_delta(countsYaw, safeSens, isSpyglass)
        appliedDeltaPitch = counts_to_delta(countsPitch, safeSens, isSpyglass)

        self.yawRemainder = max(-0.5 * step, min(0.5 * step, totalYaw - (countsYaw * step)))
        if not var_pitchClampedAtBoundary:
            self.pitchRemainder = max(-0.5 * step, min(0.5 * step, totalPitch - (countsPitch * step)))
        else:
            self.pitchRemainder = 0.0

        return countsYaw, countsPitch, appliedDeltaYaw, appliedDeltaPitch, self.yawRemainder, self.pitchRemainder, step

# Test 1: testAppliedDeltaIsExactMultipleOfGCDStep
print("Checking testAppliedDeltaIsExactMultipleOfGCDStep...")
for sens in [0.0, 0.1, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0]:
    for isSpyglass in [False, True]:
        gcd = FixedSensitivityGCD_V2()
        step = compute_step(sens, isSpyglass)
        rng = random.Random(42)
        for _ in range(500):
            dY = (rng.random() - 0.5) * 15.0
            dP = (rng.random() - 0.5) * 15.0
            cY, cP, adY, adP, rY, rP, st = gcd.quantize(dY, dP, sens, isSpyglass=isSpyglass)
            if cY != 0:
                ratio = adY / step
                assert abs(ratio - round(ratio)) < 1e-3
            if cP != 0:
                ratio = adP / step
                assert abs(ratio - round(ratio)) < 1e-3
print("PASSED!")

# Test 4: test10000FrameRemainderAccumulatorConstantSubStep
print("Checking test10000FrameRemainderAccumulatorConstantSubStep...")
for sens in [0.0, 0.5, 1.0, 2.0]:
    gcd = FixedSensitivityGCD_V2()
    step = compute_step(sens)
    sub = step * 0.237
    cumY = 0.0
    cumP = 0.0
    for f in range(10000):
        cY, cP, adY, adP, rY, rP, st = gcd.quantize(sub, sub, sens)
        cumY += adY
        cumP += adP
        assert abs(rY) <= 0.5 * step + 1e-9
        assert abs(rP) <= 0.5 * step + 1e-9
    totalD = sub * 10000
    assert abs(cumY - totalD) <= 0.5 * step + 1e-4
    assert abs(cumP - totalD) <= 0.5 * step + 1e-4
print("PASSED!")

# Test 5: Sinusoidal
print("Checking Sinusoidal...")
gcd = FixedSensitivityGCD_V2()
step = compute_step(0.5)
for f in range(10000):
    t = f * (1.0 / 144.0)
    dY = math.sin(t * 3.7) * 0.42
    dP = math.sin(t * 5.1) * 0.28
    cY, cP, adY, adP, rY, rP, st = gcd.quantize(dY, dP, 0.5)
    assert abs(rY) <= 0.5 * step + 1e-9
    assert abs(rP) <= 0.5 * step + 1e-9
print("PASSED!")

# Test 6: Random Noise
print("Checking Random Noise...")
gcd = FixedSensitivityGCD_V2()
step = compute_step(1.0)
rng = random.Random(12345)
for f in range(10000):
    dY = (rng.random() - 0.5) * 2.0
    dP = (rng.random() - 0.5) * 2.0
    cY, cP, adY, adP, rY, rP, st = gcd.quantize(dY, dP, 1.0)
    assert abs(rY) <= 0.5 * step + 1e-9
    assert abs(rP) <= 0.5 * step + 1e-9
print("PASSED!")
