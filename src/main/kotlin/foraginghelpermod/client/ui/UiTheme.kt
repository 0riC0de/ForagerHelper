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
 * Modern reddish-grey / charcoal slate aesthetic inspired by Taunahi GUI.
 */
object Colors {
	const val BACKDROP = 0xD8100C0E.toInt()
	const val PANEL = 0xF5161315.toInt()
	const val PANEL_INNER = 0xFF20191D.toInt()
	const val BORDER = 0xFF3D252C.toInt()
	const val BORDER_LIGHT = 0xFF58323E.toInt()
	const val ACCENT = 0xFFE53935.toInt()
	const val ACCENT_BRIGHT = 0xFFFF5252.toInt()
	const val ACCENT_DIM = 0xFF8E2424.toInt()
	const val ACCENT_BG = 0x30E53935.toInt()
	const val TEXT = 0xFFF5F6FA.toInt()
	const val TEXT_MUTED = 0xFFA8989E.toInt()
	const val ROW_HOVER = 0x22FFFFFF
	const val CHECK_BG = 0xFF140F12.toInt()
	const val CHECK_BORDER = 0xFF4A323A.toInt()
	const val CHECK_ON = 0xFFE53935.toInt()
	const val DANGER = 0xFFFF453A.toInt()
	const val SUCCESS = 0xFF30D158.toInt()
	const val BUTTON_BG = 0xFF2A1C22.toInt()
	const val BUTTON_HOVER = 0xFF3E2832.toInt()

	fun withAlpha(argb: Int, alpha01: Float): Int {
		val a = (Easing.clamp01(alpha01) * ((argb ushr 24) and 0xFF)).toInt().coerceIn(0, 255)
		return (a shl 24) or (argb and 0x00FFFFFF)
	}
}
