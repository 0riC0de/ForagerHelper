package com.github.foragerhelper.waypoint

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.io.File

/**
 * Represents a saved world destination with coordinate data and metadata.
 */
data class Waypoint(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    var name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val createdAt: Long = System.currentTimeMillis()
) {
    val posVec: Vec3d get() = Vec3d(x, y, z)
    val blockPos: BlockPos get() = BlockPos(x.toInt(), y.toInt(), z.toInt())
}

/**
 * Manages persistent waypoints for navigation with headless test support.
 */
object WaypointManager {
    private val waypointsList = mutableListOf<Waypoint>()
    var storageFile: File? = null

    val waypoints: List<Waypoint>
        get() = waypointsList.toList()

    fun addWaypoint(name: String, pos: Vec3d): Waypoint {
        val cleanName = name.ifBlank { "Waypoint ${waypointsList.size + 1}" }
        val wp = Waypoint(
            name = cleanName,
            x = Math.round(pos.x * 100.0) / 100.0,
            y = Math.round(pos.y * 100.0) / 100.0,
            z = Math.round(pos.z * 100.0) / 100.0
        )
        waypointsList.add(wp)
        save()
        return wp
    }

    fun addWaypoint(waypoint: Waypoint) {
        waypointsList.add(waypoint)
        save()
    }

    fun removeWaypoint(id: String): Boolean {
        val removed = waypointsList.removeIf { it.id == id }
        if (removed) save()
        return removed
    }

    fun getWaypoint(id: String): Waypoint? = waypointsList.firstOrNull { it.id == id }

    fun clear() {
        waypointsList.clear()
        save()
    }

    fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        try {
            waypointsList.clear()
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                    // Format: id|name|x|y|z|createdAt
                    val parts = trimmed.split("|")
                    if (parts.size >= 5) {
                        val id = parts[0]
                        val name = parts[1]
                        val x = parts[2].toDoubleOrNull() ?: continue
                        val y = parts[3].toDoubleOrNull() ?: continue
                        val z = parts[4].toDoubleOrNull() ?: continue
                        val createdAt = if (parts.size >= 6) parts[5].toLongOrNull() ?: System.currentTimeMillis() else System.currentTimeMillis()
                        waypointsList.add(Waypoint(id, name, x, y, z, createdAt))
                    }
                }
            }
        } catch (e: Exception) {
            // Graceful fallback on corrupt file
        }
    }

    fun save() {
        val file = storageFile ?: return
        try {
            file.parentFile?.mkdirs()
            file.bufferedWriter().use { writer ->
                writer.write("# ForagerHelper Waypoints\n")
                for (wp in waypointsList) {
                    writer.write("${wp.id}|${wp.name}|${wp.x}|${wp.y}|${wp.z}|${wp.createdAt}\n")
                }
            }
        } catch (e: Exception) {
            // Graceful fallback
        }
    }
}
