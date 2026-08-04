package foraginghelpermod.client.hud

import foraginghelpermod.OriForaginHelperMod
import foraginghelpermod.client.HelperConfig
import foraginghelpermod.client.InputController
import foraginghelpermod.client.ui.Colors
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.RenderTickCounter

object StatusHud {
	fun register() {
		HudElementRegistry.attachElementBefore(
			VanillaHudElements.CHAT,
			OriForaginHelperMod.id("status"),
			StatusHud::render
		)
	}

	private fun render(context: DrawContext, tickCounter: RenderTickCounter) {
		val client = MinecraftClient.getInstance()
		if (client.player == null || client.options.hudHidden) return
		if (!HelperConfig.showStatusHud) return

		val tr = client.textRenderer
		var y = 4

		val running = HelperConfig.enabled
		val label = if (running) "Foraging Helper: ON" else "Foraging Helper: OFF"
		val color = if (running) Colors.ACCENT else Colors.DANGER
		context.drawTextWithShadow(tr, label, 4, y, color)
		y += 12

		if (!running) return

		val trees = InputController.treeCount
		val dist = InputController.nearestDistance()
		val target = InputController.targetLog

		val scanLine = if (dist != null && target != null) {
			"Trees: $trees  |  Nearest: %.1fm".format(dist)
		} else {
			"Trees: $trees  |  No target"
		}
		context.drawTextWithShadow(tr, scanLine, 4, y, Colors.TEXT_MUTED)
		y += 12

		if (target != null) {
			context.drawTextWithShadow(
				tr,
				"Target: ${target.x}, ${target.y}, ${target.z}",
				4,
				y,
				Colors.TEXT
			)
		}
	}
}
