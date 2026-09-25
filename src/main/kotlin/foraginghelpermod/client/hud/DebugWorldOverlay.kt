package foraginghelpermod.client.hud

import com.github.foragerhelper.movement.MovementController
import com.github.foragerhelper.target.BlockTarget
import com.github.foragerhelper.target.PositionTarget
import foraginghelpermod.client.HelperConfig
import foraginghelpermod.client.InputController
import foraginghelpermod.client.path.WalkController
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.DrawStyle
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.debug.gizmo.GizmoDrawing

/** World-space explanation of the currently selected route and teleport decision. */
object DebugWorldOverlay {
	fun register() {
		WorldRenderEvents.END_EXTRACTION.register { context ->
			val client = MinecraftClient.getInstance()
			val hasActiveRoute = MovementController.isNavigating ||
				MovementController.currentWaypoints.isNotEmpty() ||
				HelperConfig.manualRouteGoal != null ||
				WalkController.path.isNotEmpty()
			if ((!HelperConfig.enabled && !hasActiveRoute) || !HelperConfig.showPathOverlay || client.player == null) return@register

			context.worldRenderer().startDrawingGizmos().use {
				val waypoints = MovementController.currentWaypoints
				if (waypoints.isNotEmpty()) {
					val currentIndex = MovementController.currentWaypointIndex
					waypoints.forEachIndexed { index, wp ->
						val color = if (index == currentIndex) 0xFFFFA000.toInt() else 0xFF199FFF.toInt()
						drawPointBox(wp, color)
					}
					for (i in 0 until waypoints.size - 1) {
						val p1 = waypoints[i].add(0.0, 0.15, 0.0)
						val p2 = waypoints[i + 1].add(0.0, 0.15, 0.0)
						val lineColor = when {
							i < currentIndex -> 0x66199FFF.toInt()
							i == currentIndex -> 0xFFFFA000.toInt()
							else -> 0xFFE53935.toInt()
						}
						GizmoDrawing.line(p1, p2, lineColor, 3.0f).ignoreOcclusion().withLifespan(2)
					}
				} else {
					val currentIndex = WalkController.pathIndex
					WalkController.path.forEachIndexed { index, pos ->
						val color = if (index == currentIndex) 0xFFFFA000.toInt() else 0xFF199FFF.toInt()
						drawBox(pos, color)
					}
					for (i in 0 until WalkController.path.size - 1) {
						val p1 = Vec3d(WalkController.path[i].x + 0.5, WalkController.path[i].y + 0.15, WalkController.path[i].z + 0.5)
						val p2 = Vec3d(WalkController.path[i + 1].x + 0.5, WalkController.path[i + 1].y + 0.15, WalkController.path[i + 1].z + 0.5)
						val lineColor = if (i == currentIndex) 0xFFFFA000.toInt() else 0xFFE53935.toInt()
						GizmoDrawing.line(p1, p2, lineColor, 2.5f).ignoreOcclusion().withLifespan(2)
					}
				}

				val activeTarget = MovementController.activeTarget
				if (activeTarget is BlockTarget) {
					drawBox(activeTarget.blockPos, 0xFFFF3030.toInt())
				} else if (activeTarget is PositionTarget) {
					drawPointBox(activeTarget.position, 0xFFB060FF.toInt())
				} else {
					InputController.targetLog?.let { drawBox(it, 0xFFFF3030.toInt()) }
					HelperConfig.manualRouteGoal?.let { drawBox(it, 0xFFB060FF.toInt()) }
				}

				WalkController.etherWarpTarget?.let { drawBox(it, 0xFF30FF70.toInt()) }
			}
		}
	}

	private fun drawBox(pos: BlockPos, color: Int) {
		GizmoDrawing.box(pos, DrawStyle.stroked(color, 2.0f)).ignoreOcclusion().withLifespan(2)
	}

	private fun drawPointBox(vec: Vec3d, color: Int) {
		val box = Box(vec.x - 0.2, vec.y, vec.z - 0.2, vec.x + 0.2, vec.y + 0.4, vec.z + 0.2)
		GizmoDrawing.box(box, DrawStyle.stroked(color, 2.0f)).ignoreOcclusion().withLifespan(2)
	}
}
