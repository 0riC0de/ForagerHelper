package foraginghelpermod

import net.fabricmc.api.ModInitializer
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory

object OriForaginHelperMod : ModInitializer {
	const val MOD_ID: String = "oriforaginhelpermod"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		
	}

	fun id(path: String): Identifier
		= Identifier.of(MOD_ID, path)
}
