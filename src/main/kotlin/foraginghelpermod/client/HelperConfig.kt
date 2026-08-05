package foraginghelpermod.client

import net.minecraft.util.math.BlockPos

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
	/** Use a held Aspect of the Void only to recover from a failed route or a void fall. */
	var useAspectOfVoid: Boolean = true
	/** Draw path, target, and Ether Warp decisions in the world for debugging. */
	var showPathOverlay: Boolean = true
	/** Manual destination set by /forageroute x y z. */
	var manualRouteGoal: BlockPos? = null
}
