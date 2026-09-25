package com.github.foragerhelper.rotation

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * High-performance, anti-cheat compliant camera rotation engine.
 *
 * Decouples camera orientation from the 20 TPS client tick rate, executing
 * on Fabric WorldRenderEvents.START_MAIN at high monitor refresh rates (60/144/240Hz+).
 *
 * Eliminates tick interpolation fighting by advancing player.yaw and player.lastYaw
 * synchronously via vanilla changeLookDirection using mouse sensitivity GCD counts.
 */
interface RotationEngine {
    fun setTarget(focusPoint: Vec3d?, snap: Boolean = false)
    fun setTargetAngles(yaw: Float, pitch: Float, snap: Boolean = false)
    fun setPathTangent(tangent: Vec3d?)
    fun onRenderFrame(deltaTimeSeconds: Float, tickProgress: Float)
    fun reset()
    val currentYaw: Float
    val currentPitch: Float

    val targetYaw: Float
    val targetPitch: Float
    val state: RotationState
    val isAimingAtTarget: Boolean

    companion object : RotationEngine {
        private var delegate: RotationEngine = DefaultRotationEngine()

        fun setDelegate(engine: RotationEngine) {
            this.delegate = engine
        }

        fun getDelegate(): RotationEngine = delegate

        fun register() {
            if (delegate is DefaultRotationEngine) {
                (delegate as DefaultRotationEngine).register()
            }
        }

        override fun setTarget(focusPoint: Vec3d?, snap: Boolean) = delegate.setTarget(focusPoint, snap)
        override fun setTargetAngles(yaw: Float, pitch: Float, snap: Boolean) = delegate.setTargetAngles(yaw, pitch, snap)
        override fun setPathTangent(tangent: Vec3d?) = delegate.setPathTangent(tangent)
        override fun onRenderFrame(deltaTimeSeconds: Float, tickProgress: Float) = delegate.onRenderFrame(deltaTimeSeconds, tickProgress)
        override fun reset() = delegate.reset()

        override val currentYaw: Float get() = delegate.currentYaw
        override val currentPitch: Float get() = delegate.currentPitch
        override val targetYaw: Float get() = delegate.targetYaw
        override val targetPitch: Float get() = delegate.targetPitch
        override val state: RotationState get() = delegate.state
        override val isAimingAtTarget: Boolean get() = delegate.isAimingAtTarget
    }
}

/**
 * Lifecycle states of the rotation engine.
 */
enum class RotationState {
    IDLE,
    PATH_TANGENT,
    BLENDING,
    TARGET_FOCUS,
    MANUAL_ANGLES
}

/**
 * Production implementation of RotationEngine with spring smoothing,
 * mouse sensitivity GCD quantization, and Hermite cubic focus blending.
 */
