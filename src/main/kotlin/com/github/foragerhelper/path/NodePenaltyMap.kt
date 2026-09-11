package com.github.foragerhelper.path

import net.minecraft.util.math.BlockPos
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe spatial penalty storage for 3D A* pathfinding.
 *
 * Implements primitive Long-keyed spatial hashing, radius-1 spatial diffusion with 0.5 falloff,
 * and linear TTL decay (20-second default) to prevent infinite stuck loops.
 */
class NodePenaltyMap(
    val defaultPenalty: Float = 50.0f,
    val defaultDurationMs: Long = 20_000L,
    val diffusionRadius: Int = 1,
    val diffusionFalloff: Float = 0.5f,
    val maxCapacity: Int = 1024
) {

    data class PenaltyRecord(
        val penalty: Float,
        val creationTime: Long,
        val expiryTime: Long
    )

    private val records = ConcurrentHashMap<Long, PenaltyRecord>()

    /**
     * Registers a penalty at [pos] and diffuses a fractional penalty to neighboring blocks.
     */
    fun penalize(
        pos: BlockPos,
        penalty: Float = defaultPenalty,
        durationMs: Long = defaultDurationMs
    ) {
        val cleanPenalty = maxOf(0.0f, penalty)
        if (records.size >= maxCapacity) {
            pruneExpired()
            if (records.size >= maxCapacity) {
                // Remove an arbitrary entry to bound memory
                records.keys().toList().firstOrNull()?.let { records.remove(it) }
            }
        }

        val now = System.currentTimeMillis()
        val expiry = now + durationMs
        records[pos.asLong()] = PenaltyRecord(cleanPenalty, now, expiry)

        if (diffusionRadius > 0 && diffusionFalloff > 0.0f) {
            val diffused = cleanPenalty * diffusionFalloff
            if (diffused > 0.0f) {
                for (dx in -diffusionRadius..diffusionRadius) {
                    for (dz in -diffusionRadius..diffusionRadius) {
                        for (dy in -1..1) {
                            if (dx == 0 && dy == 0 && dz == 0) continue
                            val neighborKey = pos.add(dx, dy, dz).asLong()
                            val existing = records[neighborKey]
                            if (existing == null || existing.penalty < diffused) {
                                records[neighborKey] = PenaltyRecord(diffused, now, expiry)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the active decayed penalty at [pos]. Returns 0.0f if not penalized or expired.
     */
    fun getPenalty(pos: BlockPos): Float {
        val key = pos.asLong()
        val record = records[key] ?: return 0.0f
        val now = System.currentTimeMillis()
        if (now >= record.expiryTime) {
            records.remove(key)
            return 0.0f
        }

        val totalDuration = (record.expiryTime - record.creationTime).toFloat()
        if (totalDuration <= 0f) return record.penalty

        val remainingFraction = (record.expiryTime - now).toFloat() / totalDuration
        return record.penalty * remainingFraction.coerceIn(0.0f, 1.0f)
    }

    /**
     * Returns true if [pos] currently has a non-zero penalty.
     */
    fun hasPenalty(pos: BlockPos): Boolean = getPenalty(pos) > 0.0f

    /**
     * Clears all spatial penalties.
     */
    fun clear() {
        records.clear()
    }

    /**
     * Prunes all expired records from memory.
     */
    fun pruneExpired() {
        val now = System.currentTimeMillis()
        records.entries.removeIf { it.value.expiryTime <= now }
    }

    val size: Int get() = records.size
}
