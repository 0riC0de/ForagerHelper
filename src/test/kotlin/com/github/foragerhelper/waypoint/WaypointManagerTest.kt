package com.github.foragerhelper.waypoint

import net.minecraft.util.math.Vec3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class WaypointManagerTest {

    private lateinit var tempFile: File

    @BeforeEach
    fun setUp() {
        tempFile = File.createTempFile("waypoints_test", ".txt")
        WaypointManager.storageFile = tempFile
        WaypointManager.clear()
    }

    @AfterEach
    fun tearDown() {
        WaypointManager.clear()
        WaypointManager.storageFile = null
        tempFile.delete()
    }

    @Test
    fun testAddAndGetWaypoint() {
        val wp = WaypointManager.addWaypoint("Oak Grove", Vec3d(120.4, 65.0, -45.8))
        assertNotNull(wp)
        assertEquals("Oak Grove", wp.name)
        assertEquals(120.4, wp.x, 0.01)
        assertEquals(65.0, wp.y, 0.01)
        assertEquals(-45.8, wp.z, 0.01)

        val retrieved = WaypointManager.getWaypoint(wp.id)
        assertNotNull(retrieved)
        assertEquals("Oak Grove", retrieved!!.name)
        assertEquals(1, WaypointManager.waypoints.size)
    }

    @Test
    fun testRemoveWaypoint() {
        val wp1 = WaypointManager.addWaypoint("Base", Vec3d(0.0, 64.0, 0.0))
        val wp2 = WaypointManager.addWaypoint("Mine", Vec3d(50.0, 12.0, 50.0))
        assertEquals(2, WaypointManager.waypoints.size)

        val removed = WaypointManager.removeWaypoint(wp1.id)
        assertTrue(removed)
        assertEquals(1, WaypointManager.waypoints.size)
        assertNull(WaypointManager.getWaypoint(wp1.id))
        assertNotNull(WaypointManager.getWaypoint(wp2.id))
    }

    @Test
    fun testFilePersistenceSaveAndLoad() {
        WaypointManager.addWaypoint("Spawn", Vec3d(10.5, 70.0, 20.25))
        WaypointManager.addWaypoint("Camp", Vec3d(-100.0, 63.5, 300.0))
        assertEquals(2, WaypointManager.waypoints.size)

        // Clear in-memory state without deleting file
        WaypointManager.storageFile = null
        val memoryList = WaypointManager.waypoints
        assertEquals(2, memoryList.size)

        // Restore file reference and load
        WaypointManager.storageFile = tempFile
        WaypointManager.load()

        assertEquals(2, WaypointManager.waypoints.size)
        val loadedSpawn = WaypointManager.waypoints.firstOrNull { it.name == "Spawn" }
        assertNotNull(loadedSpawn)
        assertEquals(10.5, loadedSpawn!!.x, 0.01)
        assertEquals(70.0, loadedSpawn.y, 0.01)
        assertEquals(20.25, loadedSpawn.z, 0.01)

        val loadedCamp = WaypointManager.waypoints.firstOrNull { it.name == "Camp" }
        assertNotNull(loadedCamp)
        assertEquals(-100.0, loadedCamp!!.x, 0.01)
    }

    @Test
    fun testClearRemovesAllWaypoints() {
        WaypointManager.addWaypoint("W1", Vec3d(1.0, 2.0, 3.0))
        WaypointManager.addWaypoint("W2", Vec3d(4.0, 5.0, 6.0))
        assertEquals(2, WaypointManager.waypoints.size)

        WaypointManager.clear()
        assertTrue(WaypointManager.waypoints.isEmpty())

        // Verify storage file is also cleared
        WaypointManager.load()
        assertTrue(WaypointManager.waypoints.isEmpty())
    }
}
