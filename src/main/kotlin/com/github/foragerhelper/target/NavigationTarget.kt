package com.github.foragerhelper.target

import net.minecraft.block.BlockState
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.sqrt

/**
 * Spatial-query abstraction decoupling NavigationTarget implementations from the
 * concrete Minecraft [World] and [ClientPlayerEntity] types — enabling offline
 * headless testing without a running game instance.
 *
 * Mirrors the [com.github.foragerhelper.path.PathEnvironment] pattern from M2.
 */
interface TargetEnvironment {

    /** Returns the [BlockState] at [pos], or null if unpopulated or headless. */
    fun getBlockState(pos: BlockPos): BlockState? = null

    /** Returns true if the block at [pos] is air or structurally empty. */
    fun isAir(pos: BlockPos): Boolean = getBlockState(pos)?.isAir ?: false

    /** Headless target block check (returns true if pos matches the sought target). */
    fun isTargetBlock(pos: BlockPos): Boolean = false

    /** Player foot position. */
    val playerPos: Vec3d

    /** Player eye position (foot + ~1.62 m). */
    val playerEyePos: Vec3d

    /** Player's current reach distance (4.5 survival, 6.0 creative). */
    val reachDistance: Double

    /**
     * Returns all [LivingEntity] instances whose bounding boxes overlap [box].
     * Tests may return empty.
     */
    fun getLivingEntitiesInBox(box: Box): List<LivingEntity> = emptyList()
}

/**
 * Production [TargetEnvironment] wrapping a real Minecraft [World] and player.
 */
class WorldTargetEnvironment(
    val world: World,
    val player: ClientPlayerEntity
) : TargetEnvironment {

    override fun getBlockState(pos: BlockPos): BlockState = world.getBlockState(pos)
    override fun isAir(pos: BlockPos): Boolean = world.getBlockState(pos).isAir

    override val playerPos: Vec3d get() = Vec3d(player.x, player.y, player.z)
    override val playerEyePos: Vec3d get() = player.eyePos

    override val reachDistance: Double get() =
        if (player.abilities.creativeMode) 6.0 else 4.5

    override fun getLivingEntitiesInBox(box: Box): List<LivingEntity> {
        return world.getEntitiesByClass(LivingEntity::class.java, box) { !it.isRemoved && !it.isDead }
    }
}

/**
 * Sealed polymorphic navigation-goal hierarchy.
 *
 * Implementations answer six spatial questions using a lightweight [TargetEnvironment]
 * instead of raw Minecraft [World]/[ClientPlayerEntity] — enabling fully headless tests.
 */
sealed interface NavigationTarget {

    /** Returns the 3D navigation goal (feet level), or null if invalid. */
    fun getTargetPos(env: TargetEnvironment): Vec3d?

    /** Returns the camera focus point, or null if unavailable. */
    fun getFocusPoint(env: TargetEnvironment): Vec3d?

    /** Returns true if the player is within interaction reach. */
    fun isInReach(env: TargetEnvironment): Boolean

    /** Returns true when the goal is fully satisfied and may be dismissed. */
    fun isCompleted(env: TargetEnvironment): Boolean

    /** Returns true while the target remains a valid candidate. */
    fun isValid(env: TargetEnvironment): Boolean

    /** Human-readable one-line status for HUD display. */
    fun describeStatus(env: TargetEnvironment): String

    // -------------------------------------------------------------------
    // Minecraft-world convenience shims (used by production integration)
    // -------------------------------------------------------------------

    /** Convenience overload: create a [WorldTargetEnvironment] from production args. */
    fun getTargetPos(world: World, player: ClientPlayerEntity): Vec3d? =
        getTargetPos(WorldTargetEnvironment(world, player))

    fun getFocusPoint(world: World, player: ClientPlayerEntity): Vec3d? =
        getFocusPoint(WorldTargetEnvironment(world, player))

    fun isInReach(world: World, player: ClientPlayerEntity): Boolean =
        isInReach(WorldTargetEnvironment(world, player))

    fun isCompleted(world: World, player: ClientPlayerEntity): Boolean =
        isCompleted(WorldTargetEnvironment(world, player))

    fun isValid(world: World, player: ClientPlayerEntity): Boolean =
        isValid(WorldTargetEnvironment(world, player))

