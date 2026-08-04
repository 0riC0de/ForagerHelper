package foraginghelpermod.client

/**
 * Client-side foraging helper options (checkbox state for the options HUD).
 */
object HelperConfig {
	var enabled: Boolean = false
	var autoBreak: Boolean = true
	var lookAtTarget: Boolean = true
	var showStatusHud: Boolean = true
	var sneakWhileActive: Boolean = false
	/** ON = favor small trees (more whole-tree bonuses); OFF = favor larger trees. */
	var preferSmallTrees: Boolean = true
}
