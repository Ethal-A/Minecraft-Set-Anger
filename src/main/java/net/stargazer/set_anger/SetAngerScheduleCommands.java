package net.stargazer.set_anger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = SetAnger.MOD_ID)
public final class SetAngerScheduleCommands {
    private static final SimpleCommandExceptionType ERROR_EMPTY_COMMAND = new SimpleCommandExceptionType(
            Component.literal("Scheduled command cannot be empty.")
    );

    private SetAngerScheduleCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("scheduleas")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("subjects", EntityArgument.entities())
                        .then(Commands.literal("clear")
                                .executes(context -> clear(
                                        context.getSource(),
                                        EntityArgument.getEntities(context, "subjects")
                                )))
                        .then(Commands.argument("time", TimeArgument.time(1))
                                .then(modeBranch("append", false))
                                .then(modeBranch("replace", true)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> modeBranch(String name, boolean replace) {
        return Commands.literal(name)
                .then(runBranch(replace, false))
                .then(atBranch(replace, false))
                .then(Commands.literal("do_not_clear_on_death")
                        .then(runBranch(replace, true))
                        .then(atBranch(replace, true)));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> runBranch(boolean replace, boolean keepOnDeath) {
        return Commands.literal("run")
                .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(context -> schedule(context, replace, keepOnDeath, command(context))))
                .then(Commands.literal("function")
                        .then(Commands.argument("function", StringArgumentType.greedyString())
                                .executes(context -> schedule(context, replace, keepOnDeath, "function " + command(context, "function")))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> atBranch(boolean replace, boolean keepOnDeath) {
        return Commands.literal("at")
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(new String[]{"@s", "@p", "@a", "@e"}, builder))
                        .then(Commands.literal("run")
                                .then(Commands.argument("command", StringArgumentType.greedyString())
                                        .executes(context -> schedule(
                                                context,
                                                replace,
                                                keepOnDeath,
                                                "execute at " + StringArgumentType.getString(context, "target") + " run " + command(context)
                                        )))
                                .then(Commands.literal("function")
                                        .then(Commands.argument("function", StringArgumentType.greedyString())
                                                .executes(context -> schedule(
                                                        context,
                                                        replace,
                                                        keepOnDeath,
                                                        "execute at " + StringArgumentType.getString(context, "target") + " run function " + command(context, "function")
                                                ))))));
    }

    private static int clear(CommandSourceStack source, Collection<? extends Entity> entities) {
        SetAngerScheduleData data = SetAngerScheduleData.get(source.getServer());
        int removed = 0;
        for (Entity entity : entities) {
            removed += data.clear(entity.getUUID());
        }

        int result = removed;
        sendDebugSuccess(source, () -> Component.literal("Cleared " + result + " scheduleas task" + (result == 1 ? "" : "s") + "."));
        return removed;
    }

    private static int schedule(CommandContext<CommandSourceStack> context, boolean replace, boolean keepOnDeath, String command)
            throws CommandSyntaxException {
        if (command.isBlank()) {
            throw ERROR_EMPTY_COMMAND.create();
        }

        Collection<? extends Entity> entities = EntityArgument.getEntities(context, "subjects");
        int delayTicks = context.getArgument("time", Integer.class);
        SetAngerScheduleData data = SetAngerScheduleData.get(context.getSource().getServer());
        int scheduled = 0;
        for (Entity entity : entities) {
            scheduled += data.schedule(context.getSource().getServer(), entity, delayTicks, replace, keepOnDeath, command);
        }

        int result = scheduled;
        sendDebugSuccess(context.getSource(), () -> Component.literal("Scheduled " + result + " scheduleas task" + (result == 1 ? "" : "s") + "."));
        return scheduled;
    }

    private static String command(CommandContext<CommandSourceStack> context) {
        return command(context, "command");
    }

    private static String command(CommandContext<CommandSourceStack> context, String argument) {
        return StringArgumentType.getString(context, argument).trim();
    }

    private static void sendDebugSuccess(CommandSourceStack source, java.util.function.Supplier<Component> message) {
        if (SetAngerConfig.debugCommandFeedback()) {
            source.sendSuccess(message, false);
        }
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        SetAngerScheduleData.get(event.getServer()).tick(event.getServer());
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide) {
            SetAngerScheduleData.get(event.getEntity().getServer()).clearOnDeath(event.getEntity());
        }
    }
}
