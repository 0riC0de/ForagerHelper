package foraginghelpermod

import com.github.foragerhelper.waypoint.WaypointManager
import foraginghelpermod.client.InputController
import foraginghelpermod.client.ManualRouteCommand
import foraginghelpermod.client.ModKeyBindings
import foraginghelpermod.client.hud.StatusHud
import foraginghelpermod.client.hud.DebugWorldOverlay
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import java.io.File

class ForagingHelperClient : ClientModInitializer {
	override fun onInitializeClient() {
		val configDir = runCatching { FabricLoader.getInstance().configDir.toFile() }.getOrNull() ?: File(".")
		WaypointManager.storageFile = File(configDir, "foragerhelper_waypoints.txt")
		WaypointManager.load()

		com.github.foragerhelper.rotation.RotationEngine.register()
		ModKeyBindings.register()
		InputController.register()
		ManualRouteCommand.register()
		StatusHud.register()
		DebugWorldOverlay.register()
	}
}
