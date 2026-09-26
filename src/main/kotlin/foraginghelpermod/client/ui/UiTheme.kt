package foraginghelpermod.client.ui

import kotlin.math.pow

object Easing {
	fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)

	fun easeOutCubic(t: Float): Float {
		val x = 1f - clamp01(t)
		return 1f - x * x * x
	}

	fun easeInCubic(t: Float): Float {
		val x = clamp01(t)
		return x * x * x
	}

	fun easeInOutCubic(t: Float): Float {
		val ct = clamp01(t)
		return if (ct < 0.5f) 4f * ct * ct * ct else 1f - (-2f * ct + 2f).pow(3) / 2f
	}

	fun easeOutBack(t: Float): Float {
		val x = clamp01(t)
		val c1 = 1.70158f
		val c3 = c1 + 1f
		return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
	}

	fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * clamp01(t)
}

/**
 * Modern orange and charcoal grey aesthetic inspired by Taunahi+ GUI.
 */
object Colors {
	const val BACKDROP = 0xB008080A.toInt()
	const val PANEL = 0xF8141417.toInt()
	const val PANEL_SIDEBAR = 0xFC101013.toInt()
	const val PANEL_INNER = 0xFF1B1B1F.toInt()
	const val CARD = 0xFF1E1E23.toInt()
	const val CARD_HOVER = 0xFF26262E.toInt()
	const val CARD_BORDER = 0xFF2C2C35.toInt()
	const val BORDER = 0xFF2C2C34.toInt()
	const val BORDER_LIGHT = 0xFF454552.toInt()
	const val ACCENT = 0xFFFF6B00.toInt() // Vibrant Taunahi Orange
	const val ACCENT_BRIGHT = 0xFFFF881A.toInt()
	const val ACCENT_DIM = 0xFFB34A00.toInt()
	const val ACCENT_BG = 0x33FF6B00.toInt()
	const val TEXT = 0xFFF5F6FA.toInt()
	const val TEXT_MUTED = 0xFF9595A2.toInt()
	const val ROW_HOVER = 0x1AFFFFFF
	const val CHECK_BG = 0xFF151518.toInt()
	const val CHECK_BORDER = 0xFF3A3A46.toInt()
	const val CHECK_ON = 0xFFFF6B00.toInt()
	const val TOGGLE_OFF = 0xFF2C2C35.toInt()
	const val TOGGLE_KNOB = 0xFFFFFFFF.toInt()
	const val DANGER = 0xFFFF453A.toInt()
	const val SUCCESS = 0xFF30D158.toInt()
	const val BUTTON_BG = 0xFF23232A.toInt()
	const val BUTTON_HOVER = 0xFF32323C.toInt()

	fun withAlpha(argb: Int, alpha01: Float): Int {
		val a = (Easing.clamp01(alpha01) * ((argb ushr 24) and 0xFF)).toInt().coerceIn(0, 255)
		return (a shl 24) or (argb and 0x00FFFFFF)
	}
}