class DefaultRotationEngine(
    val springSmoother: SpringSmoother = SpringSmoother(omega = 18.0f, maxVelocity = 720.0f),
    val sensitivityGcd: SensitivityGCD = SensitivityGCD(),
    var reachDistance: Double = 4.5,
    var blendWindowDistance: Double = 2.5,
    var aimingToleranceDegrees: Float = 3.0f,
    private val clientProvider: () -> MinecraftClient? = { runCatching { MinecraftClient.getInstance() }.getOrNull() }
) : RotationEngine {

    var targetFocusPoint: Vec3d? = null
        private set

    var pathTangentVector: Vec3d? = null
        private set

    private var manualTargetYaw: Float = Float.NaN
    private var manualTargetPitch: Float = Float.NaN

    private var lastFrameNanos: Long = 0L
    private var isRegistered = false

    // Offline / test simulation parameters
    var simulatedPlayerEyePos: Vec3d = Vec3d(0.0, 1.62, 0.0)
    var simulatedSensitivity: Double = 0.5
    var simulatedIsSpyglass: Boolean = false
    var onQuantizedMovement: ((QuantizedRotation) -> Unit)? = null

    override var currentYaw: Float = 0.0f
        private set
    override var currentPitch: Float = 0.0f
        private set

    override var targetYaw: Float = 0.0f
        private set
    override var targetPitch: Float = 0.0f
        private set

    override var state: RotationState = RotationState.IDLE
        private set

    override val isAimingAtTarget: Boolean
        get() {
            if (state != RotationState.TARGET_FOCUS && state != RotationState.BLENDING) return false
            val yawDiff = abs(MathHelper.wrapDegrees(currentYaw - targetYaw))
            val pitchDiff = abs(currentPitch - targetPitch)
            return yawDiff <= aimingToleranceDegrees && pitchDiff <= aimingToleranceDegrees
        }

    /**
     * Registers the render frame hook on Fabric API WorldRenderEvents.START_MAIN.
     */
    fun register() {
        if (isRegistered) return
        isRegistered = true

        WorldRenderEvents.START_MAIN.register { _ ->
            val client = clientProvider() ?: return@register
            val player = client.player ?: return@register
            if (client.world == null || client.currentScreen != null) {
                lastFrameNanos = 0L
                return@register
            }

            val now = System.nanoTime()
            val deltaSeconds = if (lastFrameNanos == 0L) {
                val dynamicDeltaTicks = client.renderTickCounter?.dynamicDeltaTicks ?: 1.0f
                (dynamicDeltaTicks * 0.05f).coerceIn(0.001f, 0.05f)
            } else {
                val elapsed = (now - lastFrameNanos) / 1_000_000_000.0f
                elapsed.coerceIn(0.0001f, 0.1f)
            }
            lastFrameNanos = now

            val tickProgress = client.renderTickCounter?.getTickProgress(true) ?: 1.0f
            onRenderFrame(deltaSeconds, tickProgress)
        }
    }

    /**
     * Configures simulated state for headless offline unit testing.
     */
    fun setSimulatedState(
        yaw: Float,
        pitch: Float,
        eyePos: Vec3d = Vec3d(0.0, 1.62, 0.0),
        sensitivity: Double = 0.5,
        isSpyglass: Boolean = false
    ) {
        val wrappedYaw = MathHelper.wrapDegrees(yaw)
        val clampedPitch = MathHelper.clamp(pitch, -89.9f, 89.9f)
        currentYaw = wrappedYaw
        currentPitch = clampedPitch
        targetYaw = wrappedYaw
        targetPitch = clampedPitch
        simulatedPlayerEyePos = eyePos
        simulatedSensitivity = sensitivity
        simulatedIsSpyglass = isSpyglass
        springSmoother.reset(wrappedYaw, clampedPitch)
        sensitivityGcd.reset()
    }

    override fun setTarget(focusPoint: Vec3d?, snap: Boolean) {
        this.targetFocusPoint = focusPoint
        this.manualTargetYaw = Float.NaN
        this.manualTargetPitch = Float.NaN

        if (snap && focusPoint != null) {
            val player = clientProvider()?.player
            val eye = player?.eyePos ?: simulatedPlayerEyePos
            val (yaw, pitch) = calculateTargetAngles(eye, focusPoint)
            snapTo(player, yaw, pitch)
        }
    }

    override fun setTargetAngles(yaw: Float, pitch: Float, snap: Boolean) {
        val wrappedYaw = MathHelper.wrapDegrees(yaw)
        val clampedPitch = MathHelper.clamp(pitch, -89.9f, 89.9f)
        this.manualTargetYaw = wrappedYaw
        this.manualTargetPitch = clampedPitch
        this.targetFocusPoint = null

        if (snap) {
            val player = clientProvider()?.player
            snapTo(player, wrappedYaw, clampedPitch)
        }
    }

    override fun setPathTangent(tangent: Vec3d?) {
        this.pathTangentVector = tangent
    }

    override fun reset() {
        val player = clientProvider()?.player
        val yaw = player?.yaw ?: currentYaw
        val pitch = player?.pitch ?: currentPitch

        targetFocusPoint = null
        pathTangentVector = null
        manualTargetYaw = Float.NaN
        manualTargetPitch = Float.NaN
        lastFrameNanos = 0L
        state = RotationState.IDLE

        currentYaw = MathHelper.wrapDegrees(yaw)
        currentPitch = MathHelper.clamp(pitch, -89.9f, 89.9f)
        targetYaw = currentYaw
        targetPitch = currentPitch

        springSmoother.reset(currentYaw, currentPitch)
        sensitivityGcd.reset()
    }

    private fun snapTo(player: ClientPlayerEntity?, yaw: Float, pitch: Float) {
        val wrappedYaw = MathHelper.wrapDegrees(yaw)
        val clampedPitch = MathHelper.clamp(pitch, -89.9f, 89.9f)

        if (player != null) {
            player.yaw = wrappedYaw
            player.lastYaw = wrappedYaw
            player.pitch = clampedPitch
            player.lastPitch = clampedPitch
            player.headYaw = wrappedYaw
            player.bodyYaw = wrappedYaw
        }

        currentYaw = wrappedYaw
        currentPitch = clampedPitch
        targetYaw = wrappedYaw
        targetPitch = clampedPitch

        springSmoother.reset(wrappedYaw, clampedPitch)
        sensitivityGcd.reset()
    }

    override fun onRenderFrame(deltaTimeSeconds: Float, tickProgress: Float) {
        val client = clientProvider()
        val player = client?.player

        // 1. External discontinuity detection (e.g. server teleportation, manual mouse input)
        if (player != null) {
            val angleMismatch = abs(MathHelper.wrapDegrees(player.yaw - currentYaw))
            val pitchMismatch = abs(player.pitch - currentPitch)
            if (angleMismatch > 5.0f || pitchMismatch > 5.0f) {
                currentYaw = MathHelper.wrapDegrees(player.yaw)
                currentPitch = MathHelper.clamp(player.pitch, -89.9f, 89.9f)
                springSmoother.reset(currentYaw, currentPitch)
                sensitivityGcd.reset()
            }
        }

        val eyePos = player?.eyePos ?: simulatedPlayerEyePos
        val sensitivity = if (client != null && player != null) {
            client.options.mouseSensitivity.value
        } else {
            simulatedSensitivity
        }
        val isSpyglass = player?.isUsingSpyglass ?: simulatedIsSpyglass
        val currentPitchVal = player?.pitch ?: currentPitch

        // 2. Resolve target angles and active state
        val (resolvedYaw, resolvedPitch) = resolveTargetAngles(eyePos)
        targetYaw = resolvedYaw
        targetPitch = resolvedPitch

        // If IDLE and spring has settled, avoid generating unnecessary micro-rotations
        if (state == RotationState.IDLE && springSmoother.isAtRest) {
            return
        }

        // 3. Step spring dynamics
        val delta = springSmoother.update(targetYaw, targetPitch, deltaTimeSeconds)

        // 4. Quantize to mouse sensitivity GCD
        val quantized = sensitivityGcd.quantize(
            desiredDeltaYaw = delta.deltaYaw.toDouble(),
            desiredDeltaPitch = delta.deltaPitch.toDouble(),
            sensitivity = sensitivity,
            isSpyglass = isSpyglass,
            currentPitch = currentPitchVal
        )

        onQuantizedMovement?.invoke(quantized)

        // 5. Apply quantized delta via vanilla changeLookDirection or simulated state
        if (player != null) {
            if (quantized.hasMovement) {
                val gcdMultiplier = SensitivityGCD.computeGcdMultiplier(sensitivity, isSpyglass)
                val dx = quantized.countsYaw.toDouble() * gcdMultiplier
                val dy = quantized.countsPitch.toDouble() * gcdMultiplier
                player.changeLookDirection(dx, dy)
            }
            currentYaw = MathHelper.wrapDegrees(player.yaw)
            currentPitch = MathHelper.clamp(player.pitch, -89.9f, 89.9f)
        } else {
            if (quantized.hasMovement) {
                currentYaw = MathHelper.wrapDegrees(currentYaw + quantized.appliedDeltaYaw)
                currentPitch = MathHelper.clamp(currentPitch + quantized.appliedDeltaPitch, -89.9f, 89.9f)
            }
        }
    }

    private fun resolveTargetAngles(eyePos: Vec3d): Pair<Float, Float> {
        // Manual override takes precedence
        if (!manualTargetYaw.isNaN() && !manualTargetPitch.isNaN()) {
            state = RotationState.MANUAL_ANGLES
            return Pair(manualTargetYaw, manualTargetPitch)
        }

        val focus = targetFocusPoint
        val tangent = pathTangentVector

        // Case A: Neither is set -> IDLE (relax spring to current heading)
        if (focus == null && tangent == null) {
            state = RotationState.IDLE
            return Pair(currentYaw, currentPitch)
        }

        // Case B: Only tangent is set -> PATH_TANGENT
        if (focus == null && tangent != null) {
            state = RotationState.PATH_TANGENT
            return calculateTangentAngles(tangent)
        }

        val (focusYaw, focusPitch) = calculateTargetAngles(eyePos, focus!!)

        // Case C: Only focus is set -> TARGET_FOCUS
        if (tangent == null) {
            state = RotationState.TARGET_FOCUS
            return Pair(focusYaw, focusPitch)
        }

        // Case D: Both tangent and focus are set -> Evaluate Blending Zone
        val (tangentYaw, tangentPitch) = calculateTangentAngles(tangent)
        val distance = eyePos.distanceTo(focus)
        val blendStart = reachDistance + blendWindowDistance

        return when {
            distance >= blendStart -> {
                state = RotationState.PATH_TANGENT
                Pair(tangentYaw, tangentPitch)
            }
            distance <= reachDistance -> {
                state = RotationState.TARGET_FOCUS
                Pair(focusYaw, focusPitch)
            }
            else -> {
                state = RotationState.BLENDING
                val u = ((blendStart - distance) / (blendStart - reachDistance)).coerceIn(0.0, 1.0).toFloat()
                val w = u * u * (3.0f - 2.0f * u) // Hermite cubic smoothstep (zero derivatives at endpoints)

                val deltaYaw = MathHelper.wrapDegrees(focusYaw - tangentYaw)
                val blendedYaw = MathHelper.wrapDegrees(tangentYaw + deltaYaw * w)
                val blendedPitch = MathHelper.clamp(tangentPitch + (focusPitch - tangentPitch) * w, -25.0f, 25.0f)
                Pair(blendedYaw, blendedPitch)
            }
        }
    }

    /**
     * Calculates natural look angles along a path tangent vector.
     * Clamps pitch to [-25.0, 25.0] to prevent staring at the ground or sky during navigation.
     */
    fun calculateTangentAngles(tangent: Vec3d): Pair<Float, Float> {
        val horiz = sqrt(tangent.x * tangent.x + tangent.z * tangent.z)
        if (horiz < 1e-5) {
            return Pair(currentYaw, 0.0f)
        }
        val yaw = MathHelper.wrapDegrees(Math.toDegrees(atan2(-tangent.x, tangent.z)).toFloat())
        val pitch = MathHelper.clamp(Math.toDegrees(-atan2(tangent.y, horiz)).toFloat(), -25.0f, 25.0f)
        return Pair(yaw, pitch)
    }

    /**
     * Calculates exact look angles from eye position to target focus point.
     */
    fun calculateTargetAngles(eye: Vec3d, target: Vec3d): Pair<Float, Float> {
        val dx = target.x - eye.x
        val dy = target.y - eye.y
        val dz = target.z - eye.z
        val horiz = sqrt(dx * dx + dz * dz)

        val yaw = MathHelper.wrapDegrees(Math.toDegrees(atan2(-dx, dz)).toFloat())
        val pitch = MathHelper.clamp(Math.toDegrees(-atan2(dy, horiz)).toFloat(), -89.9f, 89.9f)
        return Pair(yaw, pitch)
    }
}
