function step($s) {
    $f = $s * 0.6000000238418579 + 0.20000000298023224
    return ($f * $f * $f) * 8.0 * 0.15
}
function nearestCount($total, $st) {
    return [Math]::Floor($total / $st + 0.5)
}

function quantize($desiredDeltaPitch, $s, [ref]$pitchRemainder, [ref]$currentPitch) {
    $st = step $s
    
    # 1. Anti-windup clamping when pegged at limits
    $effectiveDesired = $desiredDeltaPitch
    if ($currentPitch.Value -ge 90.0 -and $desiredDeltaPitch -gt 0.0) {
        $effectiveDesired = 0.0
        $pitchRemainder.Value = 0.0
    } elseif ($currentPitch.Value -le -90.0 -and $desiredDeltaPitch -lt 0.0) {
        $effectiveDesired = 0.0
        $pitchRemainder.Value = 0.0
    }

    # 2. Accumulate
    $totalPitch = $effectiveDesired + $pitchRemainder.Value

    # 3. Nearest count
    $countsPitch = nearestCount $totalPitch $st

    # 4. Instant camera reversal from boundaries:
    # If pinned near positive boundary and requesting negative pitch, ensure countsPitch < 0
    if ($currentPitch.Value -ge (90.0 - $st) -and $desiredDeltaPitch -lt 0.0 -and $countsPitch -ge 0) {
        $countsPitch = -1
    } elseif ($currentPitch.Value -le (-90.0 + $st) -and $desiredDeltaPitch -gt 0.0 -and $countsPitch -le 0) {
        $countsPitch = 1
    }

    # 5. Pitch boundary over-rotation clamping
    $clampedAtBoundary = $false
    if ($countsPitch -ne 0) {
        $potentialDelta = $countsPitch * $st
        $projected = $currentPitch.Value + $potentialDelta
        if ($projected -gt 90.0) {
            $allowedDelta = 90.0 - $currentPitch.Value
            $countsPitch = [Math]::Max(0, [int][Math]::Floor($allowedDelta / $st))
            $pitchRemainder.Value = 0.0
            $clampedAtBoundary = $true
        } elseif ($projected -lt -90.0) {
            $allowedDelta = -90.0 - $currentPitch.Value
            $countsPitch = [Math]::Min(0, [int][Math]::Ceiling($allowedDelta / $st))
            $pitchRemainder.Value = 0.0
            $clampedAtBoundary = $true
        }
    }

    # 6. Update remainder
    if (-not $clampedAtBoundary) {
        $pitchRemainder.Value = $totalPitch - ($countsPitch * $st)
        # Clamping remainder to [-0.5 * step, 0.5 * step]
        if ($pitchRemainder.Value -gt 0.5 * $st) { $pitchRemainder.Value = 0.5 * $st }
        if ($pitchRemainder.Value -lt -0.5 * $st) { $pitchRemainder.Value = -0.5 * $st }
    } else {
        $pitchRemainder.Value = 0.0
    }

    $appliedDelta = $countsPitch * $st
    $currentPitch.Value += $appliedDelta
    return $countsPitch
}

foreach ($s in @(0.0, 0.5, 1.0, 2.0)) {
    $st = step $s
    [double]$pitchRemainder = 0.0
    [float]$currentPitch = 80.0

    # Downward approach
    for ($frame = 1; $frame -le 1000; $frame++) {
        $c = quantize 0.5 $s ([ref]$pitchRemainder) ([ref]$currentPitch)
        if ($currentPitch -gt 90.00001 -or $currentPitch -lt -90.00001) {
            throw "Failed pitch bounds: $currentPitch at frame $frame"
        }
    }

    # Reversal
    $cRev = quantize -1.0 $s ([ref]$pitchRemainder) ([ref]$currentPitch)
    if ($cRev -ge 0) {
        throw "Failed reversal for sens ${s}: cRev=$cRev pitch=$currentPitch"
    }

    # Upward approach
    for ($frame = 1; $frame -le 1000; $frame++) {
        $c = quantize -0.5 $s ([ref]$pitchRemainder) ([ref]$currentPitch)
        if ($currentPitch -gt 90.00001 -or $currentPitch -lt -90.00001) {
            throw "Failed upward pitch bounds: $currentPitch at frame $frame"
        }
    }

    Write-Host "Sens ${s}: PASS! Final pitch=$currentPitch remainder=$pitchRemainder"
}
