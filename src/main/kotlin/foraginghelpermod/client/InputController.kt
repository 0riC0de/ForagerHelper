package foraginghelpermod.client

import foraginghelpermod.client.ui.HelperOptionsScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient

/**
 * Client-tick input loop: opens/closes the options HUD and drives automation when enabled.
 */
object InputController {
	val enabled: Boolean
		get() = HelperConfig.enabled

	fun register() {
		ClientTickEvents.END_CLIENT_TICK.register(::onEndTick)
	}

	fun openOptions() {
		val client = MinecraftClient.getInstance()
		val current = client.currentScreen
		if (current is HelperOptionsScreen) {
			current.requestClose()
			return
		}
		if (current != null) return
		client.setScreen(HelperOptionsScreen())
	}

	private fun onEndTick(client: MinecraftClient) {
		if (client.player == null) {
			HelperConfig.enabled = false
			return
		}

		while (ModKeyBindings.toggleHelper.wasPressed()) {
			openOptions()
		}

		if (!HelperConfig.enabled) return
		if (client.currentScreen is HelperOptionsScreen) return

		// Future foraging automation ticks go here.
	}
}
