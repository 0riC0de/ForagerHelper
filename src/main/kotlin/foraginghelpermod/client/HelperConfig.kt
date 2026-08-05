package foraginghelpermod.client

/**
 * Client-side foraging helper options (checkbox state for the options HUD).
 */
object HelperConfig {
	var enabled: Boolean = false
	var autoBreak: Boolean = false
	var lookAtTarget: Boolean = false
	var showStatusHud: Boolean = false
	var sneakWhileActive: Boolean = false
	/** ON = favor small trees (more whole-tree bonuses); OFF = favor larger trees. */
	var preferSmallTrees: Boolean = false
	/** Walk toward the selected tree using A* + vanilla movement keys. */
	var autoWalk: Boolean = false
}
