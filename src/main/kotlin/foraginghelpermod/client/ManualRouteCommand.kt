package foraginghelpermod.client

import com.mojang.brigadier.arguments.IntegerArgumentType
import foraginghelpermod.client.path.WalkController
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos

object ManualRouteCommand {
	fun register() {
		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			val destination = argument("z", IntegerArgumentType.integer()).executes { context ->
				val pos = BlockPos(
					IntegerArgumentType.getInteger(context, "x"),
					IntegerArgumentType.getInteger(context, "y"),
					IntegerArgumentType.getInteger(context, "z"),
				)
				HelperConfig.manualRouteGoal = pos
				val target = com.github.foragerhelper.target.PositionTarget(
					net.minecraft.util.math.Vec3d(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5),
					arrivalRadius = 1.0
				)
				com.github.foragerhelper.movement.MovementController.setDestination(target)
				context.source.sendFeedback(Text.literal("Forager route set to (${pos.x}, ${pos.y}, ${pos.z})"))
				1
			}
			val route = literal("forageroute")
				.then(argument("x", IntegerArgumentType.integer())
					.then(argument("y", IntegerArgumentType.integer()).then(destination)))
				dispatcher.register(route.then(literal("clear").executes { context ->
				HelperConfig.manualRouteGoal = null
				com.github.foragerhelper.movement.MovementController.stop()
				WalkController.stop()
				context.source.sendFeedback(Text.literal("Forager route cleared"))
				1
			}))
		}
	}
}
