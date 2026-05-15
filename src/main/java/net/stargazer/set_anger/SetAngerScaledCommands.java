package net.stargazer.set_anger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.FunctionInstantiationException;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ObjectiveArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.ScoreHolderArgument;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.InstantiatedFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;

public final class SetAngerScaledCommands {
    private static final double MAX_ABSOLUTE_VALUE = 1_000_000.0D;
    private static final int VANILLA_MAX_FOOD_LEVEL = 20;
    private static final String[] OPERATION_SUGGESTIONS = {"addition", "multiply_base", "multiply_total"};
    private static final String[] DURATION_SUGGESTIONS = {"30s", "1m", "5m", "1h", "untildeath", "infinite"};

    private static final DynamicCommandExceptionType ERROR_NOT_LIVING = new DynamicCommandExceptionType(
            name -> Component.literal(name + " is not a living entity.")
    );
    private static final DynamicCommandExceptionType ERROR_NO_HEALTH = new DynamicCommandExceptionType(
            name -> Component.literal(name + " does not have health.")
    );
    private static final DynamicCommandExceptionType ERROR_NO_HUNGER = new DynamicCommandExceptionType(
            name -> Component.literal(name + " does not have hunger.")
    );
    private static final Dynamic2CommandExceptionType ERROR_ATTRIBUTE_MISSING = new Dynamic2CommandExceptionType(
            (entity, attribute) -> Component.literal(entity + " does not have attribute " + attribute + ".")
    );
    private static final DynamicCommandExceptionType ERROR_UNKNOWN_ATTRIBUTE = new DynamicCommandExceptionType(
            attribute -> Component.literal("Unknown attribute " + attribute + ".")
    );
    private static final DynamicCommandExceptionType ERROR_BAD_VALUE = new DynamicCommandExceptionType(
            value -> Component.literal("Computed value " + value + " is not valid.")
    );
    private static final DynamicCommandExceptionType ERROR_BAD_OPERATION = new DynamicCommandExceptionType(
            operation -> Component.literal("Unknown operation " + operation + ". Use addition, multiply_base, or multiply_total.")
    );
    private static final DynamicCommandExceptionType ERROR_BAD_DURATION = new DynamicCommandExceptionType(
            duration -> Component.literal("Invalid duration " + duration + ". Use infinite, untildeath, ticks such as 200t, or time such as 30s.")
    );
    private static final DynamicCommandExceptionType ERROR_FUNCTION_NOT_FOUND = new DynamicCommandExceptionType(
            function -> Component.literal("Unknown function " + function + ".")
    );
    private static final Dynamic2CommandExceptionType ERROR_FUNCTION_FAILED = new Dynamic2CommandExceptionType(
            (function, error) -> Component.literal("Could not run function " + function + ": " + error)
    );
    private static final SimpleCommandExceptionType ERROR_MANA_UNAVAILABLE = new SimpleCommandExceptionType(
            Component.literal("The mana command is not available. Install Iron's Spells 'n Spellbooks to use /scalemana.")
    );
    private static final DynamicCommandExceptionType ERROR_NO_SAME_DAMAGE_TYPE = new DynamicCommandExceptionType(
            name -> Component.literal("Cannot use damage_type same for " + name + " because there is no recent damage source to copy.")
    );

