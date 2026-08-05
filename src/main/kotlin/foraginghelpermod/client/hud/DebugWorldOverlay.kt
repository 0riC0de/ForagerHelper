package foraginghelpermod.client.hud

import foraginghelpermod.client.HelperConfig
import foraginghelpermod.client.InputController
import foraginghelpermod.client.path.WalkController
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.DrawStyle
import net.minecraft.util.math.BlockPos
import net.minecraft.world.debug.gizmo.GizmoDrawing

/** World-space explanation of the currently selected route and teleport decision. */
object DebugWorldOverlay {
	fun register() {
		WorldRenderEvents.END_EXTRACTION.register { context ->
			val client = MinecraftClient.getInstance()
			if (!HelperConfig.enabled || !HelperConfig.showPathOverlay || client.player == null) return@register

			context.worldRenderer().startDrawingGizmos().use {
				WalkController.path.forEachIndexed { index, pos ->
					val color = if (index == WalkController.pathIndex) 0xFFFFA000.toInt() else 0xFF199FFF.toInt()
					drawBox(pos, color)
				}
				InputController.targetLog?.let { drawBox(it, 0xFFFF3030.toInt()) }
				HelperConfig.manualRouteGoal?.let { drawBox(it, 0xFFB060FF.toInt()) }
				WalkController.etherWarpTarget?.let { drawBox(it, 0xFF30FF70.toInt()) }
			}
		}
	}

	private fun drawBox(pos: BlockPos, color: Int) {
		GizmoDrawing.box(pos, DrawStyle.stroked(color, 2.0f)).ignoreOcclusion().withLifespan(2)
	}
}
