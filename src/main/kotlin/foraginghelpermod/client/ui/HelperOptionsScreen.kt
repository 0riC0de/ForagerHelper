package foraginghelpermod.client.ui

import com.github.foragerhelper.movement.MovementController
import com.github.foragerhelper.target.PositionTarget
import com.github.foragerhelper.waypoint.Waypoint
import com.github.foragerhelper.waypoint.WaypointManager
import foraginghelpermod.client.HelperConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.BlockPos
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.input.KeyInput
import net.minecraft.text.Text
import net.minecraft.util.Util
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Modern reddish-grey / charcoal slate options screen inspired by the Taunahi GUI.
 * Features smooth open/close scaling, multi-page horizontal sliding animations,
 * interactive waypoint management, and direct travel execution.
 */
class HelperOptionsScreen : Screen(Text.translatable("screen.oriforaginhelpermod.options")) {

	private val options = listOf(
		OptionRow("Enable Helper", "Run foraging automation", { HelperConfig.enabled }, { HelperConfig.enabled = it }),
		OptionRow("Prefer Small Trees", "Favor small trees for more whole-tree bonuses", { HelperConfig.preferSmallTrees }, { HelperConfig.preferSmallTrees = it }),
		OptionRow("Auto Walk", "A* path to the selected tree", { HelperConfig.autoWalk }, { HelperConfig.autoWalk = it }),
		OptionRow("Void Recovery", "Use held Aspect of the Void when stuck or falling", { HelperConfig.useAspectOfVoid }, { HelperConfig.useAspectOfVoid = it }),
		OptionRow("Path Overlay", "Show pathfinder line and decisions in world", { HelperConfig.showPathOverlay }, { HelperConfig.showPathOverlay = it }),
		OptionRow("Auto Break", "Break foraging targets automatically", { HelperConfig.autoBreak }, { HelperConfig.autoBreak = it }),
		OptionRow("Look at Target", "Face the current foraging target", { HelperConfig.lookAtTarget }, { HelperConfig.lookAtTarget = it }),
		OptionRow("Show Status HUD", "Show ON/OFF status in the corner", { HelperConfig.showStatusHud }, { HelperConfig.showStatusHud = it }),
		OptionRow("Sneak While Active", "Hold sneak while the helper runs", { HelperConfig.sneakWhileActive }, { HelperConfig.sneakWhileActive = it }),
	)

	private var animStartMs = 0L
	private var closing = false
	private var hoverIndex = -1

	// Tab state & sliding animation
	private var selectedTab = 0 // 0 = Options, 1 = Waypoints
	private var prevTab = 0
	private var tabSwitchStartMs = 0L
	private var waypointScrollOffset = 0

	override fun init() {
		animStartMs = Util.getMeasuringTimeMs()
		closing = false
		tabSwitchStartMs = 0L
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
		// Custom backdrop rendered in render()
	}

	override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
		val progress = animationProgress()
		if (closing && progress <= 0.001f) {
			client?.setScreen(null)
			return
		}

		val motion = if (closing) Easing.easeInCubic(1f - progress) else Easing.easeOutCubic(progress)
		val alpha = if (closing) progress else Easing.easeOutCubic(progress)
		val bounce = if (closing) 1f - motion else Easing.easeOutBack(progress).coerceIn(0f, 1.06f)
		val scale = Easing.lerp(0.88f, 1f, bounce.coerceAtMost(1f))
		val slide = Easing.lerp(16f, 0f, if (closing) progress else Easing.easeOutCubic(progress))

		// Backdrop overlay
		context.fill(0, 0, width, height, Colors.withAlpha(Colors.BACKDROP, alpha * 0.95f))

		val panelW = PANEL_WIDTH
		val panelH = PANEL_HEIGHT
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
		val (mx, my) = localMouse

