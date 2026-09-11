package com.github.foragerhelper.path

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.world.World
import kotlin.math.floor

/**
 * Spatial query abstraction decoupling 3D A* pathfinding and swept-box collision
 * from concrete Minecraft World implementations.
 */
interface PathEnvironment {
    /**
     * Returns true if no solid block collision intersects the given [box].
     */
    fun isPassable(box: Box): Boolean

    /**
     * Returns true if the block at [pos] is a full solid block.
     */
    fun isSolid(pos: BlockPos): Boolean

    /**
     * Returns the continuous standing surface Y elevation for a player standing at [pos],
     * or null if the position is unsupported, in free fall, or obstructed.
     */
    fun getStandHeight(pos: BlockPos): Double?

    /**
     * Returns true if the block at [pos] or its immediate supporting block is hazardous (e.g. lava, fire, cactus).
     */
    fun isHazard(pos: BlockPos): Boolean

    /**
     * Returns all colliding bounding boxes intersecting the given query [box].
     */
    fun getBlockCollisions(box: Box): List<Box> = emptyList()

    /**
     * Returns true if the block at [pos] is a bottom slab.
     */
    fun isBottomSlab(pos: BlockPos): Boolean = false
}

/**
 * Production implementation of [PathEnvironment] backed by a Minecraft [World].
 */
class WorldPathEnvironment(val world: World) : PathEnvironment {

    override fun isPassable(box: Box): Boolean {
        return world.isSpaceEmpty(box)
    }

    override fun isSolid(pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        return state.isSolidBlock(world, pos) || !state.getCollisionShape(world, pos).isEmpty
    }

    override fun getStandHeight(pos: BlockPos): Double? {
        val stateAt = world.getBlockState(pos)
        val shapeAt = stateAt.getCollisionShape(world, pos)
        if (!shapeAt.isEmpty) {
            val maxAtY = shapeAt.boundingBoxes.maxOfOrNull { it.maxY } ?: 0.0
            if (maxAtY in 0.4..0.6) {
                return pos.y + maxAtY
            }
            // Height > 0.6 means the block inside pos blocks standing
            return null
        }

        val stateBelow = world.getBlockState(pos.down())
        val shapeBelow = stateBelow.getCollisionShape(world, pos.down())
        if (shapeBelow.isEmpty) return null
        val maxBelowY = shapeBelow.boundingBoxes.maxOfOrNull { it.maxY } ?: return null
        return pos.down().y + maxBelowY
    }

    override fun isHazard(pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        val stateBelow = world.getBlockState(pos.down())
        return isHazardBlock(state) || isHazardBlock(stateBelow)
    }

    override fun isBottomSlab(pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        val shape = state.getCollisionShape(world, pos)
        if (shape.isEmpty) return false
        val maxBoxY = shape.boundingBoxes.maxOfOrNull { it.maxY } ?: 0.0
        return maxBoxY in 0.4..0.6
    }

    override fun getBlockCollisions(box: Box): List<Box> {
        val list = ArrayList<Box>()
        for (shape in world.getBlockCollisions(null, box)) {
            if (!shape.isEmpty) {
                list.addAll(shape.boundingBoxes)
            }
        }
        return list
    }

    private fun isHazardBlock(state: BlockState): Boolean {
        val block = state.block
        return block == Blocks.LAVA || block == Blocks.FIRE || block == Blocks.SOUL_FIRE ||
               block == Blocks.CACTUS || block == Blocks.POWDER_SNOW || block == Blocks.SWEET_BERRY_BUSH ||
               block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE || block == Blocks.MAGMA_BLOCK
    }
}

/**
 * High-performance offline test implementation of [PathEnvironment] supporting platforms,
 * walls, slabs, stairs, fences, ceilings, custom boxes, and hazards.
 */
class TestWorldGrid : PathEnvironment {

    private val solidBlocks = HashSet<BlockPos>()
    private val bottomSlabs = HashSet<BlockPos>()
    private val topSlabs = HashSet<BlockPos>()
    private val stairs = HashMap<BlockPos, Direction>()
    private val fences = HashSet<BlockPos>()
    private val hazardBlocks = HashSet<BlockPos>()
    private val customBoxes = ArrayList<Box>()

    fun setSolid(pos: BlockPos) {
        solidBlocks.add(pos.toImmutable())
    }

    fun setSolid(x: Int, y: Int, z: Int) = setSolid(BlockPos(x, y, z))

    fun setAir(pos: BlockPos) {
        val p = pos.toImmutable()
        solidBlocks.remove(p)
        bottomSlabs.remove(p)
        topSlabs.remove(p)
        stairs.remove(p)
        fences.remove(p)
        hazardBlocks.remove(p)
    }

    fun setAir(x: Int, y: Int, z: Int) = setAir(BlockPos(x, y, z))

    fun setSlab(pos: BlockPos, top: Boolean = false) {
        val p = pos.toImmutable()
        if (top) {
            topSlabs.add(p)
            bottomSlabs.remove(p)
        } else {
            bottomSlabs.add(p)
            topSlabs.remove(p)
        }
    }

    fun setSlab(x: Int, y: Int, z: Int, top: Boolean = false) = setSlab(BlockPos(x, y, z), top)

    fun setStairs(pos: BlockPos, facing: Direction = Direction.NORTH) {
        stairs[pos.toImmutable()] = facing
    }

    fun setStairs(x: Int, y: Int, z: Int, facing: Direction = Direction.NORTH) =
        setStairs(BlockPos(x, y, z), facing)

    fun setFence(pos: BlockPos) {
        fences.add(pos.toImmutable())
    }

    fun setFence(x: Int, y: Int, z: Int) = setFence(BlockPos(x, y, z))

