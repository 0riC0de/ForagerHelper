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

	fun easeOutBack(t: Float): Float {
		val x = clamp01(t)
		val c1 = 1.70158f
		val c3 = c1 + 1f
		return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
	}

	fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * clamp01(t)
}

object Colors {
	const val BACKDROP = 0xBB0A0C0E.toInt()
	const val PANEL = 0xF012161C.toInt()
	const val PANEL_INNER = 0xFF1A2028.toInt()
	const val BORDER = 0xFF2E3640.toInt()
	const val ACCENT = 0xFF7CBA5A.toInt()
	const val ACCENT_DIM = 0xFF3E6B34.toInt()
	const val TEXT = 0xFFE8EDF2.toInt()
	const val TEXT_MUTED = 0xFF8B95A1.toInt()
	const val ROW_HOVER = 0x28FFFFFF
	const val CHECK_BG = 0xFF0E1218.toInt()
	const val CHECK_BORDER = 0xFF3A4552.toInt()
	const val CHECK_ON = 0xFF7CBA5A.toInt()
	const val DANGER = 0xFFE06C75.toInt()

	fun withAlpha(argb: Int, alpha01: Float): Int {
		val a = (Easing.clamp01(alpha01) * ((argb ushr 24) and 0xFF)).toInt().coerceIn(0, 255)
		return (a shl 24) or (argb and 0x00FFFFFF)
	}
}
