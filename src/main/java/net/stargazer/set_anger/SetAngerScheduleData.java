package net.stargazer.set_anger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

public final class SetAngerScheduleData extends SavedData {
    private static final String DATA_NAME = SetAnger.MOD_ID + "_schedules";
    private static final String TAG_TASKS = "tasks";
    private static final String TAG_ENTITY = "entity";
    private static final String TAG_DIMENSION = "dimension";
    private static final String TAG_DUE_TICK = "due_tick";
    private static final String TAG_COMMAND = "command";
    private static final String TAG_KEEP_ON_DEATH = "keep_on_death";
    private static final String TAG_PLAYER = "player";

    private static final Factory<SetAngerScheduleData> FACTORY = new Factory<>(
            SetAngerScheduleData::new,
            SetAngerScheduleData::load
    );

    private final List<ScheduledCommand> tasks = new ArrayList<>();

    public static SetAngerScheduleData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public int schedule(MinecraftServer server, Entity entity, int delayTicks, boolean replace, boolean keepOnDeath, String command) {
        if (replace) {
            clear(entity.getUUID());
        }

        boolean keep = keepOnDeath && entity instanceof ServerPlayer;
        tasks.add(new ScheduledCommand(
                entity.getUUID(),
                entity.level().dimension().location(),
                server.overworld().getGameTime() + delayTicks,
                command,
                keep,
                entity instanceof ServerPlayer
        ));
        setDirty();
        return 1;
    }

    public int clear(UUID entityId) {
        int removed = 0;
        for (Iterator<ScheduledCommand> iterator = tasks.iterator(); iterator.hasNext(); ) {
            if (iterator.next().entityId().equals(entityId)) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    public void clearOnDeath(Entity entity) {
        UUID entityId = entity.getUUID();
        boolean changed = tasks.removeIf(task -> task.entityId().equals(entityId) && !(task.player() && task.keepOnDeath()));
        if (changed) {
            setDirty();
        }
    }

    public int tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        int executed = 0;
        boolean changed = false;
        for (Iterator<ScheduledCommand> iterator = tasks.iterator(); iterator.hasNext(); ) {
            ScheduledCommand task = iterator.next();
            if (task.dueTick() > now) {
                continue;
            }

            iterator.remove();
            changed = true;
            Entity entity = findExecutionEntity(server, task);
            if (entity != null && entity.isAlive()) {
                execute(server, entity, task.command());
                executed++;
            }
        }

        if (changed) {
            setDirty();
        }
        return executed;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ScheduledCommand task : tasks) {
            CompoundTag taskTag = new CompoundTag();
            taskTag.putUUID(TAG_ENTITY, task.entityId());
            taskTag.putString(TAG_DIMENSION, task.dimension().toString());
            taskTag.putLong(TAG_DUE_TICK, task.dueTick());
            taskTag.putString(TAG_COMMAND, task.command());
            taskTag.putBoolean(TAG_KEEP_ON_DEATH, task.keepOnDeath());
            taskTag.putBoolean(TAG_PLAYER, task.player());
            list.add(taskTag);
        }
        tag.put(TAG_TASKS, list);
        return tag;
    }

    private static SetAngerScheduleData load(CompoundTag tag, HolderLookup.Provider registries) {
        SetAngerScheduleData data = new SetAngerScheduleData();
        ListTag list = tag.getList(TAG_TASKS, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag taskTag = list.getCompound(index);
            if (!taskTag.hasUUID(TAG_ENTITY)) {
                continue;
            }

            ResourceLocation dimension = ResourceLocation.tryParse(taskTag.getString(TAG_DIMENSION));
            if (dimension == null) {
                continue;
            }

            data.tasks.add(new ScheduledCommand(
                    taskTag.getUUID(TAG_ENTITY),
                    dimension,
                    taskTag.getLong(TAG_DUE_TICK),
                    taskTag.getString(TAG_COMMAND),
                    taskTag.getBoolean(TAG_KEEP_ON_DEATH),
                    taskTag.getBoolean(TAG_PLAYER)
            ));
        }
        return data;
    }

    private static Entity findExecutionEntity(MinecraftServer server, ScheduledCommand task) {
        if (task.player()) {
            return server.getPlayerList().getPlayer(task.entityId());
        }

        ResourceKey<Level> dimensionKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, task.dimension());
        ServerLevel level = server.getLevel(dimensionKey);
        return level == null ? null : level.getEntity(task.entityId());
    }

    private static void execute(MinecraftServer server, Entity entity, String command) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }

        var source = server.createCommandSourceStack()
                .withPermission(2)
                .withEntity(entity)
                .withLevel(level)
                .withPosition(entity.position())
                .withRotation(entity.getRotationVector());
        if (!SetAngerConfig.debugCommandFeedback()) {
            source = source.withSuppressedOutput();
        }

        try {
            server.getCommands().performPrefixedCommand(source, command);
        } catch (RuntimeException exception) {
            SetAnger.LOGGER.warn("Skipped failed scheduleas command for {}: {}", entity.getDisplayName().getString(), command, exception);
        }
    }

    private record ScheduledCommand(
            UUID entityId,
            ResourceLocation dimension,
            long dueTick,
            String command,
            boolean keepOnDeath,
            boolean player
    ) {
    }
}