    fun describeStatus(world: World, player: ClientPlayerEntity): String =
        describeStatus(WorldTargetEnvironment(world, player))

    fun describeStatus(player: ClientPlayerEntity): String =
        describeStatus(object : TargetEnvironment {
            override fun getBlockState(pos: BlockPos): BlockState? = null
            override fun isAir(pos: BlockPos): Boolean = true
            override val playerPos: Vec3d get() = Vec3d(player.x, player.y, player.z)
            override val playerEyePos: Vec3d get() = player.eyePos
            override val reachDistance: Double get() = 4.5
        })

    fun isInReach(player: ClientPlayerEntity, reachDistance: Double): Boolean =
        isInReach(object : TargetEnvironment {
            override fun getBlockState(pos: BlockPos): BlockState? = null
            override fun isAir(pos: BlockPos): Boolean = true
            override val playerPos: Vec3d get() = Vec3d(player.x, player.y, player.z)
            override val playerEyePos: Vec3d get() = player.eyePos
            override val reachDistance: Double get() = reachDistance
        })
}

// =============================================================================
// Concrete target implementations
// =============================================================================

/**
 * A block-position navigation target for foraging/mining.
 *
 * Navigation goal: centre top of the block.
 * Focus point: closest block face to the player eye.
 * Completion: block is gone (mined/harvested).
 *
 * @param blockPos          Target block position.
 * @param expectedBlockTest Optional predicate; null accepts any non-air block.
 * @param standOffsetY      Added to block top for navigation goal Y.
 * @param headlessPredicate Optional predicate for headless testing without BlockState.
 */
data class BlockTarget(
    val blockPos: BlockPos,
    val expectedBlockTest: ((BlockState) -> Boolean)? = null,
    val standOffsetY: Double = 0.0,
    val headlessPredicate: ((BlockPos, TargetEnvironment) -> Boolean)? = null
) : NavigationTarget {

    override fun getTargetPos(env: TargetEnvironment): Vec3d? {
        if (!isValid(env)) return null
        return Vec3d(blockPos.x + 0.5, blockPos.y + 1.0 + standOffsetY, blockPos.z + 0.5)
    }

    override fun getFocusPoint(env: TargetEnvironment): Vec3d? {
        if (!isValid(env)) return null
        val eyePos = env.playerEyePos
        val cx = blockPos.x + 0.5
        val cy = blockPos.y + 0.5
        val cz = blockPos.z + 0.5
        val dx = eyePos.x - cx
        val dy = eyePos.y - cy
        val dz = eyePos.z - cz
        val ax = Math.abs(dx); val ay = Math.abs(dy); val az = Math.abs(dz)
        return when {
            ax >= ay && ax >= az -> Vec3d(blockPos.x + if (dx > 0) 1.0 else 0.0, cy, cz)
            ay >= ax && ay >= az -> Vec3d(cx, blockPos.y + if (dy > 0) 1.0 else 0.0, cz)
            else                 -> Vec3d(cx, cy, blockPos.z + if (dz > 0) 1.0 else 0.0)
        }
    }

    override fun isInReach(env: TargetEnvironment): Boolean {
        val centre = Vec3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        val r = env.reachDistance
        return env.playerEyePos.squaredDistanceTo(centre) <= r * r
    }

    override fun isCompleted(env: TargetEnvironment): Boolean = !isValid(env)

    override fun isValid(env: TargetEnvironment): Boolean {
        if (env.isAir(blockPos)) return false
        val state = env.getBlockState(blockPos)
        if (state != null) {
            return expectedBlockTest?.invoke(state) ?: true
        }
        if (headlessPredicate != null) {
            return headlessPredicate.invoke(blockPos, env)
        }
        return env.isTargetBlock(blockPos)
    }

    override fun describeStatus(env: TargetEnvironment): String {
        val dist = sqrt(env.playerEyePos.squaredDistanceTo(
            Vec3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        ))
        return "BlockTarget(${blockPos.x},${blockPos.y},${blockPos.z}) dist=%.1fm".format(dist)
    }
}

