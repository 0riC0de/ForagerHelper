package foraginghelpermod.client.ui

import foraginghelpermod.client.HelperConfig
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.input.KeyInput
import net.minecraft.text.Text
import net.minecraft.util.Util
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * Clean glass-style options panel with open/close scale+fade animation.
 * Inspired by common Fabric helper HUD layouts (dark panel, accent rail, checkbox rows).
 */
class HelperOptionsScreen : Screen(Text.translatable("screen.oriforaginhelpermod.options")) {
	private val options = listOf(
		OptionRow("Enable Helper", "Run foraging automation", { HelperConfig.enabled }, { HelperConfig.enabled = it }),
		OptionRow("Prefer Small Trees", "Favor small trees for more whole-tree bonuses", { HelperConfig.preferSmallTrees }, { HelperConfig.preferSmallTrees = it }),
		OptionRow("Auto Break", "Break foraging targets automatically", { HelperConfig.autoBreak }, { HelperConfig.autoBreak = it }),
		OptionRow("Look at Target", "Face the current foraging target", { HelperConfig.lookAtTarget }, { HelperConfig.lookAtTarget = it }),
		OptionRow("Show Status HUD", "Show ON/OFF status in the corner", { HelperConfig.showStatusHud }, { HelperConfig.showStatusHud = it }),
		OptionRow("Sneak While Active", "Hold sneak while the helper runs", { HelperConfig.sneakWhileActive }, { HelperConfig.sneakWhileActive = it }),
	)

	private var animStartMs = 0L
	private var closing = false
	private var hoverIndex = -1

	override fun init() {
		animStartMs = Util.getMeasuringTimeMs()
		closing = false
	}

	fun requestClose() {
		if (closing) return
		closing = true
		animStartMs = Util.getMeasuringTimeMs()
	}

	override fun close() {
		requestClose()
	}

	override fun shouldPause(): Boolean = false

	override fun shouldCloseOnEsc(): Boolean = true

