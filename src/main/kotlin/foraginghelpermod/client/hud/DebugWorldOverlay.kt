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
			if (!HelperConfig.enabled || !HelperConfig.showPathOverlay || client.player == null) return@register

			context.worldRenderer().startDrawingGizmos().use {
				val waypoints = MovementController.currentWaypoints
				if (waypoints.isNotEmpty()) {
					val currentIndex = MovementController.currentWaypointIndex
					waypoints.forEachIndexed { index, wp ->
						val color = if (index == currentIndex) 0xFFFFA000.toInt() else 0xFF199FFF.toInt()
						drawPointBox(wp, color)
					}
				} else {
					WalkController.path.forEachIndexed { index, pos ->
						val color = if (index == WalkController.pathIndex) 0xFFFFA000.toInt() else 0xFF199FFF.toInt()
						drawBox(pos, color)
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