    private SetAngerScaledCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(createHealCommand());
        dispatcher.register(createHungerCommand());
        dispatcher.register(createScaleDamageCommand(buildContext));
        dispatcher.register(createScaleHealCommand(buildContext));
        dispatcher.register(createScaleHungerCommand(buildContext));
        dispatcher.register(createScaleManaCommand(buildContext));
        dispatcher.register(createScaleAttributeCommand(buildContext));
        dispatcher.register(createScaleValueCommand(buildContext));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createHealCommand() {
        return Commands.literal("heal")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                                        .executes(context -> applyHealthCommand(
                                                context,
                                                DoubleArgumentType.getDouble(context, "amount"),
                                                HealthMode.ADD
                                        ))))
                        .then(Commands.literal("multiply_current")
                                .then(Commands.argument("factor", DoubleArgumentType.doubleArg())
                                        .executes(context -> applyHealthCommand(
                                                context,
                                                DoubleArgumentType.getDouble(context, "factor"),
                                                HealthMode.MULTIPLY_CURRENT
                                        ))))
                        .then(Commands.literal("multiply_total")
                                .then(Commands.argument("factor", DoubleArgumentType.doubleArg())
                                        .executes(context -> applyHealthCommand(
                                                context,
                                                DoubleArgumentType.getDouble(context, "factor"),
                                                HealthMode.MULTIPLY_TOTAL
                                        )))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createHungerCommand() {
        return Commands.literal("hunger")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.literal("add")
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                                        .executes(context -> applyHungerCommand(
                                                context,
                                                DoubleArgumentType.getDouble(context, "amount"),
                                                HungerMode.ADD
                                        ))))
                        .then(Commands.literal("multiply_current")
                                .then(Commands.argument("factor", DoubleArgumentType.doubleArg())
                                        .executes(context -> applyHungerCommand(
                                                context,
                                                DoubleArgumentType.getDouble(context, "factor"),
                                                HungerMode.MULTIPLY_CURRENT
                                        ))))
                        .then(Commands.literal("multiply_total")
                                .then(Commands.argument("factor", DoubleArgumentType.doubleArg())
                                        .executes(context -> applyHungerCommand(
                                                context,
                                                DoubleArgumentType.getDouble(context, "factor"),
                                                HungerMode.MULTIPLY_TOTAL
                                        )))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createScaleDamageCommand(CommandBuildContext buildContext) {
        return Commands.literal("scaledamage")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(valueBranches(buildContext, () -> damageTypeBranch(buildContext))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createScaleHealCommand(CommandBuildContext buildContext) {
        return Commands.literal("scaleheal")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(valueBranches(buildContext, () -> Commands.argument("scale", DoubleArgumentType.doubleArg())
                                        .executes(SetAngerScaledCommands::scaleHeal)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createScaleHungerCommand(CommandBuildContext buildContext) {
        return Commands.literal("scalehunger")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(valueBranches(buildContext, () -> Commands.argument("scale", DoubleArgumentType.doubleArg())
                                        .executes(SetAngerScaledCommands::scaleHunger)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createScaleManaCommand(CommandBuildContext buildContext) {
        return Commands.literal("scalemana")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(valueBranches(buildContext, () -> Commands.argument("scale", DoubleArgumentType.doubleArg())
                                        .executes(SetAngerScaledCommands::scaleMana)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createScaleAttributeCommand(CommandBuildContext buildContext) {
        return Commands.literal("scaleattribute")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(attributeArgument("attribute")
                                .then(Commands.literal("set_modifier")
                                        .then(Commands.argument("modifier", ResourceLocationArgument.id())
                                                .then(valueBranches(buildContext, () -> scaleAttributeAmountArgument("scale")))
                                                .then(Commands.literal("value")
                                                        .then(scaleAttributeAmountArgument("value")))))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> scaleAttributeAmountArgument(String argumentName) {
        return Commands.argument(argumentName, DoubleArgumentType.doubleArg())
                .then(Commands.literal("duration")
                        .then(durationArgument()
                                .executes(context -> scaleAttribute(context, AttributeModifier.Operation.ADD_VALUE))))
                .then(Commands.literal("operation")
                        .then(operationArgument()
                                .then(Commands.literal("duration")
                                        .then(durationArgument()
                                                .executes(context -> scaleAttribute(context, parseOperation(StringArgumentType.getString(context, "operation"))))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createScaleValueCommand(CommandBuildContext buildContext) {
        return Commands.literal("scalevalue")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(valueBranches(buildContext, () -> Commands.argument("scale", DoubleArgumentType.doubleArg())
                                .then(Commands.literal("run")
                                        .then(Commands.literal("function")
                                                .then(Commands.argument("function", ResourceLocationArgument.id())
                                                        .executes(SetAngerScaledCommands::runFunctionWithScaledValue)))))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> valueBranches(
            CommandBuildContext buildContext,
            Supplier<ArgumentBuilder<CommandSourceStack, ?>> afterScale
    ) {
        return Commands.literal("from")
                        .then(Commands.argument("source", EntityArgument.entity())
                        .then(Commands.literal("attribute")
                                .then(attributeArgument("source_attribute")
                                        .then(Commands.literal("scale")
                                                .then(afterScale.get())))))
                .then(Commands.literal("score")
                        .then(Commands.argument("score_holder", ScoreHolderArgument.scoreHolder())
                                .suggests(ScoreHolderArgument.SUGGEST_SCORE_HOLDERS)
                                .then(Commands.argument("objective", ObjectiveArgument.objective())
                                        .then(Commands.literal("scale")
                                                .then(afterScale.get())))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> damageTypeBranch(CommandBuildContext buildContext) {
        Command<CommandSourceStack> sameWithoutAttacker = context -> scaleDamage(context, DamageTypeMode.SAME, false);
        Command<CommandSourceStack> sameWithAttacker = context -> scaleDamage(context, DamageTypeMode.SAME, true);
        Command<CommandSourceStack> explicitWithoutAttacker = context -> scaleDamage(context, DamageTypeMode.EXPLICIT, false);
        Command<CommandSourceStack> explicitWithAttacker = context -> scaleDamage(context, DamageTypeMode.EXPLICIT, true);

        return Commands.argument("scale", DoubleArgumentType.doubleArg())
                .executes(sameWithoutAttacker)
                .then(Commands.literal("by")
                        .then(Commands.argument("attacker", EntityArgument.entity())
                                .executes(sameWithAttacker)))
                .then(Commands.literal("damage_type")
                        .then(Commands.literal("same")
                                .executes(sameWithoutAttacker)
                                .then(Commands.literal("by")
                                        .then(Commands.argument("attacker", EntityArgument.entity())
                                                .executes(sameWithAttacker))))
                        .then(Commands.argument("damage_type", ResourceArgument.resource(buildContext, Registries.DAMAGE_TYPE))
                                .executes(explicitWithoutAttacker)
                                .then(Commands.literal("by")
                                        .then(Commands.argument("attacker", EntityArgument.entity())
                                                .executes(explicitWithAttacker)))));
    }

    private static int scaleDamage(CommandContext<CommandSourceStack> context, DamageTypeMode damageTypeMode, boolean withAttacker) throws CommandSyntaxException {
        float amount = checkedPositiveFloat(computeValue(context));
        Holder.Reference<DamageType> explicitDamageType = damageTypeMode == DamageTypeMode.EXPLICIT
                ? ResourceArgument.getResource(context, "damage_type", Registries.DAMAGE_TYPE)
                : null;
        Entity attacker = withAttacker ? EntityArgument.getEntity(context, "attacker") : null;

        int changed = 0;
        for (Entity target : EntityArgument.getEntities(context, "targets")) {
            DamageSource damageSource = createDamageSource(context, target, damageTypeMode, explicitDamageType, attacker);
            if (target.hurt(damageSource, amount)) {
                changed++;
            }
        }

        int result = changed;
        sendDebugSuccess(context.getSource(), () -> Component.literal("Applied scaled damage to " + result + " target" + (result == 1 ? "" : "s") + "."));
        return changed;
    }

    private static DamageSource createDamageSource(
            CommandContext<CommandSourceStack> context,
            Entity target,
            DamageTypeMode damageTypeMode,
            Holder.Reference<DamageType> explicitDamageType,
            Entity attacker
    ) throws CommandSyntaxException {
        if (damageTypeMode == DamageTypeMode.EXPLICIT) {
            return attacker == null ? new DamageSource(explicitDamageType) : new DamageSource(explicitDamageType, attacker);
        }

        DamageSource sameSource = recentDamageSource(target)
                .or(() -> recentDamageSource(context.getSource().getEntity()))
                .orElseThrow(() -> ERROR_NO_SAME_DAMAGE_TYPE.create(target.getDisplayName().getString()));
        return attacker == null ? sameSource : new DamageSource(sameSource.typeHolder(), attacker);
    }

    private static Optional<DamageSource> recentDamageSource(Entity entity) {
        if (entity instanceof LivingEntity livingEntity && livingEntity.getLastDamageSource() != null) {
            return Optional.of(livingEntity.getLastDamageSource());
        }
        return Optional.empty();
    }

    private static int scaleHeal(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        float amount = checkedPositiveFloat(computeValue(context));
        int changed = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (!(entity instanceof LivingEntity livingEntity)) {
                throw ERROR_NOT_LIVING.create(entity.getDisplayName().getString());
            }

            livingEntity.heal(amount);
            changed++;
        }

        int result = changed;
        sendDebugSuccess(context.getSource(), () -> Component.literal("Applied scaled healing to " + result + " target" + (result == 1 ? "" : "s") + "."));
        return changed;
    }

    private static int scaleHunger(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        int amount = Mth.floor(computeValue(context));
        int changed = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            applyHungerDelta(entity, amount);
            changed++;
        }

        int result = changed;
        sendDebugSuccess(context.getSource(), () -> Component.literal("Adjusted hunger for " + result + " player" + (result == 1 ? "" : "s") + "."));
        return changed;
    }

    private static int scaleMana(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (context.getSource().getServer().getCommands().getDispatcher().getRoot().getChild("mana") == null) {
            throw ERROR_MANA_UNAVAILABLE.create();
        }

        double amount = computeValue(context);
        String command = "mana add @s " + formatValue(amount);
        int changed = 0;
        for (Entity target : EntityArgument.getEntities(context, "targets")) {
            CommandSourceStack targetSource = context.getSource()
                    .withEntity(target)
                    .withPosition(target.position())
                    .withRotation(target.getRotationVector());
            context.getSource().getServer().getCommands().performPrefixedCommand(targetSource, command);
            changed++;
        }

        int result = changed;
        sendDebugSuccess(context.getSource(), () -> Component.literal("Adjusted mana for " + result + " target" + (result == 1 ? "" : "s") + "."));
        return changed;
    }

    private static int scaleAttribute(CommandContext<CommandSourceStack> context, AttributeModifier.Operation operation) throws CommandSyntaxException {
        double amount = computeAttributeModifierAmount(context);
        Holder.Reference<Attribute> attribute = resolveAttribute(ResourceLocationArgument.getId(context, "attribute"));
        ResourceLocation modifierId = ResourceLocationArgument.getId(context, "modifier");
        SetAngerScaledModifiers.ModifierDuration duration = parseDuration(StringArgumentType.getString(context, "duration"));

        int changed = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            if (!(entity instanceof LivingEntity livingEntity)) {
                throw ERROR_NOT_LIVING.create(entity.getDisplayName().getString());
            }

            try {
                SetAngerScaledModifiers.apply(livingEntity, attribute, modifierId, amount, operation, duration);
            } catch (IllegalArgumentException exception) {
                throw ERROR_ATTRIBUTE_MISSING.create(entity.getDisplayName().getString(), attribute.unwrapKey().map(key -> key.location().toString()).orElse(attribute.value().toString()));
            }
            changed++;
        }

        int result = changed;
        sendDebugSuccess(context.getSource(), () -> Component.literal("Applied scaled attribute modifier to " + result + " target" + (result == 1 ? "" : "s") + "."));
        return changed;
    }

    private static int applyHealthCommand(CommandContext<CommandSourceStack> context, double value, HealthMode mode) throws CommandSyntaxException {
        checkedFiniteBounded(value);
        int changed = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            LivingEntity livingEntity = livingEntityWithHealth(entity);
            double delta = switch (mode) {
                case ADD -> value;
                case MULTIPLY_CURRENT -> livingEntity.getHealth() * value;
                case MULTIPLY_TOTAL -> livingEntity.getMaxHealth() * value;
            };
            applyHealthDelta(livingEntity, checkedFiniteBounded(delta));
            changed++;
        }

        int result = changed;
        sendDebugSuccess(context.getSource(), () -> Component.literal("Adjusted health for " + result + " target" + (result == 1 ? "" : "s") + "."));
        return changed;
    }

    private static int applyHungerCommand(CommandContext<CommandSourceStack> context, double value, HungerMode mode) throws CommandSyntaxException {
        checkedFiniteBounded(value);
        int changed = 0;
        for (Entity entity : EntityArgument.getEntities(context, "targets")) {
            FoodData foodData = foodData(entity);
            double delta = switch (mode) {
                case ADD -> value;
                case MULTIPLY_CURRENT -> foodData.getFoodLevel() * value;
                case MULTIPLY_TOTAL -> VANILLA_MAX_FOOD_LEVEL * value;
            };
            applyHungerDelta(entity, Mth.floor(checkedFiniteBounded(delta)));
            changed++;
        }

        int result = changed;
        sendDebugSuccess(context.getSource(), () -> Component.literal("Adjusted hunger for " + result + " target" + (result == 1 ? "" : "s") + "."));
        return changed;
    }

    private static LivingEntity livingEntityWithHealth(Entity entity) throws CommandSyntaxException {
        if (!(entity instanceof LivingEntity livingEntity)) {
            throw ERROR_NO_HEALTH.create(entity.getDisplayName().getString());
        }
        return livingEntity;
    }

    private static void applyHealthDelta(LivingEntity livingEntity, double delta) throws CommandSyntaxException {
        float amount = (float) Math.abs(delta);
        try {
            if (delta >= 0.0D) {
                livingEntity.heal(amount);
            } else {
                livingEntity.hurt(livingEntity.damageSources().generic(), amount);
            }
        } catch (RuntimeException exception) {
            throw ERROR_NO_HEALTH.create(livingEntity.getDisplayName().getString());
        }
    }

    private static FoodData foodData(Entity entity) throws CommandSyntaxException {
        if (!(entity instanceof Player player)) {
            throw ERROR_NO_HUNGER.create(entity.getDisplayName().getString());
        }

        try {
            FoodData foodData = player.getFoodData();
            if (foodData == null) {
                throw ERROR_NO_HUNGER.create(entity.getDisplayName().getString());
            }
            return foodData;
        } catch (CommandSyntaxException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ERROR_NO_HUNGER.create(entity.getDisplayName().getString());
        }
    }

    private static void applyHungerDelta(Entity entity, int amount) throws CommandSyntaxException {
        FoodData foodData = foodData(entity);
        try {
            foodData.setFoodLevel(Mth.clamp(foodData.getFoodLevel() + amount, 0, VANILLA_MAX_FOOD_LEVEL));
        } catch (RuntimeException exception) {
            throw ERROR_NO_HUNGER.create(entity.getDisplayName().getString());
        }
    }

    private static int runFunctionWithScaledValue(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "name");
        double value = computeValue(context);
        ResourceLocation functionId = ResourceLocationArgument.getId(context, "function");
        Optional<CommandFunction<CommandSourceStack>> function = context.getSource().getServer().getFunctions().get(functionId);
        if (function.isEmpty()) {
            throw ERROR_FUNCTION_NOT_FOUND.create(functionId);
        }

        CompoundTag arguments = new CompoundTag();
        arguments.putString(name, formatValue(value));

        InstantiatedFunction<CommandSourceStack> instantiated;
        try {
            instantiated = function.get().instantiate(arguments, context.getSource().getServer().getCommands().getDispatcher());
        } catch (FunctionInstantiationException exception) {
            throw ERROR_FUNCTION_FAILED.create(functionId, exception.getMessage());
        }

        Commands.executeCommandInContext(context.getSource(), executionContext ->
                ExecutionContext.queueInitialFunctionCall(executionContext, instantiated, context.getSource(), CommandResultCallback.EMPTY)
        );
        return 1;
    }

    private static double computeValue(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        double value;
        if (hasArgument(context, "source")) {
            Entity source = EntityArgument.getEntity(context, "source");
            if (!(source instanceof LivingEntity livingEntity)) {
                throw ERROR_NOT_LIVING.create(source.getDisplayName().getString());
            }

            Holder.Reference<Attribute> attribute = resolveAttribute(ResourceLocationArgument.getId(context, "source_attribute"));
            if (livingEntity.getAttribute(attribute) == null) {
                throw ERROR_ATTRIBUTE_MISSING.create(source.getDisplayName().getString(), attribute.unwrapKey().map(key -> key.location().toString()).orElse(attribute.value().toString()));
            }
            value = livingEntity.getAttributeValue(attribute);
        } else {
            ScoreHolder scoreHolder = ScoreHolderArgument.getName(context, "score_holder");
            Objective objective = ObjectiveArgument.getObjective(context, "objective");
            ReadOnlyScoreInfo score = context.getSource().getServer().getScoreboard().getPlayerScoreInfo(scoreHolder, objective);
            value = score == null ? 0.0D : score.value();
        }

        value *= DoubleArgumentType.getDouble(context, "scale");
        if (!Double.isFinite(value) || Math.abs(value) > MAX_ABSOLUTE_VALUE) {
            throw ERROR_BAD_VALUE.create(formatValue(value));
        }
        return value;
    }

    private static double computeAttributeModifierAmount(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (hasArgument(context, "value")) {
            return checkedFiniteBounded(DoubleArgumentType.getDouble(context, "value"));
        }
        return computeValue(context);
    }

    private static Holder.Reference<Attribute> resolveAttribute(ResourceLocation attributeId) throws CommandSyntaxException {
        return BuiltInRegistries.ATTRIBUTE.getHolder(attributeId)
                .orElseThrow(() -> ERROR_UNKNOWN_ATTRIBUTE.create(attributeId));
    }

    private static AttributeModifier.Operation parseOperation(String value) throws CommandSyntaxException {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "addition" -> AttributeModifier.Operation.ADD_VALUE;
            case "multiply_base" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "multiply_total" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> throw ERROR_BAD_OPERATION.create(value);
        };
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> attributeArgument(String name) {
        return Commands.argument(name, ResourceLocationArgument.id())
                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ATTRIBUTE.keySet(), builder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> operationArgument() {
        return Commands.argument("operation", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(OPERATION_SUGGESTIONS, builder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> durationArgument() {
        return Commands.argument("duration", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(DURATION_SUGGESTIONS, builder));
    }

    private static SetAngerScaledModifiers.ModifierDuration parseDuration(String value) throws CommandSyntaxException {
        String duration = value.toLowerCase(Locale.ROOT);
        if ("infinite".equals(duration)) {
            return SetAngerScaledModifiers.infiniteDuration();
        }
        if ("untildeath".equals(duration)) {
            return SetAngerScaledModifiers.untilDeathDuration();
        }

        long multiplier = 1L;
        String number = duration;
        if (duration.endsWith("t")) {
            number = duration.substring(0, duration.length() - 1);
        } else if (duration.endsWith("s")) {
            multiplier = 20L;
            number = duration.substring(0, duration.length() - 1);
        } else if (duration.endsWith("m")) {
            multiplier = 20L * 60L;
            number = duration.substring(0, duration.length() - 1);
        } else if (duration.endsWith("h")) {
            multiplier = 20L * 60L * 60L;
            number = duration.substring(0, duration.length() - 1);
        }

        try {
            long ticks = Long.parseLong(number) * multiplier;
            if (ticks <= 0L) {
                throw ERROR_BAD_DURATION.create(value);
            }
            return SetAngerScaledModifiers.timedDuration(ticks);
        } catch (NumberFormatException exception) {
            throw ERROR_BAD_DURATION.create(value);
        }
    }

    private static float checkedPositiveFloat(double value) throws CommandSyntaxException {
        if (value <= 0.0D || value > Float.MAX_VALUE) {
            throw ERROR_BAD_VALUE.create(formatValue(value));
        }
        return (float) value;
    }

    private static double checkedFiniteBounded(double value) throws CommandSyntaxException {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_ABSOLUTE_VALUE) {
            throw ERROR_BAD_VALUE.create(formatValue(value));
        }
        return value;
    }

    private static String formatValue(double value) {
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.6f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static boolean hasArgument(CommandContext<CommandSourceStack> context, String name) {
        try {
            context.getArgument(name, Object.class);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void sendDebugSuccess(CommandSourceStack source, java.util.function.Supplier<Component> message) {
        if (SetAngerConfig.debugCommandFeedback()) {
            source.sendSuccess(message, false);
        }
    }

    private enum DamageTypeMode {
        SAME,
        EXPLICIT
    }

    private enum HealthMode {
        ADD,
        MULTIPLY_CURRENT,
        MULTIPLY_TOTAL
    }

    private enum HungerMode {
        ADD,
        MULTIPLY_CURRENT,
        MULTIPLY_TOTAL
    }

}