/**
 * An entity navigation target for mob tracking and wildlife interaction.
 *
 * Navigation goal: entity foot position.
 * Focus point: entity eye position (or vertical centre for non-living entities).
 * Completion: player is within [interactRadius] of the entity.
 *
 * @param entity         The tracked entity.
 * @param interactRadius Radius at which interaction is possible.
 */
data class EntityTarget(
    val entity: Entity,
    val interactRadius: Double = 2.5
) : NavigationTarget {

    override fun getTargetPos(env: TargetEnvironment): Vec3d? {
        if (!isValid(env)) return null
        return Vec3d(entity.x, entity.y, entity.z)
    }

    override fun getFocusPoint(env: TargetEnvironment): Vec3d? {
        if (!isValid(env)) return null
        return if (entity is LivingEntity) entity.eyePos
               else Vec3d(entity.x, entity.y + entity.height / 2.0, entity.z)
    }

    override fun isInReach(env: TargetEnvironment): Boolean {
        val focus = getFocusPoint(env) ?: return false
        val r = env.reachDistance
        return env.playerEyePos.squaredDistanceTo(focus) <= r * r
    }

    override fun isCompleted(env: TargetEnvironment): Boolean {
        if (!isValid(env)) return true
        return sqrt(env.playerPos.squaredDistanceTo(Vec3d(entity.x, entity.y, entity.z))) <= interactRadius
    }

    override fun isValid(env: TargetEnvironment): Boolean {
        if (entity.isRemoved) return false
        if (entity is LivingEntity && entity.isDead) return false
        return true
    }

    override fun describeStatus(env: TargetEnvironment): String {
        val dist = sqrt(env.playerPos.squaredDistanceTo(Vec3d(entity.x, entity.y, entity.z)))
        return "EntityTarget(${entity.type.name.string} #${entity.id}) dist=%.1fm".format(dist)
    }
}

/**
 * A free-position navigation target for waypoint navigation.
 *
 * Navigation goal: [position].
 * Focus point: [position] raised by [focusHeightBias].
 * Completion: player foot is within [arrivalRadius].
 * Validity: always true (no external dependency).
 *
 * @param position        3D destination.
 * @param arrivalRadius   Arrival threshold.
 * @param focusHeightBias Y offset applied to the focus point (default ~eye level).
 */
data class PositionTarget(
    val position: Vec3d,
    val arrivalRadius: Double = 1.0,
    val focusHeightBias: Double = 0.9
) : NavigationTarget {

    override fun getTargetPos(env: TargetEnvironment): Vec3d = position

    override fun getFocusPoint(env: TargetEnvironment): Vec3d =
        Vec3d(position.x, position.y + focusHeightBias, position.z)

    override fun isInReach(env: TargetEnvironment): Boolean {
        val r = env.reachDistance
        return env.playerPos.squaredDistanceTo(position) <= r * r
    }

    override fun isCompleted(env: TargetEnvironment): Boolean {
        val dx = env.playerPos.x - position.x
        val dz = env.playerPos.z - position.z
        val dy = env.playerPos.y - position.y
        val horizDistSq = dx * dx + dz * dz
        val rSq = arrivalRadius * arrivalRadius
        if (horizDistSq + dy * dy <= rSq) return true
        val yTolerated = kotlin.math.abs(dy) <= maxOf(arrivalRadius, 1.25)
        if (horizDistSq <= rSq && yTolerated) return true
        // If standing on the exact destination block column (integer block coords), tolerate within block boundaries
        val playerBlockX = kotlin.math.floor(env.playerPos.x).toInt()
        val playerBlockZ = kotlin.math.floor(env.playerPos.z).toInt()
        val targetBlockX = kotlin.math.floor(position.x).toInt()
        val targetBlockZ = kotlin.math.floor(position.z).toInt()
        val onSameBlockColumn = playerBlockX == targetBlockX && playerBlockZ == targetBlockZ
        return onSameBlockColumn && arrivalRadius >= 0.5 && yTolerated
    }

    override fun isValid(env: TargetEnvironment): Boolean = true

    override fun describeStatus(env: TargetEnvironment): String {
        val dist = sqrt(env.playerPos.squaredDistanceTo(position))
        return "PositionTarget(%.1f,%.1f,%.1f) dist=%.1fm".format(position.x, position.y, position.z, dist)
    }
}
