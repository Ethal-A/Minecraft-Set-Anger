package net.stargazer.set_anger;

import net.minecraft.commands.arguments.selector.options.EntitySelectorOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.player.Player;

public final class SetAngerSelectors {
    private static final TagKey<EntityType<?>> HOSTILE_TAG = entityTypeTag("hostile");
    private static final TagKey<EntityType<?>> NEUTRAL_TAG = entityTypeTag("neutral");
    private static final TagKey<EntityType<?>> PASSIVE_TAG = entityTypeTag("passive");
    private static boolean registered;

    private SetAngerSelectors() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }

        EntitySelectorOptions.bootStrap();
        registerDispositionOption("hostile", Disposition.HOSTILE, "Filter mobs that normally target players");
        registerDispositionOption("neutral", Disposition.NEUTRAL, "Filter mobs that retaliate or become angry when provoked");
        registerDispositionOption("passive", Disposition.PASSIVE, "Filter mobs that are normally non-hostile");
        registerBooleanOption(
                "angryatplayer",
                entity -> entity instanceof Mob mob
                        && mob.getTarget() instanceof Player player
                        && player.isAlive()
                        && !player.isSpectator()
                        && !player.isCreative(),
                "Filter mobs currently targeting a non-creative, non-spectator player"
        );
        registered = true;
    }

    private static void registerDispositionOption(String name, Disposition disposition, String description) {
        EntitySelectorOptions.register(
                name,
                parser -> {
                    parser.setSuggestions((builder, consumer) -> {
                        builder.suggest("true");
                        builder.suggest("false");
                        return builder.buildFuture();
                    });
                    boolean expected = parser.getReader().readBoolean();
                    parser.addPredicate(entity -> (classify(entity) == disposition) == expected);
                },
                parser -> true,
                Component.literal(description)
        );
    }

    private static void registerBooleanOption(String name, java.util.function.Predicate<Entity> predicate, String description) {
        EntitySelectorOptions.register(
                name,
                parser -> {
                    parser.setSuggestions((builder, consumer) -> {
                        builder.suggest("true");
                        builder.suggest("false");
                        return builder.buildFuture();
                    });
                    boolean expected = parser.getReader().readBoolean();
                    parser.addPredicate(entity -> predicate.test(entity) == expected);
                },
                parser -> true,
                Component.literal(description)
        );
    }

    private static Disposition classify(Entity entity) {
        EntityType<?> type = entity.getType();
        Disposition configured = SetAngerConfig.classify(type);
        if (configured != Disposition.NONE) {
            return configured;
        }
        if (type.is(HOSTILE_TAG)) {
            return Disposition.HOSTILE;
        }
        if (type.is(NEUTRAL_TAG)) {
            return Disposition.NEUTRAL;
        }
        if (type.is(PASSIVE_TAG)) {
            return Disposition.PASSIVE;
        }
        if (entity instanceof NeutralMob) {
            return Disposition.NEUTRAL;
        }
        if (entity instanceof Mob && type.getCategory() == MobCategory.MONSTER) {
            return Disposition.HOSTILE;
        }
        if (entity instanceof Mob) {
            return Disposition.PASSIVE;
        }

        return Disposition.NONE;
    }

    private static TagKey<EntityType<?>> entityTypeTag(String name) {
        return TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(SetAnger.MOD_ID, name));
    }

    enum Disposition {
        HOSTILE,
        NEUTRAL,
        PASSIVE,
        NONE
    }
}
