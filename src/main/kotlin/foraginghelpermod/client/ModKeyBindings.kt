package foraginghelpermod.client

import foraginghelpermod.OriForaginHelperMod
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

object ModKeyBindings {
	val CATEGORY: KeyBinding.Category =
		KeyBinding.Category.create(OriForaginHelperMod.id("foraging_helper"))

	lateinit var toggleHelper: KeyBinding
		private set

	fun register() {
		toggleHelper = KeyBindingHelper.registerKeyBinding(
			KeyBinding(
				"key.oriforaginhelpermod.toggle",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_H,
				CATEGORY
			)
		)
	}
}
