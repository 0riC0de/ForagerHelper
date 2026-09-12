package com.github.foragerhelper.target

import net.minecraft.block.BlockState
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.sqrt

/**
 * Pluggable scanner interface for discovering navigation targets of type [T].
 *
 * Implementations scan a world region and return ranked candidate targets.
 * Scanners are stateless — they are called on demand by [TargetManager] and
 * produce a fresh list each time.
 *
 * @param T The [NavigationTarget] subtype produced by this scanner.
 */
interface TargetScanner<T : NavigationTarget> {

    /**
     * Scans [world] around [origin] within [radius] blocks and returns a list of
     * candidate targets ordered from highest to lowest priority.
     */
    fun scan(world: World, origin: Vec3d, radius: Double): List<T>

    /**
     * Headless scan overload accepting [TargetEnvironment].
     */
    fun scan(env: TargetEnvironment, origin: Vec3d, radius: Double): List<T> = emptyList()

    /**
     * Human-readable name of this scanner, used for debug/HUD display.
     */
    val name: String
}

// =============================================================================
// Concrete scanners
// =============================================================================

/**
 * Scans for log and leaves blocks associated with tree clusters, returning the
 * nearest cluster of accessible log blocks sorted by horizontal proximity to
 * [origin].
 *
 * Prioritises blocks at standing head-height (y+1..y+5) over ground logs to
 * prefer tall tree trunks over fallen wood.
 *
 * @param logTest      Predicate that returns true for log-type blocks.
 * @param maxResults   Maximum number of [BlockTarget] entries to return.
 */
