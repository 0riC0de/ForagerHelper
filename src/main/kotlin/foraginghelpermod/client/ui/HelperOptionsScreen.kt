package foraginghelpermod.client.ui

import com.github.foragerhelper.movement.MovementController
import com.github.foragerhelper.target.PositionTarget
import com.github.foragerhelper.waypoint.Waypoint
import com.github.foragerhelper.waypoint.WaypointManager
import foraginghelpermod.client.HelperConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.input.KeyInput
import net.minecraft.text.Text
import net.minecraft.util.Util
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modern orange and charcoal grey options screen overhaul inspired by the Taunahi+ GUI.
 * Features:
 * - Left sidebar category navigation (General, Foraging, Movement, Waypoints).
 * - Dark charcoal cards/panels with clean borders and modern pill toggle switches.
 * - Orange & grey color theme (#FF6B00 / #FF8800 accent, charcoal cards #1E1E23).
 * - Smooth down-to-up opening animation and down-sliding close.
 * - Slower, silky smooth category sliding transition with subtle motion blur trailing effect.
 * - Interactive waypoint management with custom naming input field, travel, and delete.
 */
class HelperOptionsScreen : Screen(Text.translatable("screen.oriforaginhelpermod.options")) {

	private val generalOptions = listOf(
		OptionRow("Enable Helper", "Master toggle to run foraging automation", { HelperConfig.enabled }, { HelperConfig.enabled = it }),
		OptionRow("Show Status HUD", "Display active status overlay in the corner", { HelperConfig.showStatusHud }, { HelperConfig.showStatusHud = it }),
		OptionRow("Void Recovery", "Use Aspect of the Void when stuck or falling", { HelperConfig.useAspectOfVoid }, { HelperConfig.useAspectOfVoid = it }),
		OptionRow("Sneak While Active", "Hold sneak key while automation is running", { HelperConfig.sneakWhileActive }, { HelperConfig.sneakWhileActive = it })
	)

	private val foragingOptions = listOf(
		OptionRow("Prefer Small Trees", "Favor smaller trees for whole-tree chop bonuses", { HelperConfig.preferSmallTrees }, { HelperConfig.preferSmallTrees = it }),
		OptionRow("Auto Break", "Break foraging log targets automatically", { HelperConfig.autoBreak }, { HelperConfig.autoBreak = it }),
		OptionRow("Look at Target", "Smoothly face the current foraging log target", { HelperConfig.lookAtTarget }, { HelperConfig.lookAtTarget = it })
	)

	private val movementOptions = listOf(
		OptionRow("Auto Walk", "Execute A* 3D pathfinding to selected targets", { HelperConfig.autoWalk }, { HelperConfig.autoWalk = it }),
		OptionRow("Path Overlay", "Render 3D pathfinder trajectory line in world", { HelperConfig.showPathOverlay }, { HelperConfig.showPathOverlay = it })
	)

	private val categories = listOf(
		CategoryTab("General", "Automation settings"),
		CategoryTab("Foraging", "Target & break rules"),
		CategoryTab("Movement", "Navigation & overlay"),
		CategoryTab("Waypoints", "Saved destinations")
	)

	private var animStartMs = 0L
	private var closing = false

	// Category tabs & sliding transition
	private var selectedTab = 0
	private var prevTab = 0
	private var tabSwitchStartMs = 0L

	// Waypoint naming & scroll
	private var nameInputText = ""
	private var isNameInputFocused = false
	private var waypointScrollOffset = 0

	override fun init() {
		animStartMs = Util.getMeasuringTimeMs()
		closing = false
		tabSwitchStartMs = 0L
		isNameInputFocused = false
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

		// Down-to-up opening animation: slides upward into center with smooth easeOutCubic
		val alpha = if (closing) progress else Easing.easeOutCubic(progress)
		val scale = if (closing) Easing.lerp(0.94f, 1f, progress) else Easing.lerp(0.92f, 1f, Easing.easeOutCubic(progress))
		val openSlide = if (closing) {
			Easing.lerp(0f, height * 0.70f, Easing.easeInCubic(1f - progress))
		} else {
			Easing.lerp(height * 0.65f, 0f, Easing.easeOutCubic(progress))
		}

		// Fullscreen dark backdrop
		context.fill(0, 0, width, height, Colors.withAlpha(Colors.BACKDROP, alpha * 0.95f))

		val panelW = PANEL_WIDTH
		val panelH = PANEL_HEIGHT
		val panelX = (width - panelW) / 2f
		val panelY = (height - panelH) / 2f + openSlide
		val cx = panelX + panelW / 2f
		val cy = panelY + panelH / 2f

		val matrices = context.matrices
		matrices.pushMatrix()
		matrices.translate(cx, cy)
		matrices.scale(scale, scale)
		matrices.translate(-cx, -cy)

		val (mx, my) = transformMouse(mouseX, mouseY, cx, cy, scale)

		// 1. Draw Outer Window & Borders
		drawPanelFrame(context, panelX.roundToInt(), panelY.roundToInt(), panelW, panelH, alpha)

		// 2. Draw Left Sidebar (Categories)
		drawSidebar(context, panelX.roundToInt(), panelY.roundToInt(), SIDEBAR_WIDTH, panelH, alpha, mx, my)

		// 3. Draw Right Content Area (With Slower Sliding & Motion Blur)
		val contentLeft = panelX.roundToInt() + SIDEBAR_WIDTH + 8
		val contentTop = panelY.roundToInt() + 8
		val contentW = panelW - SIDEBAR_WIDTH - 16
		val contentH = panelH - 16

		val now = Util.getMeasuringTimeMs()
		val tabElapsed = (now - tabSwitchStartMs).toFloat()
		val isTabAnimating = tabSwitchStartMs > 0L && tabElapsed < TAB_ANIM_MS
		val tabProgress = if (isTabAnimating) Easing.easeInOutCubic((tabElapsed / TAB_ANIM_MS).coerceIn(0f, 1f)) else 1f

		try {
			context.enableScissor(contentLeft - 2, contentTop - 2, contentLeft + contentW + 2, contentTop + contentH + 2)

			if (isTabAnimating) {
				val direction = if (selectedTab > prevTab) 1 else -1
				val slideDist = contentW.toFloat() + 20f
				val outOffset = -direction * tabProgress * slideDist
				val inOffset = direction * (1f - tabProgress) * slideDist

				// Subtle motion blur trailing passes (rendered during peak sliding velocity)
				val velocityFactor = sin(tabProgress * Math.PI.toFloat())
				if (velocityFactor > 0.15f) {
					val blur1Offset = -direction * 8f * velocityFactor
					val blur2Offset = -direction * 4f * velocityFactor
					val blurAlpha1 = 0.08f * alpha * velocityFactor
					val blurAlpha2 = 0.15f * alpha * velocityFactor

					drawContentPage(context, selectedTab, contentLeft + inOffset.roundToInt() + blur1Offset.roundToInt(), contentTop, contentW, contentH, blurAlpha1, mx, my)
					drawContentPage(context, selectedTab, contentLeft + inOffset.roundToInt() + blur2Offset.roundToInt(), contentTop, contentW, contentH, blurAlpha2, mx, my)
				}

				// Draw outgoing page
				drawContentPage(context, prevTab, contentLeft + outOffset.roundToInt(), contentTop, contentW, contentH, alpha * (1f - tabProgress * 0.7f), mx, my)
				// Draw incoming page
				drawContentPage(context, selectedTab, contentLeft + inOffset.roundToInt(), contentTop, contentW, contentH, alpha * (0.3f + tabProgress * 0.7f), mx, my)
			} else {
				drawContentPage(context, selectedTab, contentLeft, contentTop, contentW, contentH, alpha, mx, my)
			}
		} finally {
			context.disableScissor()
		}

		matrices.popMatrix()
	}

	private fun drawPanelFrame(context: DrawContext, x: Int, y: Int, w: Int, h: Int, alpha: Float) {
		// Drop shadow
		context.fill(x + 5, y + 8, x + w + 5, y + h + 8, Colors.withAlpha(0xFF000000.toInt(), alpha * 0.50f))
		context.fill(x + 2, y + 4, x + w + 2, y + h + 4, Colors.withAlpha(0xFF000000.toInt(), alpha * 0.30f))

		// Background dark charcoal panel
		context.fill(x, y, x + w, y + h, Colors.withAlpha(Colors.PANEL, alpha))
		// Outer border
		drawBorder(context, x, y, w, h, Colors.withAlpha(Colors.BORDER, alpha))

		// Orange accent top highlight line
		context.fill(x + 1, y + 1, x + w - 1, y + 3, Colors.withAlpha(Colors.ACCENT, alpha))
	}

	private fun drawSidebar(context: DrawContext, x: Int, y: Int, w: Int, h: Int, alpha: Float, mx: Int, my: Int) {
		// Sidebar background
		context.fill(x + 1, y + 3, x + w, y + h - 1, Colors.withAlpha(Colors.PANEL_SIDEBAR, alpha))
		// Vertical separator line
		context.fill(x + w, y + 1, x + w + 1, y + h - 1, Colors.withAlpha(Colors.BORDER, alpha))

		// Logo / Header
		val tr = textRenderer
		val logoX = x + 12
		val logoY = y + 14

		// Taunahi-style logo badge (small orange accent square with white dot)
		context.fill(logoX, logoY + 1, logoX + 10, logoY + 11, Colors.withAlpha(Colors.ACCENT, alpha))
		context.fill(logoX + 3, logoY + 4, logoX + 7, logoY + 8, Colors.withAlpha(0xFFFFFFFF.toInt(), alpha))

		context.drawText(tr, "Forager", logoX + 14, logoY, Colors.withAlpha(Colors.TEXT, alpha), false)
		context.drawText(tr, "Helper+", logoX + 14 + tr.getWidth("Forager "), logoY, Colors.withAlpha(Colors.ACCENT, alpha), false)

		// Divider below title
		context.fill(x + 10, logoY + 18, x + w - 10, logoY + 19, Colors.withAlpha(Colors.BORDER, alpha * 0.7f))

		// Category navigation items
		val tabStartY = logoY + 28
		val tabH = 30

		categories.forEachIndexed { index, cat ->
			val tabY = tabStartY + index * (tabH + 2)
			val isSelected = selectedTab == index
			val isHover = mx in (x + 6) until (x + w - 6) && my in tabY until (tabY + tabH)

			if (isSelected) {
				// Active category: subtle orange background & left indicator bar
				context.fill(x + 6, tabY, x + w - 6, tabY + tabH, Colors.withAlpha(Colors.ACCENT_BG, alpha))
				context.fill(x + 6, tabY + 4, x + 9, tabY + tabH - 4, Colors.withAlpha(Colors.ACCENT, alpha))
			} else if (isHover) {
				context.fill(x + 6, tabY, x + w - 6, tabY + tabH, Colors.withAlpha(Colors.ROW_HOVER, alpha))
			}

			val textColor = when {
				isSelected -> Colors.TEXT
				isHover -> Colors.TEXT
				else -> Colors.TEXT_MUTED
			}

			val textX = x + 16
			val textY = tabY + 6
			context.drawText(tr, cat.title, textX, textY, Colors.withAlpha(textColor, alpha), false)

			// Subtitle / category description in tiny muted font
			context.drawText(tr, cat.subtitle, textX, textY + 11, Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.70f), false)
		}

		// Footer hint
		val hint = "Esc to close"
		val hw = tr.getWidth(hint)
		context.drawText(tr, hint, x + (w - hw) / 2, y + h - 14, Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.60f), false)
	}

	private fun drawContentPage(
		context: DrawContext,
		tab: Int,
		left: Int,
		top: Int,
		w: Int,
		h: Int,
		alpha: Float,
		mx: Int,
		my: Int
	) {
		val tr = textRenderer
		val catTitle = categories.getOrNull(tab)?.title ?: "Settings"

		// Header row
		context.drawText(tr, catTitle, left + 4, top + 6, Colors.withAlpha(Colors.TEXT, alpha), false)
		context.fill(left + 4, top + 20, left + w - 4, top + 21, Colors.withAlpha(Colors.BORDER, alpha * 0.6f))

		val listTop = top + 28
		when (tab) {
			0 -> drawOptionCards(context, generalOptions, left, listTop, w, alpha, mx, my)
			1 -> drawOptionCards(context, foragingOptions, left, listTop, w, alpha, mx, my)
			2 -> drawOptionCards(context, movementOptions, left, listTop, w, alpha, mx, my)
			3 -> drawWaypointsPage(context, left, listTop, w, h - 32, alpha, mx, my)
		}
	}

	private fun drawOptionCards(
		context: DrawContext,
		optionList: List<OptionRow>,
		left: Int,
		top: Int,
		w: Int,
		alpha: Float,
		mx: Int,
		my: Int
	) {
		val tr = textRenderer
		val cardH = 46
		val cardW = w - 8

		optionList.forEachIndexed { index, opt ->
			val cardX = left + 4
			val cardY = top + index * (cardH + 6)
			val isHover = mx in cardX until (cardX + cardW) && my in cardY until (cardY + cardH)

			// Card background with subtle hover highlight
			val cardBg = if (isHover) Colors.CARD_HOVER else Colors.CARD
			context.fill(cardX, cardY, cardX + cardW, cardY + cardH, Colors.withAlpha(cardBg, alpha))
			val cardBorder = if (isHover) Colors.BORDER_LIGHT else Colors.CARD_BORDER
			drawBorder(context, cardX, cardY, cardW, cardH, Colors.withAlpha(cardBorder, alpha))

			// Option label & description
			context.drawText(tr, opt.label, cardX + 12, cardY + 8, Colors.withAlpha(Colors.TEXT, alpha), false)
			context.drawText(tr, opt.description, cardX + 12, cardY + 22, Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.90f), false)

			// Modern Pill Toggle Switch
			val toggleW = 28
			val toggleH = 14
			val toggleX = cardX + cardW - 14 - toggleW
			val toggleY = cardY + (cardH - toggleH) / 2
			drawPillToggle(context, toggleX, toggleY, toggleW, toggleH, opt.getter(), alpha, isHover)
		}
	}

	private fun drawPillToggle(
		context: DrawContext,
		x: Int,
		y: Int,
		w: Int,
		h: Int,
		checked: Boolean,
		alpha: Float,
		hovered: Boolean
	) {
		val trackBg = if (checked) Colors.ACCENT else Colors.TOGGLE_OFF
		val trackBorder = when {
			checked -> Colors.ACCENT_BRIGHT
			hovered -> Colors.BORDER_LIGHT
			else -> Colors.BORDER
		}

		// Pill track
		context.fill(x, y, x + w, y + h, Colors.withAlpha(trackBg, alpha))
		drawBorder(context, x, y, w, h, Colors.withAlpha(trackBorder, alpha))

		// Knob: smooth circle-like slider handle
		val knobSize = h - 4
		val knobX = if (checked) x + w - knobSize - 2 else x + 2
		val knobY = y + 2
		context.fill(knobX, knobY, knobX + knobSize, knobY + knobSize, Colors.withAlpha(Colors.TOGGLE_KNOB, alpha))
	}

	private fun drawWaypointsPage(
		context: DrawContext,
		left: Int,
		top: Int,
		w: Int,
		h: Int,
		alpha: Float,
		mx: Int,
		my: Int
	) {
		val tr = textRenderer
		val playerPos = client?.player?.let { Vec3d(it.x, it.y, it.z) }

		// Top Control Bar: [Text Input: Waypoint Name] + [+ Add Current Pos Button]
		val barY = top + 2
		val addBtnW = 100
		val inputW = w - 16 - addBtnW - 8
		val inputH = 22
		val inputX = left + 4

		// 1. Waypoint Name Text Input Field
		val isInputHover = mx in inputX until (inputX + inputW) && my in barY until (barY + inputH)
		val inputBg = Colors.CHECK_BG
		context.fill(inputX, barY, inputX + inputW, barY + inputH, Colors.withAlpha(inputBg, alpha))
		val inputBorder = when {
			isNameInputFocused -> Colors.ACCENT
			isInputHover -> Colors.BORDER_LIGHT
			else -> Colors.CARD_BORDER
		}
		drawBorder(context, inputX, barY, inputW, inputH, Colors.withAlpha(inputBorder, alpha))

		if (nameInputText.isEmpty() && !isNameInputFocused) {
			context.drawText(tr, "Waypoint name...", inputX + 8, barY + 7, Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.70f), false)
		} else {
			val cursor = if (isNameInputFocused && (Util.getMeasuringTimeMs() / 500L) % 2L == 0L) "_" else ""
			val displayStr = nameInputText + cursor
			context.drawText(tr, displayStr, inputX + 8, barY + 7, Colors.withAlpha(Colors.TEXT, alpha), false)
		}

		// 2. "+ Add Pos" Button
		val addBtnX = inputX + inputW + 6
		val isAddHover = mx in addBtnX until (addBtnX + addBtnW) && my in barY until (barY + inputH)
		val addBg = if (isAddHover) Colors.withAlpha(Colors.ACCENT_BRIGHT, alpha) else Colors.withAlpha(Colors.ACCENT, alpha)
		context.fill(addBtnX, barY, addBtnX + addBtnW, barY + inputH, addBg)
		drawBorder(context, addBtnX, barY, addBtnW, inputH, Colors.withAlpha(if (isAddHover) Colors.TEXT else Colors.ACCENT_DIM, alpha))
		val addLabel = "+ Add Pos"
		val alw = tr.getWidth(addLabel)
		context.drawText(tr, addLabel, addBtnX + (addBtnW - alw) / 2, barY + 7, Colors.withAlpha(Colors.TEXT, alpha), false)

		// 3. Waypoint Cards List
		val listTop = barY + inputH + 8
		val listH = h - (listTop - top)
		val waypoints = WaypointManager.waypoints

		if (waypoints.isEmpty()) {
			val emptyMsg = "No waypoints saved"
			val hintMsg = "Type a name above and click '+ Add Pos' to record your spot"
			val emw = tr.getWidth(emptyMsg)
			val hmw = tr.getWidth(hintMsg)
			context.drawText(tr, emptyMsg, left + (w - emw) / 2, listTop + 45, Colors.withAlpha(Colors.TEXT, alpha * 0.85f), false)
			context.drawText(tr, hintMsg, left + (w - hmw) / 2, listTop + 60, Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.70f), false)
			return
		}

		val cardH = 38
		val cardW = w - 8
		val maxVisible = 4

		val startIndex = waypointScrollOffset.coerceIn(0, max(0, waypoints.size - maxVisible))
		val visibleWaypoints = waypoints.drop(startIndex).take(maxVisible)

		visibleWaypoints.forEachIndexed { i, wp ->
			val cardY = listTop + i * (cardH + 4)
			val isHover = mx in (left + 4) until (left + 4 + cardW) && my in cardY until (cardY + cardH)

			// Card base
			val bg = if (isHover) Colors.CARD_HOVER else Colors.CARD
			context.fill(left + 4, cardY, left + 4 + cardW, cardY + cardH, Colors.withAlpha(bg, alpha))
			drawBorder(context, left + 4, cardY, cardW, cardH, Colors.withAlpha(Colors.CARD_BORDER, alpha))

			// Waypoint marker bullet
			context.fill(left + 10, cardY + 12, left + 14, cardY + 26, Colors.withAlpha(Colors.ACCENT, alpha))

			// Waypoint Name
			context.drawText(tr, wp.name, left + 20, cardY + 7, Colors.withAlpha(Colors.TEXT, alpha), false)

			// Coordinates & live distance
			val distStr = if (playerPos != null) {
				val dist = sqrt(playerPos.squaredDistanceTo(wp.posVec)).roundToInt()
				"${dist}m away"
			} else ""
			val coordStr = "(${wp.x.toInt()}, ${wp.y.toInt()}, ${wp.z.toInt()})  $distStr"
			context.drawText(tr, coordStr, left + 20, cardY + 20, Colors.withAlpha(Colors.TEXT_MUTED, alpha * 0.85f), false)

			// [ Travel ] Button
			val travelW = 50
			val travelH = 22
			val travelX = left + cardW - 12 - travelW - 26
			val travelY = cardY + 8
			val travelHover = mx in travelX until (travelX + travelW) && my in travelY until (travelY + travelH)
			val travelBg = if (travelHover) Colors.withAlpha(Colors.ACCENT, alpha) else Colors.withAlpha(Colors.BUTTON_BG, alpha)
			context.fill(travelX, travelY, travelX + travelW, travelY + travelH, travelBg)
			drawBorder(context, travelX, travelY, travelW, travelH, Colors.withAlpha(if (travelHover) Colors.ACCENT_BRIGHT else Colors.BORDER, alpha))
			val trText = "Travel"
			val trw = tr.getWidth(trText)
			context.drawText(tr, trText, travelX + (travelW - trw) / 2, travelY + 7, Colors.withAlpha(Colors.TEXT, alpha), false)

			// [ X ] Delete Button
			val delW = 20
			val delH = 22
			val delX = left + cardW - 12 - delW
			val delY = cardY + 8
			val delHover = mx in delX until (delX + delW) && my in delY until (delY + delH)
			val delBg = if (delHover) Colors.withAlpha(Colors.DANGER, alpha) else Colors.withAlpha(Colors.BUTTON_BG, alpha)
			context.fill(delX, delY, delX + delW, delY + delH, delBg)
			drawBorder(context, delX, delY, delW, delH, Colors.withAlpha(if (delHover) Colors.DANGER else Colors.BORDER, alpha))
			val delText = "x"
			val dw = tr.getWidth(delText)
			context.drawText(tr, delText, delX + (delW - dw) / 2, delY + 7, Colors.withAlpha(if (delHover) Colors.TEXT else Colors.TEXT_MUTED, alpha), false)
		}

		// Scroll indicator if waypoints exceed maxVisible
		if (waypoints.size > maxVisible) {
			val scrollBarH = listH - 10
			val scrollBarY = listTop + 5
			val scrollBarX = left + cardW + 1
			context.fill(scrollBarX, scrollBarY, scrollBarX + 2, scrollBarY + scrollBarH, Colors.withAlpha(Colors.BORDER, alpha * 0.4f))

			val thumbH = max(16, scrollBarH * maxVisible / waypoints.size)
			val maxScroll = waypoints.size - maxVisible
			val thumbY = scrollBarY + (scrollBarH - thumbH) * startIndex / maxScroll
			context.fill(scrollBarX, thumbY, scrollBarX + 2, thumbY + thumbH, Colors.withAlpha(Colors.ACCENT, alpha * 0.8f))
		}
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
		val scale = Easing.lerp(0.92f, 1f, Easing.easeOutCubic(progress))
		val openSlide = Easing.lerp(height * 0.65f, 0f, Easing.easeOutCubic(progress))
		val panelW = PANEL_WIDTH
		val panelH = PANEL_HEIGHT
		val panelX = (width - panelW) / 2f
		val panelY = (height - panelH) / 2f + openSlide
		val cx = panelX + panelW / 2f
		val cy = panelY + panelH / 2f

		val (mx, my) = transformMouse(click.x().toInt(), click.y().toInt(), cx, cy, scale)

		// 1. Sidebar Category Tab clicks
		val tabStartY = panelY.roundToInt() + 14 + 28
		val tabH = 30
		for (i in categories.indices) {
			val tabY = tabStartY + i * (tabH + 2)
			if (mx in (panelX.roundToInt() + 6) until (panelX.roundToInt() + SIDEBAR_WIDTH - 6) && my in tabY until (tabY + tabH)) {
				if (selectedTab != i) {
					prevTab = selectedTab
					selectedTab = i
					tabSwitchStartMs = Util.getMeasuringTimeMs()
					isNameInputFocused = false
				}
				return true
			}
		}

		// Prevent content misclicks during active tab transition
		val now = Util.getMeasuringTimeMs()
		val tabElapsed = (now - tabSwitchStartMs).toFloat()
		if (tabSwitchStartMs > 0L && tabElapsed < TAB_ANIM_MS) {
			return true
		}

		val contentLeft = panelX.roundToInt() + SIDEBAR_WIDTH + 8
		val contentTop = panelY.roundToInt() + 8
		val contentW = panelW - SIDEBAR_WIDTH - 16
		val listTop = contentTop + 28

		// 2. Options clicks (General, Foraging, Movement)
		val currentOptions = when (selectedTab) {
			0 -> generalOptions
			1 -> foragingOptions
			2 -> movementOptions
			else -> null
		}

		if (currentOptions != null) {
			val cardH = 46
			val cardW = contentW - 8
			currentOptions.forEachIndexed { index, opt ->
				val cardX = contentLeft + 4
				val cardY = listTop + index * (cardH + 6)
				if (mx in cardX until (cardX + cardW) && my in cardY until (cardY + cardH)) {
					opt.setter(!opt.getter())
					return true
				}
			}
			return true
		}

		// 3. Waypoints Tab clicks
		if (selectedTab == 3) {
			val barY = listTop + 2
			val addBtnW = 100
			val inputW = contentW - 16 - addBtnW - 8
			val inputH = 22
			val inputX = contentLeft + 4

			// Text input focus
			if (mx in inputX until (inputX + inputW) && my in barY until (barY + inputH)) {
				isNameInputFocused = true
				return true
			} else {
				isNameInputFocused = false
			}

			// Add Current Position Button
			val addBtnX = inputX + inputW + 6
			if (mx in addBtnX until (addBtnX + addBtnW) && my in barY until (barY + inputH)) {
				val player = client?.player
				if (player != null) {
					WaypointManager.addWaypoint(nameInputText, Vec3d(player.x, player.y, player.z))
					nameInputText = ""
				}
				return true
			}

			// Waypoint List Cards
			val wpListTop = barY + inputH + 8
			val cardH = 38
			val cardW = contentW - 8
			val maxVisible = 4
			val waypoints = WaypointManager.waypoints
			val startIndex = waypointScrollOffset.coerceIn(0, max(0, waypoints.size - maxVisible))
			val visibleWaypoints = waypoints.drop(startIndex).take(maxVisible)

			visibleWaypoints.forEachIndexed { i, wp ->
				val cardY = wpListTop + i * (cardH + 4)

				// Travel Button
				val travelW = 50
				val travelH = 22
				val travelX = contentLeft + cardW - 12 - travelW - 26
				val travelY = cardY + 8
				if (mx in travelX until (travelX + travelW) && my in travelY until (travelY + travelH)) {
					val targetPos = BlockPos.ofFloored(wp.x, wp.y, wp.z)
					HelperConfig.manualRouteGoal = targetPos
					MovementController.setDestination(PositionTarget(wp.posVec, arrivalRadius = 1.0))
					HelperConfig.autoWalk = true
					requestClose()
					return true
				}

				// Delete Button
				val delW = 20
				val delH = 22
				val delX = contentLeft + cardW - 12 - delW
				val delY = cardY + 8
				if (mx in delX until (delX + delW) && my in delY until (delY + delH)) {
					WaypointManager.removeWaypoint(wp.id)
					return true
				}
			}
		}

		return true
	}

	override fun keyPressed(input: KeyInput): Boolean {
		if (isNameInputFocused) {
			val key = input.key()
			if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (nameInputText.isNotEmpty()) {
					nameInputText = nameInputText.dropLast(1)
				}
				return true
			}
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
				val player = client?.player
				if (player != null) {
					WaypointManager.addWaypoint(nameInputText, Vec3d(player.x, player.y, player.z))
					nameInputText = ""
					isNameInputFocused = false
				}
				return true
			}
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				isNameInputFocused = false
				return true
			}
			return true
		}

		if (input.key() == GLFW.GLFW_KEY_ESCAPE || input.key() == GLFW.GLFW_KEY_H) {
			requestClose()
			return true
		}
		return super.keyPressed(input)
	}

	override fun charTyped(input: net.minecraft.client.input.CharInput): Boolean {
		if (isNameInputFocused && input.isValidChar) {
			val str = input.asString()
			for (chr in str) {
				if (chr.isLetterOrDigit() || chr.isWhitespace() || chr in "_-()[]#") {
					if (nameInputText.length < 24) {
						nameInputText += chr
					}
				}
			}
			return true
		}
		return super.charTyped(input)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
		if (selectedTab == 3) {
			if (verticalAmount > 0) {
				waypointScrollOffset = max(0, waypointScrollOffset - 1)
			} else if (verticalAmount < 0) {
				val maxScroll = max(0, WaypointManager.waypoints.size - 4)
				waypointScrollOffset = min(maxScroll, waypointScrollOffset + 1)
			}
			return true
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
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

	private fun transformMouse(mouseX: Int, mouseY: Int, cx: Float, cy: Float, scale: Float): Pair<Int, Int> {
		val lx = ((mouseX - cx) / scale + cx).roundToInt()
		val ly = ((mouseY - cy) / scale + cy).roundToInt()
		return lx to ly
	}

	private data class OptionRow(
		val label: String,
		val description: String,
		val getter: () -> Boolean,
		val setter: (Boolean) -> Unit
	)

	private data class CategoryTab(
		val title: String,
		val subtitle: String
	)

	companion object {
		private const val PANEL_WIDTH = 470
		private const val PANEL_HEIGHT = 310
		private const val SIDEBAR_WIDTH = 125
		private const val OPEN_MS = 340f
		private const val CLOSE_MS = 220f
		private const val TAB_ANIM_MS = 380f // Slower, silky smooth transition
	}
}
