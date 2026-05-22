package net.stargazer.set_anger;

import com.google.common.collect.Lists;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = SetAnger.MOD_ID)
public final class SetAngerCommands {
    private static final DynamicCommandExceptionType ERROR_SUBJECT_NOT_MOB = new DynamicCommandExceptionType(
            name -> Component.literal("Cannot set anger for " + name + " because it is not a mob.")
    );
    private static final DynamicCommandExceptionType ERROR_TARGET_NOT_LIVING = new DynamicCommandExceptionType(
            name -> Component.literal("Cannot target " + name + " because it is not a living entity.")
    );
    private static final DynamicCommandExceptionType ERROR_NO_TARGET_IN_LEVEL = new DynamicCommandExceptionType(
            name -> Component.literal("No selected living target is in the same dimension as " + name + ".")
    );
    private static final Dynamic2CommandExceptionType ERROR_SET_FAILED = new Dynamic2CommandExceptionType(
            (subject, target) -> Component.literal("Tried to set " + subject + " angry at " + target + ", but another hook rejected the target.")
    );
    private static final SimpleCommandExceptionType ERROR_RELOAD_FAILED = new SimpleCommandExceptionType(
            Component.literal("Failed to reload Set Anger config. Check the server log for details.")
    );

    private SetAngerCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(createSetAngerCommand("setanger"));
        dispatcher.register(createSetAngerCommand("sa"));
        SetAngerScaledCommands.register(dispatcher, event.getBuildContext());
        SetAngerScheduleCommands.register(dispatcher);
        registerExecuteOnRelations(dispatcher);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createSetAngerCommand(String name) {
        return Commands.literal(name)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reloadconfig")
                        .executes(context -> reloadConfig(context.getSource())))
                .then(Commands.literal("clear")
                        .then(Commands.argument("subjects", EntityArgument.entities())
                                .executes(context -> clearAnger(
                                        context.getSource(),
                                        EntityArgument.getEntities(context, "subjects")
                                ))))
                .then(Commands.argument("subjects", EntityArgument.entities())
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(context -> setAnger(
                                        context.getSource(),
                                        EntityArgument.getEntities(context, "subjects"),
                                        EntityArgument.getEntities(context, "targets")
                                ))));
    }

    private static int setAnger(CommandSourceStack source, Collection<? extends Entity> subjectEntities, Collection<? extends Entity> targetEntities)
            throws CommandSyntaxException {
        List<Mob> subjects = toMobs(subjectEntities);
        List<LivingEntity> targets = toLivingTargets(targetEntities);
        List<AngerAssignment> assignments = new ArrayList<>(subjects.size());

        for (Mob subject : subjects) {
            assignments.add(new AngerAssignment(subject, findNearestUsableTarget(subject, targets)));
        }

        for (AngerAssignment assignment : assignments) {
            SetAngerHandler.setAnger(assignment.subject(), assignment.target());
            if (assignment.subject().getTarget() != assignment.target()) {
                throw ERROR_SET_FAILED.create(assignment.subject().getDisplayName().getString(), assignment.target().getDisplayName().getString());
            }
        }

        int result = assignments.size();
        sendDebugSuccess(source, () -> Component.literal("Set anger for " + result + " mob" + (result == 1 ? "" : "s") + "."));
        return result;
    }

    private static int clearAnger(CommandSourceStack source, Collection<? extends Entity> subjectEntities) throws CommandSyntaxException {
        List<Mob> subjects = toMobs(subjectEntities);
        for (Mob subject : subjects) {
            SetAngerHandler.clearAnger(subject);
        }

        int result = subjects.size();
        sendDebugSuccess(source, () -> Component.literal("Cleared anger for " + result + " mob" + (result == 1 ? "" : "s") + "."));
        return result;
    }

    private static int reloadConfig(CommandSourceStack source) throws CommandSyntaxException {
        Path configPath;
        try {
            MinecraftServer server = source.getServer();
            configPath = SetAngerConfig.reload();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                server.getCommands().sendCommands(player);
            }
        } catch (RuntimeException exception) {
            SetAnger.LOGGER.error("Failed to reload Set Anger config", exception);
            throw ERROR_RELOAD_FAILED.create();
        }

        source.sendSuccess(
                () -> Component.literal("Reloaded Set Anger configuration."),
                true
        );
        return 1;
    }

    private static List<Mob> toMobs(Collection<? extends Entity> entities) throws CommandSyntaxException {
        List<Mob> mobs = new ArrayList<>(entities.size());
        for (Entity entity : entities) {
            if (!(entity instanceof Mob mob)) {
                throw ERROR_SUBJECT_NOT_MOB.create(entity.getDisplayName().getString());
            }
            mobs.add(mob);
        }
        return mobs;
    }

    private static List<LivingEntity> toLivingTargets(Collection<? extends Entity> entities) throws CommandSyntaxException {
        List<LivingEntity> targets = new ArrayList<>(entities.size());
        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity target)) {
                throw ERROR_TARGET_NOT_LIVING.create(entity.getDisplayName().getString());
            }
            targets.add(target);
        }
        return targets;
    }

    private static LivingEntity findNearestUsableTarget(Mob subject, List<LivingEntity> targets) throws CommandSyntaxException {
        return targets.stream()
                .filter(target -> target != subject)
                .filter(LivingEntity::isAlive)
                .filter(target -> target.level() == subject.level())
                .min(Comparator.comparingDouble(subject::distanceToSqr))
                .orElseThrow(() -> ERROR_NO_TARGET_IN_LEVEL.create(subject.getDisplayName().getString()));
    }

    private record AngerAssignment(Mob subject, LivingEntity target) {
    }

    private static void sendDebugSuccess(CommandSourceStack source, java.util.function.Supplier<Component> message) {
        if (SetAngerConfig.debugCommandFeedback()) {
            source.sendSuccess(message, false);
        }
    }

    private static List<CommandSourceStack> findTargetingSources(CommandSourceStack source, double maxDistance) {
        Entity target = source.getEntity();
        if (target == null) {
            return List.of();
        }

        double maxDistanceSqr = maxDistance >= 0.0D ? maxDistance * maxDistance : -1.0D;
        List<CommandSourceStack> sources = new ArrayList<>();
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob) || mob.getTarget() != target) {
                    continue;
                }
                if (maxDistanceSqr >= 0.0D && (mob.level() != target.level() || mob.distanceToSqr(target) > maxDistanceSqr)) {
                    continue;
                }

                sources.add(source.withEntity(mob)
                        .withPosition(mob.position())
                        .withRotation(mob.getRotationVector()));
            }
        }

        return sources;
    }

    private static void registerExecuteOnRelations(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNode<CommandSourceStack> execute = dispatcher.getRoot().getChild("execute");
        if (execute == null) {
            return;
        }

        CommandNode<CommandSourceStack> on = execute.getChild("on");
        if (on == null) {
            return;
        }

        if (on.getChild("targeting") == null) {
            on.addChild(Commands.literal("targeting")
                    .fork(execute, context -> findTargetingSources(context.getSource(), -1.0D))
                    .build());
        }
        if (on.getChild("targeting_near") == null) {
            on.addChild(Commands.literal("targeting_near")
                    .then(Commands.argument("max", DoubleArgumentType.doubleArg(0.0D))
                            .fork(execute, context -> findTargetingSources(
                                    context.getSource(),
                                    DoubleArgumentType.getDouble(context, "max")
                            )))
                    .build());
        }
        if (on.getChild("victim") == null) {
            on.addChild(Commands.literal("victim")
                    .fork(execute, context -> {
                        CommandSourceStack stack = context.getSource();
                        if (!SetAngerConfig.enableExecuteOnVictim()) {
                            return List.of();
                        }

                        Entity entity = stack.getEntity();
                        if (!(entity instanceof LivingEntity livingEntity)) {
                            return List.of();
                        }

                        LivingEntity victim = livingEntity.getLastHurtMob();
                        if (victim == null || victim.isRemoved()) {
                            return List.of();
                        }

                        return Lists.newArrayList(stack.withEntity(victim));
                    })
                    .build());
        }
    }
}