		drawPanelFrame(context, panelX.roundToInt(), panelY.roundToInt(), panelW, panelH, alpha)
		drawHeader(context, panelX.roundToInt(), panelY.roundToInt(), panelW, alpha)
		drawTabBar(context, panelX.roundToInt(), panelY.roundToInt() + HEADER_HEIGHT, panelW, alpha, mx, my)

		// Content area with horizontal sliding animation
		val contentTop = panelY.roundToInt() + HEADER_HEIGHT + TAB_BAR_HEIGHT
		val contentH = panelH - HEADER_HEIGHT - TAB_BAR_HEIGHT - FOOTER_HEIGHT
		val contentLeft = panelX.roundToInt() + 6
		val contentRight = panelX.roundToInt() + panelW - 6
		val contentBottom = contentTop + contentH

		val now = Util.getMeasuringTimeMs()
		val tabElapsed = (now - tabSwitchStartMs).toFloat()
		val isTabAnimating = tabSwitchStartMs > 0L && tabElapsed < TAB_ANIM_MS
		val tabProgress = if (isTabAnimating) Easing.easeOutCubic((tabElapsed / TAB_ANIM_MS).coerceIn(0f, 1f)) else 1f

		try {
			context.enableScissor(contentLeft, contentTop, contentRight, contentBottom)

			if (isTabAnimating) {
				val slideDist = panelW.toFloat()
				val outOffset = if (selectedTab > prevTab) -tabProgress * slideDist else tabProgress * slideDist
				val inOffset = if (selectedTab > prevTab) (1f - tabProgress) * slideDist else -(1f - tabProgress) * slideDist

				// Draw outgoing tab
				drawPage(context, prevTab, panelX.roundToInt() + outOffset.roundToInt(), contentTop, panelW, contentH, alpha * (1f - tabProgress * 0.5f), mx, my)
				// Draw incoming tab
				drawPage(context, selectedTab, panelX.roundToInt() + inOffset.roundToInt(), contentTop, panelW, contentH, alpha * (0.5f + tabProgress * 0.5f), mx, my)
			} else {
				drawPage(context, selectedTab, panelX.roundToInt(), contentTop, panelW, contentH, alpha, mx, my)
			}
		} finally {
			context.disableScissor()
		}

		drawFooter(context, panelX.roundToInt(), panelY.roundToInt() + panelH - FOOTER_HEIGHT, panelW, alpha)