    fun setCeiling(pos: BlockPos) = setSolid(pos)
    fun setCeiling(x: Int, y: Int, z: Int) = setSolid(x, y, z)

    fun setHazard(pos: BlockPos) {
        hazardBlocks.add(pos.toImmutable())
    }

    fun setHazard(x: Int, y: Int, z: Int) = setHazard(BlockPos(x, y, z))

    fun addCustomBox(box: Box) {
        customBoxes.add(box)
    }

    fun addFlatPlatform(minX: Int, maxX: Int, minZ: Int, maxZ: Int, y: Int) {
        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                setSolid(x, y, z)
            }
        }
    }

    fun addWall(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) {
        val minX = minOf(x1, x2)
        val maxX = maxOf(x1, x2)
        val minY = minOf(y1, y2)
        val maxY = maxOf(y1, y2)
        val minZ = minOf(z1, z2)
        val maxZ = maxOf(z1, z2)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    setSolid(x, y, z)
                }
            }
        }
    }

    fun clear() {
        solidBlocks.clear()
        bottomSlabs.clear()
        topSlabs.clear()
        stairs.clear()
        fences.clear()
        hazardBlocks.clear()
        customBoxes.clear()
    }

    override fun isPassable(box: Box): Boolean {
        return getBlockCollisions(box).none { it.intersects(box) }
    }

    override fun isSolid(pos: BlockPos): Boolean {
        return solidBlocks.contains(pos)
    }

    override fun isBottomSlab(pos: BlockPos): Boolean {
        return bottomSlabs.contains(pos)
    }

    override fun isHazard(pos: BlockPos): Boolean {
        return hazardBlocks.contains(pos) || hazardBlocks.contains(pos.down())
    }

    override fun getStandHeight(pos: BlockPos): Double? {
        if (isHazard(pos)) return null

        // If standing inside a solid block or full obstacle, cannot stand
        if (solidBlocks.contains(pos) || fences.contains(pos) || topSlabs.contains(pos)) {
            return null
        }

        // Slab inside pos (standing on bottom slab surface)
        if (bottomSlabs.contains(pos)) {
            return pos.y + 0.5
        }

        // Stairs inside pos (standing on lower stair step)
        if (stairs.containsKey(pos)) {
            return pos.y + 0.5
        }

        // Check custom boxes at pos
        for (cb in customBoxes) {
            val minX = pos.x.toDouble()
            val maxX = pos.x + 1.0
            val minZ = pos.z.toDouble()
            val maxZ = pos.z + 1.0
            if (cb.minX < maxX && cb.maxX > minX && cb.minZ < maxZ && cb.maxZ > minZ) {
                if (cb.maxY in (pos.y.toDouble())..(pos.y + 0.6)) {
                    return cb.maxY
                }
            }
        }

        // Check ground support underneath pos
        val below = pos.down()
        if (solidBlocks.contains(below)) {
            return pos.y.toDouble()
        }
        if (topSlabs.contains(below)) {
            return pos.y.toDouble()
        }
        if (bottomSlabs.contains(below)) {
            return pos.y - 0.5
        }
        if (stairs.containsKey(below)) {
            return pos.y.toDouble()
        }
        if (fences.contains(below)) {
            return pos.y + 0.5
        }

        // Check custom boxes beneath pos
        for (cb in customBoxes) {
            val minX = pos.x.toDouble()
            val maxX = pos.x + 1.0
            val minZ = pos.z.toDouble()
            val maxZ = pos.z + 1.0
            if (cb.minX < maxX && cb.maxX > minX && cb.minZ < maxZ && cb.maxZ > minZ) {
                if (cb.maxY in (below.y.toDouble())..(pos.y.toDouble())) {
                    return cb.maxY
                }
            }
        }

        return null
    }

    override fun getBlockCollisions(box: Box): List<Box> {
        val result = ArrayList<Box>()
        val minX = floor(box.minX).toInt()
        val maxX = floor(box.maxX).toInt()
        val minY = floor(box.minY).toInt()
        val maxY = floor(box.maxY).toInt()
        val minZ = floor(box.minZ).toInt()
        val maxZ = floor(box.maxZ).toInt()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val p = BlockPos(x, y, z)
                    if (solidBlocks.contains(p)) {
                        result.add(Box(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0))
                    }
                    if (bottomSlabs.contains(p)) {
                        result.add(Box(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 0.5, z + 1.0))
                    }
                    if (topSlabs.contains(p)) {
                        result.add(Box(x.toDouble(), y + 0.5, z.toDouble(), x + 1.0, y + 1.0, z + 1.0))
                    }
                    if (fences.contains(p)) {
                        result.add(Box(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.5, z + 1.0))
                    }
                    val stairFacing = stairs[p]
                    if (stairFacing != null) {
                        // Base bottom half
                        result.add(Box(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 0.5, z + 1.0))
                        // Top step half only blocks horizontal entry from below the lower step
                        if (box.minY < y + 0.5) {
                            when (stairFacing) {
                                Direction.NORTH -> result.add(Box(x.toDouble(), y + 0.5, z.toDouble(), x + 1.0, y + 1.0, z + 0.5))
                                Direction.SOUTH -> result.add(Box(x.toDouble(), y + 0.5, z + 0.5, x + 1.0, y + 1.0, z + 1.0))
                                Direction.WEST -> result.add(Box(x.toDouble(), y + 0.5, z.toDouble(), x + 0.5, y + 1.0, z + 1.0))
                                Direction.EAST -> result.add(Box(x + 0.5, y + 0.5, z.toDouble(), x + 1.0, y + 1.0, z + 1.0))
                                else -> {}
                            }
                        }
                    }
                }
            }
        }

        for (cb in customBoxes) {
            if (cb.intersects(box)) {
                result.add(cb)
            }
        }

        return result
    }
}
