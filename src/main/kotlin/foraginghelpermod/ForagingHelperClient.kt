package foraginghelpermod

import foraginghelpermod.client.InputController
import foraginghelpermod.client.ModKeyBindings
import foraginghelpermod.client.hud.StatusHud
import net.fabricmc.api.ClientModInitializer

class ForagingHelperClient : ClientModInitializer {
	override fun onInitializeClient() {
		ModKeyBindings.register()
		InputController.register()
		StatusHud.register()
	}
}
