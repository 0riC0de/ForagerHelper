package foraginghelpermod.client.path

import foraginghelpermod.client.scan.TreeScanner
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Finds safe, useful Ether Warp surfaces without selecting dead-end blocks. */
object EtherWarpPlanner {
	private const val MAX_RANGE = 57.0
	private const val MIN_RANGE = 5.0
	private const val SIDE_SEARCH = 3
	private const val HEIGHT_SEARCH = 5

	fun findBestTarget(world: ClientWorld, player: Vec3d, goal: BlockPos): BlockPos? {
		val goalCenter = Vec3d(goal.x + 0.5, goal.y + 0.5, goal.z + 0.5)
		val currentDistance = horizontalDistance(player, goalCenter)
		if (currentDistance > MAX_RANGE || currentDistance.isNaN()) return null

		val dx = goalCenter.x - player.x
		val dz = goalCenter.z - player.z
		val horizontal = sqrt(dx * dx + dz * dz).coerceAtLeast(0.001)
		val dirX = dx / horizontal
		val dirZ = dz / horizontal
		val sideX = -dirZ
		val sideZ = dirX

		var best: Candidate? = null
		for (distance in MAX_RANGE.toInt() downTo MIN_RANGE.toInt()) {
			val baseX = player.x + dirX * distance
			val baseZ = player.z + dirZ * distance
			for (side in -SIDE_SEARCH..SIDE_SEARCH) {
				for (height in -HEIGHT_SEARCH..HEIGHT_SEARCH) {
					val block = BlockPos(
						floor(baseX + sideX * side).toInt(),
						floor(player.y + height).toInt(),
						floor(baseZ + sideZ * side).toInt(),
					)
					if (!isSafeSurface(world, block)) continue
					if (!hasClearRay(world, player, block)) continue

					val landing = Vec3d(block.x + 0.5, block.y + 1.0, block.z + 0.5)
					val remaining = horizontalDistance(landing, goalCenter)
					val progress = currentDistance - remaining
					if (progress < 3.0) continue

					val candidate = Candidate(block.toImmutable(), remaining, progress)
					if (best == null || candidate.remaining < best!!.remaining ||
						(candidate.remaining == best!!.remaining && candidate.progress > best!!.progress)) {
						best = candidate
					}
				}
			}
		}
		return best?.block
	}

	private fun isSafeSurface(world: ClientWorld, block: BlockPos): Boolean {
		val state = world.getBlockState(block)
		if (state.isAir || state.getCollisionShape(world, block).isEmpty) return false
		if (TreeScanner.isLogLike(state) || !world.getFluidState(block).isEmpty) return false

		// Ether Warp lands above the clicked surface. Keep both landing blocks clear.
		for (above in 1..2) {
			val pos = block.up(above)
			if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty || !world.getFluidState(pos).isEmpty) return false
		}
		return true
	}

	private fun hasClearRay(world: ClientWorld, from: Vec3d, target: BlockPos): Boolean {
		val to = Vec3d(target.x + 0.5, target.y + 0.5, target.z + 0.5)
		val dx = to.x - from.x
		val dy = to.y - (from.y + 1.62)
		val dz = to.z - from.z
		val steps = maxOf(abs(dx), abs(dy), abs(dz)).toInt() * 3
		if (steps <= 0) return true
		for (i in 1 until steps) {
			val t = i.toDouble() / steps
			val pos = BlockPos(
				floor(from.x + dx * t).toInt(),
				floor(from.y + 1.62 + dy * t).toInt(),
				floor(from.z + dz * t).toInt(),
			)
			if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty) return false
		}
		return true
	}

	private fun horizontalDistance(a: Vec3d, b: Vec3d): Double {
		val dx = a.x - b.x
		val dz = a.z - b.z
		return sqrt(dx * dx + dz * dz)
	}

	private data class Candidate(
		val block: BlockPos,
		val remaining: Double,
		val progress: Double,
	)
}