class TreeClusterTargetScanner(
    private val logTest: ((BlockState) -> Boolean)? = null,
    private val maxResults: Int = 8,
    private val headlessFilter: ((BlockPos, TargetEnvironment) -> Boolean)? = null
) : TargetScanner<BlockTarget> {

    override val name: String = "TreeClusterScanner"

    override fun scan(world: World, origin: Vec3d, radius: Double): List<BlockTarget> {
        val env = object : TargetEnvironment {
            override fun getBlockState(pos: BlockPos): BlockState = world.getBlockState(pos)
            override fun isAir(pos: BlockPos): Boolean = world.getBlockState(pos).isAir
            override val playerPos: Vec3d get() = origin
            override val playerEyePos: Vec3d get() = origin
            override val reachDistance: Double get() = 4.5
        }
        return scan(env, origin, radius)
    }

    override fun scan(env: TargetEnvironment, origin: Vec3d, radius: Double): List<BlockTarget> {
        val results = ArrayList<Pair<BlockTarget, Double>>()
        val originPos = BlockPos.ofFloored(origin)
        val r = radius.toInt().coerceAtLeast(1)

        for (dx in -r..r) {
            for (dy in -2..8) {
                for (dz in -r..r) {
                    val pos = originPos.add(dx, dy, dz)
                    val distSq = origin.squaredDistanceTo(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                    if (distSq > radius * radius) continue

                    if (env.isAir(pos)) continue

                    val state = env.getBlockState(pos)
                    val matches = if (state != null && logTest != null) {
                        logTest.invoke(state)
                    } else if (headlessFilter != null) {
                        headlessFilter.invoke(pos, env)
                    } else {
                        env.isTargetBlock(pos)
                    }

                    if (matches) {
                        val target = BlockTarget(pos, logTest, headlessPredicate = headlessFilter)
                        results.add(target to distSq)
                    }
                }
            }
        }

        // Sort by proximity, return top N
        results.sortBy { it.second }
        return results.take(maxResults).map { it.first }
    }
}

/**
 * Scans for living entities of a specified [EntityType] (or any living entity
 * when [entityTypeTest] is null), returning them sorted by proximity.
 *
 * @param entityTypeTest   Optional predicate to filter entity types. Null matches all [LivingEntity].
 * @param requireLineOfSight  If true, only entities with line-of-sight to [origin] are included.
 * @param maxResults          Maximum number of [EntityTarget] entries to return.
 */
class MobTargetScanner(
    private val entityTypeTest: ((Entity) -> Boolean)? = null,
    private val requireLineOfSight: Boolean = false,
    private val interactRadius: Double = 2.5,
    private val maxResults: Int = 4
) : TargetScanner<EntityTarget> {

    override val name: String = "MobTargetScanner"

    override fun scan(world: World, origin: Vec3d, radius: Double): List<EntityTarget> {
        val searchBox = Box(
            origin.x - radius, origin.y - radius, origin.z - radius,
            origin.x + radius, origin.y + radius, origin.z + radius
        )

        @Suppress("UNCHECKED_CAST")
        val entities = world.getEntitiesByClass(
            LivingEntity::class.java, searchBox
        ) { entity ->
            if (entity.isDead || entity.isRemoved) return@getEntitiesByClass false
            if (entityTypeTest != null && !entityTypeTest.invoke(entity)) return@getEntitiesByClass false
            val distSq = origin.squaredDistanceTo(entity.x, entity.y, entity.z)
            if (distSq > radius * radius) return@getEntitiesByClass false
            if (requireLineOfSight) {
                val entityEye = entity.eyePos
                val hit = world.raycast(
                    net.minecraft.world.RaycastContext(
                        origin,
                        entityEye,
                        net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                        net.minecraft.world.RaycastContext.FluidHandling.NONE,
                        entity
                    )
                )
                if (hit.type != net.minecraft.util.hit.HitResult.Type.MISS) return@getEntitiesByClass false
            }
            true
        }

        return entities
            .sortedBy { origin.squaredDistanceTo(it.x, it.y, it.z) }
            .take(maxResults)
            .map { EntityTarget(it, interactRadius) }
    }
}

/**
 * Scans for arbitrary block types matching a custom [blockTest] predicate within
 * [radius] of [origin]. Useful for crop harvesting, ore mining, etc.
 *
 * Results are sorted by proximity.
 *
 * @param blockTest    Predicate that returns true for target block types.
 * @param maxResults   Maximum number of [BlockTarget] entries to return.
 * @param verticalRange Vertical scan range above and below origin.
 */
class CustomBlockTargetScanner(
    private val blockTest: ((BlockState) -> Boolean)? = null,
    private val maxResults: Int = 16,
    private val verticalRange: Int = 4,
    private val headlessFilter: ((BlockPos, TargetEnvironment) -> Boolean)? = null
) : TargetScanner<BlockTarget> {

    override val name: String = "CustomBlockScanner"

    override fun scan(world: World, origin: Vec3d, radius: Double): List<BlockTarget> {
        val env = object : TargetEnvironment {
            override fun getBlockState(pos: BlockPos): BlockState = world.getBlockState(pos)
            override fun isAir(pos: BlockPos): Boolean = world.getBlockState(pos).isAir
            override val playerPos: Vec3d get() = origin
            override val playerEyePos: Vec3d get() = origin
            override val reachDistance: Double get() = 4.5
        }
        return scan(env, origin, radius)
    }

    override fun scan(env: TargetEnvironment, origin: Vec3d, radius: Double): List<BlockTarget> {
        val results = ArrayList<Pair<BlockTarget, Double>>()
        val originPos = BlockPos.ofFloored(origin)
        val r = radius.toInt().coerceAtLeast(1)

        for (dx in -r..r) {
            for (dy in -verticalRange..verticalRange) {
                for (dz in -r..r) {
                    val pos = originPos.add(dx, dy, dz)
                    val distSq = origin.squaredDistanceTo(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                    if (distSq > radius * radius) continue

                    if (env.isAir(pos)) continue

                    val state = env.getBlockState(pos)
                    val matches = if (state != null && blockTest != null) {
                        blockTest.invoke(state)
                    } else if (headlessFilter != null) {
                        headlessFilter.invoke(pos, env)
                    } else {
                        env.isTargetBlock(pos)
                    }

                    if (matches) {
                        val target = BlockTarget(pos, blockTest, headlessPredicate = headlessFilter)
                        results.add(target to distSq)
                    }
                }
            }
        }

        results.sortBy { it.second }
        return results.take(maxResults).map { it.first }
    }
}
