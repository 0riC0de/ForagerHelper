package foraginghelpermod.client.scan

import kotlin.math.sqrt

/**
 * Ranks tree clusters for selection.
 * Prefer-small favors finishing trees faster (more whole-tree bonuses);
 * prefer-large does the opposite. Distance is a soft penalty.
 */
object TreeScorer {
	/** How heavily distance (blocks) pulls against size preference. */
	const val DISTANCE_WEIGHT: Double = 0.35

	fun score(cluster: TreeCluster, preferSmall: Boolean): Double {
		val sizeTerm = if (preferSmall) cluster.size.toDouble() else -cluster.size.toDouble()
		val distance = sqrt(cluster.distanceSq)
		return sizeTerm + DISTANCE_WEIGHT * distance
	}

	fun pickBest(clusters: List<TreeCluster>, preferSmall: Boolean): TreeCluster? =
		clusters.minByOrNull { score(it, preferSmall) }
}