	override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
		// Custom backdrop is drawn in render().
	}

	override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
		val progress = animationProgress()
		if (closing && progress <= 0.001f) {
			client?.setScreen(null)
			return
		}

		val motion = if (closing) {
			Easing.easeInCubic(1f - progress)
		} else {
			Easing.easeOutCubic(progress)
		}

		val alpha = if (closing) progress else Easing.easeOutCubic(progress)
		val bounce = if (closing) 1f - motion else Easing.easeOutBack(progress).coerceIn(0f, 1.06f)
		val scale = Easing.lerp(0.88f, 1f, bounce.coerceAtMost(1f))
		val slide = Easing.lerp(16f, 0f, if (closing) progress else Easing.easeOutCubic(progress))

		context.fill(0, 0, width, height, Colors.withAlpha(Colors.BACKDROP, alpha * 0.92f))

		val panelW = PANEL_WIDTH
		val panelH = headerHeight() + options.size * ROW_HEIGHT + FOOTER_HEIGHT + 24
		val panelX = (width - panelW) / 2f
		val panelY = (height - panelH) / 2f + slide
		val cx = panelX + panelW / 2f
		val cy = panelY + panelH / 2f

		val matrices = context.matrices
		matrices.pushMatrix()
		matrices.translate(cx, cy)
		matrices.scale(scale, scale)
		matrices.translate(-cx, -cy)

		val localMouse = transformMouse(mouseX, mouseY, cx, cy, scale)
		hoverIndex = hitOptionIndex(localMouse.first, localMouse.second, panelX.roundToInt(), panelY.roundToInt(), panelW, panelH)

		drawPanel(context, panelX.roundToInt(), panelY.roundToInt(), panelW, panelH, alpha)
		drawHeader(context, panelX.roundToInt(), panelY.roundToInt(), panelW, alpha)
		drawOptions(context, panelX.roundToInt(), panelY.roundToInt() + headerHeight(), panelW, alpha)
		drawFooter(context, panelX.roundToInt(), panelY.roundToInt() + panelH - FOOTER_HEIGHT, panelW, alpha)

		matrices.popMatrix()
	}

	override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
		if (closing || animationProgress() < 0.85f) return true

		val progress = animationProgress()
		val bounce = Easing.easeOutBack(progress).coerceIn(0f, 1f)
		val scale = Easing.lerp(0.88f, 1f, bounce)
		val slide = Easing.lerp(16f, 0f, Easing.easeOutCubic(progress))
		val panelW = PANEL_WIDTH
		val panelH = headerHeight() + options.size * ROW_HEIGHT + FOOTER_HEIGHT + 24
		val panelX = (width - panelW) / 2f
		val panelY = (height - panelH) / 2f + slide
		val cx = panelX + panelW / 2f
		val cy = panelY + panelH / 2f

		val (mx, my) = transformMouse(click.x().toInt(), click.y().toInt(), cx, cy, scale)
		val index = hitOptionIndex(mx, my, panelX.roundToInt(), panelY.roundToInt(), panelW, panelH)
		if (index >= 0) {
			val row = options[index]
			row.setter(!row.getter())
			return true
		}
		return true
	}

	override fun keyPressed(input: KeyInput): Boolean {
		if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
			requestClose()
			return true
		}
		return super.keyPressed(input)
	}

	private fun animationProgress(): Float {
		val elapsed = (Util.getMeasuringTimeMs() - animStartMs).toFloat()
		val duration = if (closing) CLOSE_MS else OPEN_MS
		return if (closing) {
			(1f - elapsed / duration).coerceIn(0f, 1f)
		} else {
			(elapsed / duration).coerceIn(0f, 1f)
		}
	}

	private fun headerHeight(): Int = 52

	private fun drawPanel(context: DrawContext, x: Int, y: Int, w: Int, h: Int, alpha: Float) {
		// Soft shadow layers
		context.fill(x + 4, y + 6, x + w + 4, y + h + 6, Colors.withAlpha(0xFF000000.toInt(), alpha * 0.35f))
		context.fill(x + 2, y + 3, x + w + 2, y + h + 3, Colors.withAlpha(0xFF000000.toInt(), alpha * 0.2f))

		context.fill(x, y, x + w, y + h, Colors.withAlpha(Colors.PANEL, alpha))
		context.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.withAlpha(Colors.PANEL_INNER, alpha * 0.55f))

		// Border
		drawBorder(context, x, y, w, h, Colors.withAlpha(Colors.BORDER, alpha))

		// Accent rail under header
		context.fill(x + 1, y + headerHeight() - 1, x + w - 1, y + headerHeight(), Colors.withAlpha(Colors.ACCENT, alpha * 0.85f))
		context.fill(x, y, x + 3, y + h, Colors.withAlpha(Colors.ACCENT, alpha))
	}

	private fun drawHeader(context: DrawContext, x: Int, y: Int, w: Int, alpha: Float) {
		val title = "Foraging Helper"
		val subtitle = "Options"
		val tr = textRenderer
		context.drawText(tr, title, x + 18, y + 14, Colors.withAlpha(Colors.TEXT, alpha), false)
		context.drawText(tr, subtitle, x + 18, y + 28, Colors.withAlpha(Colors.TEXT_MUTED, alpha), false)

		val status = if (HelperConfig.enabled) "ACTIVE" else "IDLE"
		val statusColor = if (HelperConfig.enabled) Colors.ACCENT else Colors.DANGER
		val statusW = tr.getWidth(status)
		context.drawText(tr, status, x + w - 18 - statusW, y + 20, Colors.withAlpha(statusColor, alpha), false)
	}

	private fun drawOptions(context: DrawContext, x: Int, y: Int, w: Int, alpha: Float) {
		options.forEachIndexed { index, row ->
			val rowY = y + 8 + index * ROW_HEIGHT
			val hovered = index == hoverIndex
			if (hovered) {
				context.fill(x + 8, rowY, x + w - 8, rowY + ROW_HEIGHT - 4, Colors.withAlpha(Colors.ROW_HOVER, alpha))
			}

			context.drawText(
				textRenderer,
				row.label,
				x + 18,
				rowY + 4,
				Colors.withAlpha(Colors.TEXT, alpha),
				false
			)
			context.drawText(
				textRenderer,
				row.description,
				x + 18,
				rowY + 15,
				Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.95f),
				false
			)

			drawCheckbox(context, x + w - 18 - CHECK_SIZE, rowY + (ROW_HEIGHT - 4 - CHECK_SIZE) / 2, row.getter(), alpha, hovered)
		}
	}

	private fun drawCheckbox(context: DrawContext, x: Int, y: Int, checked: Boolean, alpha: Float, hovered: Boolean) {
		val border = if (hovered || checked) Colors.ACCENT else Colors.CHECK_BORDER
		context.fill(x, y, x + CHECK_SIZE, y + CHECK_SIZE, Colors.withAlpha(Colors.CHECK_BG, alpha))
		drawBorder(context, x, y, CHECK_SIZE, CHECK_SIZE, Colors.withAlpha(border, alpha))

		if (checked) {
			context.fill(x + 3, y + 3, x + CHECK_SIZE - 3, y + CHECK_SIZE - 3, Colors.withAlpha(Colors.CHECK_ON, alpha))
		}
	}

	private fun drawFooter(context: DrawContext, x: Int, y: Int, w: Int, alpha: Float) {
		val hint = "H / Esc to close"
		val tw = textRenderer.getWidth(hint)
		context.drawText(textRenderer, hint, x + (w - tw) / 2, y + 10, Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.9f), false)
	}

	private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
		context.fill(x, y, x + w, y + 1, color)
		context.fill(x, y + h - 1, x + w, y + h, color)
		context.fill(x, y, x + 1, y + h, color)
		context.fill(x + w - 1, y, x + w, y + h, color)
	}

	private fun hitOptionIndex(mx: Int, my: Int, panelX: Int, panelY: Int, panelW: Int, panelH: Int): Int {
		val contentTop = panelY + headerHeight() + 8
		val contentLeft = panelX + 8
		val contentRight = panelX + panelW - 8
		if (mx !in contentLeft until contentRight) return -1
		if (my < contentTop || my > panelY + panelH - FOOTER_HEIGHT) return -1

		val index = (my - contentTop) / ROW_HEIGHT
		return if (index in options.indices) index else -1
	}

	private fun transformMouse(mouseX: Int, mouseY: Int, cx: Float, cy: Float, scale: Float): Pair<Int, Int> {
		val lx = ((mouseX - cx) / scale + cx).roundToInt()
		val ly = ((mouseY - cy) / scale + cy).roundToInt()
		return lx to ly
	}

	private data class OptionRow(
		val label: String,
		val description: String,
		val getter: () -> Boolean,
		val setter: (Boolean) -> Unit,
	)

	companion object {
		private const val PANEL_WIDTH = 280
		private const val ROW_HEIGHT = 28
		private const val FOOTER_HEIGHT = 28
		private const val CHECK_SIZE = 14
		private const val OPEN_MS = 260f
		private const val CLOSE_MS = 180f
	}
}