		matrices.popMatrix()
	}

	private fun drawPage(
		context: DrawContext,
		tab: Int,
		x: Int,
		top: Int,
		w: Int,
		h: Int,
		alpha: Float,
		mx: Int,
		my: Int
	) {
		if (tab == 0) {
			drawOptionsList(context, x, top, w, alpha, mx, my)
		} else {
			drawWaypointsList(context, x, top, w, h, alpha, mx, my)
		}
	}

	private fun drawPanelFrame(context: DrawContext, x: Int, y: Int, w: Int, h: Int, alpha: Float) {
		// Drop shadow
		context.fill(x + 4, y + 6, x + w + 4, y + h + 6, Colors.withAlpha(0xFF000000.toInt(), alpha * 0.45f))
		context.fill(x + 2, y + 3, x + w + 2, y + h + 3, Colors.withAlpha(0xFF000000.toInt(), alpha * 0.25f))

		// Panel base
		context.fill(x, y, x + w, y + h, Colors.withAlpha(Colors.PANEL, alpha))
		context.fill(x + 1, y + 1, x + w - 1, y + h - 1, Colors.withAlpha(Colors.PANEL_INNER, alpha * 0.6f))

		// Outer border
		drawBorder(context, x, y, w, h, Colors.withAlpha(Colors.BORDER, alpha))

		// Glowing crimson accent top bar
		context.fill(x + 1, y + 1, x + w - 1, y + 3, Colors.withAlpha(Colors.ACCENT, alpha))
	}

	private fun drawHeader(context: DrawContext, x: Int, y: Int, w: Int, alpha: Float) {
		val title = "Foraging Helper"
		val subtitle = "Taunahi Aesthetic Engine"
		val tr = textRenderer
		context.drawText(tr, title, x + 16, y + 10, Colors.withAlpha(Colors.TEXT, alpha), false)
		context.drawText(tr, subtitle, x + 16, y + 22, Colors.withAlpha(Colors.TEXT_MUTED, alpha), false)

		val status = if (HelperConfig.enabled) "ACTIVE" else "IDLE"
		val statusColor = if (HelperConfig.enabled) Colors.ACCENT else Colors.TEXT_MUTED
		val statusW = tr.getWidth(status)
		val statusX = x + w - 16 - statusW - 8
		val statusY = y + 12

		// Status pill badge
		context.fill(statusX - 4, statusY - 2, statusX + statusW + 4, statusY + 11, Colors.withAlpha(Colors.PANEL_INNER, alpha))
		drawBorder(context, statusX - 4, statusY - 2, statusW + 8, 13, Colors.withAlpha(statusColor, alpha * 0.8f))
		context.drawText(tr, status, statusX, statusY, Colors.withAlpha(statusColor, alpha), false)
	}

	private fun drawTabBar(context: DrawContext, x: Int, y: Int, w: Int, alpha: Float, mx: Int, my: Int) {
		val tabW = (w - 32) / 2
		val tabH = 22

		// Tab 0: Options
		val tab0X = x + 14
		val tab0Hover = mx in tab0X until (tab0X + tabW) && my in y until (y + tabH)
		val tab0Active = selectedTab == 0
		val tab0Bg = when {
			tab0Active -> Colors.withAlpha(Colors.ACCENT_BG, alpha)
			tab0Hover -> Colors.withAlpha(Colors.BUTTON_HOVER, alpha)
			else -> Colors.withAlpha(Colors.BUTTON_BG, alpha * 0.7f)
		}
		context.fill(tab0X, y, tab0X + tabW, y + tabH, tab0Bg)
		val tab0Border = if (tab0Active) Colors.ACCENT else Colors.BORDER
		drawBorder(context, tab0X, y, tabW, tabH, Colors.withAlpha(tab0Border, alpha))
		val tab0TextColor = if (tab0Active) Colors.TEXT else Colors.TEXT_MUTED
		val tab0Title = "Settings"
		val t0w = textRenderer.getWidth(tab0Title)
		context.drawText(textRenderer, tab0Title, tab0X + (tabW - t0w) / 2, y + 6, Colors.withAlpha(tab0TextColor, alpha), false)
		if (tab0Active) {
			context.fill(tab0X + 4, y + tabH - 2, tab0X + tabW - 4, y + tabH, Colors.withAlpha(Colors.ACCENT, alpha))
		}

		// Tab 1: Waypoints
		val tab1X = tab0X + tabW + 4
		val tab1Hover = mx in tab1X until (tab1X + tabW) && my in y until (y + tabH)
		val tab1Active = selectedTab == 1
		val tab1Bg = when {
			tab1Active -> Colors.withAlpha(Colors.ACCENT_BG, alpha)
			tab1Hover -> Colors.withAlpha(Colors.BUTTON_HOVER, alpha)
			else -> Colors.withAlpha(Colors.BUTTON_BG, alpha * 0.7f)
		}
		context.fill(tab1X, y, tab1X + tabW, y + tabH, tab1Bg)
		val tab1Border = if (tab1Active) Colors.ACCENT else Colors.BORDER
		drawBorder(context, tab1X, y, tabW, tabH, Colors.withAlpha(tab1Border, alpha))
		val tab1TextColor = if (tab1Active) Colors.TEXT else Colors.TEXT_MUTED
		val wpCount = WaypointManager.waypoints.size
		val tab1Title = if (wpCount > 0) "Waypoints ($wpCount)" else "Waypoints"
		val t1w = textRenderer.getWidth(tab1Title)
		context.drawText(textRenderer, tab1Title, tab1X + (tabW - t1w) / 2, y + 6, Colors.withAlpha(tab1TextColor, alpha), false)
		if (tab1Active) {
			context.fill(tab1X + 4, y + tabH - 2, tab1X + tabW - 4, y + tabH, Colors.withAlpha(Colors.ACCENT, alpha))
		}
	}

	private fun drawOptionsList(context: DrawContext, x: Int, top: Int, w: Int, alpha: Float, mx: Int, my: Int) {
		options.forEachIndexed { index, row ->
			val rowY = top + 4 + index * ROW_HEIGHT
			val hovered = mx in (x + 8) until (x + w - 8) && my in rowY until (rowY + ROW_HEIGHT - 3)
			if (hovered) {
				context.fill(x + 8, rowY, x + w - 8, rowY + ROW_HEIGHT - 3, Colors.withAlpha(Colors.ROW_HOVER, alpha))
			}

			context.drawText(textRenderer, row.label, x + 16, rowY + 3, Colors.withAlpha(Colors.TEXT, alpha), false)
			context.drawText(textRenderer, row.description, x + 16, rowY + 14, Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.95f), false)

			drawCheckbox(context, x + w - 16 - CHECK_SIZE, rowY + (ROW_HEIGHT - 3 - CHECK_SIZE) / 2, row.getter(), alpha, hovered)
		}
	}

	private fun drawWaypointsList(context: DrawContext, x: Int, top: Int, w: Int, h: Int, alpha: Float, mx: Int, my: Int) {
		val tr = textRenderer
		val playerPos = client?.player?.let { net.minecraft.util.math.Vec3d(it.x, it.y, it.z) }

		// Top Action: "+ Add Current Pos"
		val addBtnX = x + 12
		val addBtnY = top + 4
		val addBtnW = w - 24
		val addBtnH = 22
		val addHover = mx in addBtnX until (addBtnX + addBtnW) && my in addBtnY until (addBtnY + addBtnH)
		val addBg = if (addHover) Colors.withAlpha(Colors.ACCENT, alpha * 0.85f) else Colors.withAlpha(Colors.BUTTON_BG, alpha)
		context.fill(addBtnX, addBtnY, addBtnX + addBtnW, addBtnY + addBtnH, addBg)
		drawBorder(context, addBtnX, addBtnY, addBtnW, addBtnH, Colors.withAlpha(if (addHover) Colors.ACCENT_BRIGHT else Colors.BORDER, alpha))
		val addText = "+ Add Current Location"
		val atw = tr.getWidth(addText)
		context.drawText(tr, addText, addBtnX + (addBtnW - atw) / 2, addBtnY + 6, Colors.withAlpha(Colors.TEXT, alpha), false)

		val listTop = addBtnY + addBtnH + 6
		val waypoints = WaypointManager.waypoints

		if (waypoints.isEmpty()) {
			val emptyMsg = "No waypoints saved"
			val hintMsg = "Click '+ Add Current Location' to save where you are"
			val emw = tr.getWidth(emptyMsg)
			val hmw = tr.getWidth(hintMsg)
			context.drawText(tr, emptyMsg, x + (w - emw) / 2, listTop + 40, Colors.withAlpha(Colors.TEXT_MUTED, alpha), false)
			context.drawText(tr, hintMsg, x + (w - hmw) / 2, listTop + 54, Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.7f), false)
			return
		}

		val visibleCount = 6
		val rowH = 34
		for (i in 0 until minOf(visibleCount, waypoints.size)) {
			val wp = waypoints[i]
			val rowY = listTop + i * (rowH + 3)

			// Waypoint Card Background
			context.fill(x + 12, rowY, x + w - 12, rowY + rowH, Colors.withAlpha(Colors.PANEL_INNER, alpha * 0.75f))
			drawBorder(context, x + 12, rowY, w - 24, rowH, Colors.withAlpha(Colors.BORDER, alpha * 0.6f))

			// Waypoint Name
			context.drawText(tr, wp.name, x + 18, rowY + 5, Colors.withAlpha(Colors.TEXT, alpha), false)

			// Coordinates & Distance
			val distStr = if (playerPos != null) {
				val dist = sqrt(playerPos.squaredDistanceTo(wp.posVec)).roundToInt()
				"${dist}m away"
			} else ""
			val coordStr = "(${wp.x.toInt()}, ${wp.y.toInt()}, ${wp.z.toInt()})  $distStr"
			context.drawText(tr, coordStr, x + 18, rowY + 18, Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.85f), false)

			// [ Travel ] Button
			val travelW = 46
			val travelH = 20
			val travelX = x + w - 16 - 24 - travelW
			val travelY = rowY + 7
			val travelHover = mx in travelX until (travelX + travelW) && my in travelY until (travelY + travelH)
			val travelBg = if (travelHover) Colors.withAlpha(Colors.ACCENT, alpha) else Colors.withAlpha(Colors.BUTTON_BG, alpha)
			context.fill(travelX, travelY, travelX + travelW, travelY + travelH, travelBg)
			drawBorder(context, travelX, travelY, travelW, travelH, Colors.withAlpha(if (travelHover) Colors.ACCENT_BRIGHT else Colors.BORDER, alpha))
			val trText = "Travel"
			val trw = tr.getWidth(trText)
			context.drawText(tr, trText, travelX + (travelW - trw) / 2, travelY + 5, Colors.withAlpha(Colors.TEXT, alpha), false)

			// [ X ] Delete Button
			val delW = 18
			val delH = 20
			val delX = x + w - 16 - delW
			val delY = rowY + 7
			val delHover = mx in delX until (delX + delW) && my in delY until (delY + delH)
			val delBg = if (delHover) Colors.withAlpha(Colors.DANGER, alpha) else Colors.withAlpha(Colors.BUTTON_BG, alpha)
			context.fill(delX, delY, delX + delW, delY + delH, delBg)
			drawBorder(context, delX, delY, delW, delH, Colors.withAlpha(if (delHover) Colors.DANGER else Colors.BORDER, alpha))
			val delText = "x"
			val dw = tr.getWidth(delText)
			context.drawText(tr, delText, delX + (delW - dw) / 2, delY + 5, Colors.withAlpha(if (delHover) Colors.TEXT else Colors.TEXT_MUTED, alpha), false)
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
		context.drawText(textRenderer, hint, x + (w - tw) / 2, y + 8, Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.85f), false)
	}

	private fun drawBorder(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
		context.fill(x, y, x + w, y + 1, color)
		context.fill(x, y + h - 1, x + w, y + h, color)
		context.fill(x, y + 1, x + 1, y + h - 1, color)
		context.fill(x + w - 1, y + 1, x + w, y + h - 1, color)
	}

	override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
		if (closing || animationProgress() < 0.85f) return true

		val progress = animationProgress()
		val bounce = Easing.easeOutBack(progress).coerceIn(0f, 1f)
		val scale = Easing.lerp(0.88f, 1f, bounce)
		val slide = Easing.lerp(16f, 0f, Easing.easeOutCubic(progress))
		val panelW = PANEL_WIDTH
		val panelH = PANEL_HEIGHT
		val panelX = (width - panelW) / 2f
		val panelY = (height - panelH) / 2f + slide
		val cx = panelX + panelW / 2f
		val cy = panelY + panelH / 2f

		val (mx, my) = transformMouse(click.x().toInt(), click.y().toInt(), cx, cy, scale)

		// 1. Tab Bar clicks
		val tabY = panelY.roundToInt() + HEADER_HEIGHT
		val tabW = (panelW - 32) / 2
		val tabH = 22
		val tab0X = panelX.roundToInt() + 14
		val tab1X = tab0X + tabW + 4

		if (my in tabY until (tabY + tabH)) {
			if (mx in tab0X until (tab0X + tabW) && selectedTab != 0) {
				prevTab = selectedTab
				selectedTab = 0
				tabSwitchStartMs = Util.getMeasuringTimeMs()
				return true
			}
			if (mx in tab1X until (tab1X + tabW) && selectedTab != 1) {
				prevTab = selectedTab
				selectedTab = 1
				tabSwitchStartMs = Util.getMeasuringTimeMs()
				return true
			}
		}

		// Prevent content misclicks during active tab sliding transition
		val now = Util.getMeasuringTimeMs()
		val tabElapsed = (now - tabSwitchStartMs).toFloat()
		if (tabSwitchStartMs > 0L && tabElapsed < TAB_ANIM_MS) {
			return true
		}

		val contentTop = panelY.roundToInt() + HEADER_HEIGHT + TAB_BAR_HEIGHT

		// 2. Options Tab clicks
		if (selectedTab == 0) {
			val optIndex = hitOptionIndex(mx, my, panelX.roundToInt(), contentTop, panelW)
			if (optIndex in options.indices) {
				val row = options[optIndex]
				row.setter(!row.getter())
				return true
			}
		} else {
			// 3. Waypoints Tab clicks
			val addBtnX = panelX.roundToInt() + 12
			val addBtnY = contentTop + 4
			val addBtnW = panelW - 24
			val addBtnH = 22
			if (mx in addBtnX until (addBtnX + addBtnW) && my in addBtnY until (addBtnY + addBtnH)) {
				val player = client?.player
				if (player != null) {
					WaypointManager.addWaypoint("", net.minecraft.util.math.Vec3d(player.x, player.y, player.z))
				}
				return true
			}

			val listTop = addBtnY + addBtnH + 6
			val waypoints = WaypointManager.waypoints
			val rowH = 34
			for (i in 0 until minOf(6, waypoints.size)) {
				val wp = waypoints[i]
				val rowY = listTop + i * (rowH + 3)

				// Travel Button
				val travelW = 46
				val travelH = 20
				val travelX = panelX.roundToInt() + panelW - 16 - 24 - travelW
				val travelY = rowY + 7
				if (mx in travelX until (travelX + travelW) && my in travelY until (travelY + travelH)) {
					val targetPos = BlockPos.ofFloored(wp.x, wp.y, wp.z)
					HelperConfig.manualRouteGoal = targetPos
					MovementController.setDestination(PositionTarget(wp.posVec, arrivalRadius = 1.0))
					HelperConfig.autoWalk = true
					requestClose()
					return true
				}

				// Delete Button
				val delW = 18
				val delH = 20
				val delX = panelX.roundToInt() + panelW - 16 - delW
				val delY = rowY + 7
				if (mx in delX until (delX + delW) && my in delY until (delY + delH)) {
					WaypointManager.removeWaypoint(wp.id)
					return true
				}
			}
		}

		return true
	}

	override fun keyPressed(input: KeyInput): Boolean {
		if (input.key() == GLFW.GLFW_KEY_ESCAPE || input.key() == GLFW.GLFW_KEY_H) {
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

	private fun hitOptionIndex(mx: Int, my: Int, panelX: Int, contentTop: Int, panelW: Int): Int {
		val contentLeft = panelX + 8
		val contentRight = panelX + panelW - 8
		if (mx !in contentLeft until contentRight) return -1
		if (my < contentTop) return -1
		val index = (my - contentTop - 4) / ROW_HEIGHT
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
		private const val PANEL_WIDTH = 310
		private const val PANEL_HEIGHT = 350
		private const val HEADER_HEIGHT = 36
		private const val TAB_BAR_HEIGHT = 28
		private const val FOOTER_HEIGHT = 24
		private const val ROW_HEIGHT = 28
		private const val CHECK_SIZE = 14
		private const val OPEN_MS = 240f
		private const val CLOSE_MS = 160f
		private const val TAB_ANIM_MS = 220f
	}
}
