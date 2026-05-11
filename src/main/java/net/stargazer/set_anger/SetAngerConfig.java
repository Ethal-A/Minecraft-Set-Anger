package net.stargazer.set_anger;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class SetAngerConfig {
    private static final String CONFIG_FILE = SetAnger.MOD_ID + ".toml";
    private static final String LEGACY_CONFIG_FILE = SetAnger.MOD_ID + "-server.toml";
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get()
            .resolve(CONFIG_FILE)
            .toAbsolutePath()
            .normalize();
    private static final Path LEGACY_CONFIG_PATH = FMLPaths.CONFIGDIR.get()
            .resolve(LEGACY_CONFIG_FILE)
            .toAbsolutePath()
            .normalize();
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> HOSTILE_ENTITIES;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> NEUTRAL_ENTITIES;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> PASSIVE_ENTITIES;
    private static final ModConfigSpec.BooleanValue UPDATE_ATTACKER_ON_BLOCKED_DAMAGE;
    private static final ModConfigSpec.BooleanValue ENABLE_EXECUTE_ON_VICTIM;
    private static volatile LoadedValues loadedValues;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Advancement and execute relation helpers.").push("relations");
        UPDATE_ATTACKER_ON_BLOCKED_DAMAGE = builder.comment(
                        "When true, blocked shield damage records the attacking entity as the defender's last attacker.",
                        "This helps vanilla 'execute on attacker' work from advancement reward functions even when damage was fully blocked."
                )
                .define("updateAttackerOnBlockedDamage", true);
        ENABLE_EXECUTE_ON_VICTIM = builder.comment(
                        "When true, Set Anger registers 'execute on victim'.",
                        "When false, the relation is still present in the command tree but resolves to no entity."
                )
                .define("enableExecuteOnVictim", true);
        builder.pop();

        builder.comment(
                "Entity disposition overrides for Set Anger selectors.",
                "Entries can be exact entity ids, such as \"minecraft:zombie\", or namespace wildcards, such as \"examplemod:*\".",
                "Config overrides datapack tags and built-in heuristics. If an entity matches multiple lists, hostile wins, then neutral, then passive."
        ).push("entity_categories");

        HOSTILE_ENTITIES = builder.comment("Entities that should match @e[hostile=true].")
                .defineListAllowEmpty("hostile", List.of(), SetAngerConfig::isEntityPattern);
        NEUTRAL_ENTITIES = builder.comment("Entities that should match @e[neutral=true].")
                .defineListAllowEmpty("neutral", List.of(), SetAngerConfig::isEntityPattern);
        PASSIVE_ENTITIES = builder.comment("Entities that should match @e[passive=true].")
                .defineListAllowEmpty("passive", List.of(), SetAngerConfig::isEntityPattern);

        builder.pop();
        SPEC = builder.build();
    }

    private SetAngerConfig() {
    }

    public static SetAngerSelectors.Disposition classify(EntityType<?> type) {
        LoadedValues values = loadedValues;
        if (matchesAny(type, values != null ? values.hostile() : getList(HOSTILE_ENTITIES))) {
            return SetAngerSelectors.Disposition.HOSTILE;
        }
        if (matchesAny(type, values != null ? values.neutral() : getList(NEUTRAL_ENTITIES))) {
            return SetAngerSelectors.Disposition.NEUTRAL;
        }
        if (matchesAny(type, values != null ? values.passive() : getList(PASSIVE_ENTITIES))) {
            return SetAngerSelectors.Disposition.PASSIVE;
        }

        return SetAngerSelectors.Disposition.NONE;
    }

    public static boolean updateAttackerOnBlockedDamage() {
        LoadedValues values = loadedValues;
        return values != null ? values.updateAttackerOnBlockedDamage() : getBoolean(UPDATE_ATTACKER_ON_BLOCKED_DAMAGE, true);
    }

    public static boolean enableExecuteOnVictim() {
        LoadedValues values = loadedValues;
        return values != null ? values.enableExecuteOnVictim() : getBoolean(ENABLE_EXECUTE_ON_VICTIM, true);
    }

    public static Path reload() {
        return loadFromDisk(true);
    }

    public static Path loadFromDisk() {
        return loadFromDisk(false);
    }

    private static Path loadFromDisk(boolean throwOnFailure) {
        migrateLegacyConfig();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create Set Anger config directory " + CONFIG_PATH.getParent(), exception);
        }

        try (CommentedFileConfig config = CommentedFileConfig.builder(CONFIG_PATH)
                .sync()
                .preserveInsertionOrder()
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .build()) {
            config.load();
            SPEC.correct(config);
            config.save();
            loadedValues = LoadedValues.from(config, CONFIG_PATH);
            clearCaches();
            return CONFIG_PATH;
        } catch (RuntimeException exception) {
            loadedValues = LoadedValues.defaults(CONFIG_PATH);
            clearCaches();
            if (throwOnFailure) {
                throw exception;
            }
            SetAnger.LOGGER.error("Failed to load Set Anger config from {}; using defaults", CONFIG_PATH, exception);
        } catch (Exception exception) {
            loadedValues = LoadedValues.defaults(CONFIG_PATH);
            clearCaches();
            if (throwOnFailure) {
                throw new IllegalStateException("Could not load Set Anger config from " + CONFIG_PATH, exception);
            }
            SetAnger.LOGGER.error("Failed to load Set Anger config from {}; using defaults", CONFIG_PATH, exception);
        }
        return CONFIG_PATH;
    }

    private static boolean matchesAny(EntityType<?> type, List<? extends String> patterns) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        for (String pattern : patterns) {
            if (matches(id, pattern)) {
                return true;
            }
        }

        return false;
    }

    private static boolean matches(ResourceLocation id, String pattern) {
        String normalized = normalize(pattern);
        if (normalized.endsWith(":*")) {
            String namespace = normalized.substring(0, normalized.length() - 2);
            return id.getNamespace().equals(namespace);
        }

        ResourceLocation exact = ResourceLocation.tryParse(normalized);
        return id.equals(exact);
    }

    private static boolean isEntityPattern(Object value) {
        if (!(value instanceof String string)) {
            return false;
        }

        String normalized = normalize(string);
        if (normalized.isEmpty()) {
            return false;
        }

        if (normalized.endsWith(":*")) {
            String namespace = normalized.substring(0, normalized.length() - 2);
            return !namespace.isEmpty() && ResourceLocation.isValidNamespace(namespace);
        }

        return ResourceLocation.tryParse(normalized) != null;
    }

    private static List<? extends String> getList(ModConfigSpec.ConfigValue<List<? extends String>> value) {
        if (!SPEC.isLoaded()) {
            return List.of();
        }

        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return List.of();
        }
    }

    private static boolean getBoolean(ModConfigSpec.BooleanValue value, boolean fallback) {
        if (!SPEC.isLoaded()) {
            return fallback;
        }

        try {
            return value.get();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    private static void clearCaches() {
        HOSTILE_ENTITIES.clearCache();
        NEUTRAL_ENTITIES.clearCache();
        PASSIVE_ENTITIES.clearCache();
        UPDATE_ATTACKER_ON_BLOCKED_DAMAGE.clearCache();
        ENABLE_EXECUTE_ON_VICTIM.clearCache();
        SPEC.afterReload();
    }

    private static void migrateLegacyConfig() {
        if (Files.isRegularFile(CONFIG_PATH) || !Files.isRegularFile(LEGACY_CONFIG_PATH)) {
            return;
        }

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.copy(LEGACY_CONFIG_PATH, CONFIG_PATH);
            SetAnger.LOGGER.info("Copied legacy Set Anger config from {} to {}", LEGACY_CONFIG_PATH, CONFIG_PATH);
        } catch (IOException exception) {
            SetAnger.LOGGER.warn("Could not copy legacy Set Anger config from {} to {}", LEGACY_CONFIG_PATH, CONFIG_PATH, exception);
        }
    }

    private static List<String> readList(CommentedConfig config, String path) {
        Optional<Object> value = config.getOptional(path);
        if (value.isEmpty() || !(value.get() instanceof List<?> rawValues)) {
            return List.of();
        }

        List<String> values = new ArrayList<>(rawValues.size());
        for (Object rawValue : rawValues) {
            if (rawValue instanceof String stringValue) {
                values.add(stringValue);
            }
        }
        return values;
    }

    private static boolean readBoolean(CommentedConfig config, String path, boolean fallback) {
        Optional<Object> value = config.getOptional(path);
        return value.filter(Boolean.class::isInstance).map(Boolean.class::cast).orElse(fallback);
    }

    private record LoadedValues(
            List<String> hostile,
            List<String> neutral,
            List<String> passive,
            boolean updateAttackerOnBlockedDamage,
            boolean enableExecuteOnVictim,
            Path path
    ) {
        private static LoadedValues from(CommentedConfig config, Path path) {
            return new LoadedValues(
                    sanitizePatterns(readList(config, "entity_categories.hostile")),
                    sanitizePatterns(readList(config, "entity_categories.neutral")),
                    sanitizePatterns(readList(config, "entity_categories.passive")),
                    readBoolean(config, "relations.updateAttackerOnBlockedDamage", true),
                    readBoolean(config, "relations.enableExecuteOnVictim", true),
                    path
            );
        }

        private static LoadedValues defaults(Path path) {
            return new LoadedValues(List.of(), List.of(), List.of(), true, true, path);
        }

        private static List<String> sanitizePatterns(List<String> patterns) {
            List<String> sanitized = new ArrayList<>(patterns.size());
            for (String pattern : patterns) {
                if (isEntityPattern(pattern)) {
                    sanitized.add(normalize(pattern));
                }
            }
            return List.copyOf(sanitized);
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
