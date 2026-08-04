package foraginghelpermod.client.hud

import foraginghelpermod.OriForaginHelperMod
import foraginghelpermod.client.HelperConfig
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

		val running = HelperConfig.enabled
		val label = if (running) "Foraging Helper: ON" else "Foraging Helper: OFF"
		val color = if (running) Colors.ACCENT else Colors.DANGER

		context.drawTextWithShadow(client.textRenderer, label, 4, 4, color)
	}
}
