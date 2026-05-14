package net.stargazer.set_anger;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@EventBusSubscriber(modid = SetAnger.MOD_ID)
public final class SetAngerScaledModifiers {
    private static final String DATA_MODIFIERS = SetAnger.MOD_ID + ":scaled_attribute_modifiers";
    private static final String TAG_ATTRIBUTE = "attribute";
    private static final String TAG_MODIFIER = "modifier";
    private static final String TAG_EXPIRES_AT = "expires_at";
    private static final long EXPIRES_UNTIL_DEATH = -1L;

    private SetAngerScaledModifiers() {
    }

    public static void apply(
            LivingEntity target,
            Holder<Attribute> attribute,
            ResourceLocation modifierId,
            double amount,
            AttributeModifier.Operation operation,
            ModifierDuration duration
    ) {
        AttributeInstance instance = target.getAttribute(attribute);
        if (instance == null) {
            throw new IllegalArgumentException(target.getDisplayName().getString() + " does not have attribute " + attributeName(attribute) + ".");
        }

        instance.removeModifier(modifierId);
        instance.addOrReplacePermanentModifier(new AttributeModifier(modifierId, amount, operation));

        ResourceLocation attributeId = attributeName(attribute);
        removeRecord(target, attributeId, modifierId);
        if (duration.kind() == ModifierDuration.Kind.TIMED) {
            addRecord(target, attributeId, modifierId, target.level().getGameTime() + duration.ticks());
        } else if (duration.kind() == ModifierDuration.Kind.UNTIL_DEATH) {
            addRecord(target, attributeId, modifierId, EXPIRES_UNTIL_DEATH);
        }
    }

    public static ModifierDuration infiniteDuration() {
        return new ModifierDuration(ModifierDuration.Kind.INFINITE, 0L);
    }

    public static ModifierDuration untilDeathDuration() {
        return new ModifierDuration(ModifierDuration.Kind.UNTIL_DEATH, 0L);
    }

    public static ModifierDuration timedDuration(long ticks) {
        return new ModifierDuration(ModifierDuration.Kind.TIMED, ticks);
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        if (event.getEntity().level().isClientSide || !(event.getEntity() instanceof LivingEntity livingEntity)) {
            return;
        }

        removeExpiredModifiers(livingEntity);
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide) {
            removeUntilDeathModifiers(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || event.getEntity().level().isClientSide) {
            return;
        }

        removeUntilDeathModifiers(event.getOriginal());
        removeUntilDeathModifiers(event.getEntity());
        copyTimedRecords(event.getOriginal(), event.getEntity());
    }

    private static void removeExpiredModifiers(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(DATA_MODIFIERS, Tag.TAG_LIST)) {
            return;
        }

        long gameTime = entity.level().getGameTime();
        ListTag records = data.getList(DATA_MODIFIERS, Tag.TAG_COMPOUND);
        for (int index = records.size() - 1; index >= 0; index--) {
            CompoundTag record = records.getCompound(index);
            long expiresAt = record.getLong(TAG_EXPIRES_AT);
            if (expiresAt >= 0L && gameTime >= expiresAt) {
                removeModifier(entity, record);
                records.remove(index);
            }
        }

        if (records.isEmpty()) {
            data.remove(DATA_MODIFIERS);
        }
    }

    private static void removeUntilDeathModifiers(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(DATA_MODIFIERS, Tag.TAG_LIST)) {
            return;
        }

        ListTag records = data.getList(DATA_MODIFIERS, Tag.TAG_COMPOUND);
        for (int index = records.size() - 1; index >= 0; index--) {
            CompoundTag record = records.getCompound(index);
            if (record.getLong(TAG_EXPIRES_AT) == EXPIRES_UNTIL_DEATH) {
                removeModifier(entity, record);
                records.remove(index);
            }
        }

        if (records.isEmpty()) {
            data.remove(DATA_MODIFIERS);
        }
    }

    private static void copyTimedRecords(LivingEntity original, LivingEntity clone) {
        CompoundTag originalData = original.getPersistentData();
        if (!originalData.contains(DATA_MODIFIERS, Tag.TAG_LIST)) {
            return;
        }

        CompoundTag cloneData = clone.getPersistentData();
        ListTag clonedRecords = cloneData.contains(DATA_MODIFIERS, Tag.TAG_LIST)
                ? cloneData.getList(DATA_MODIFIERS, Tag.TAG_COMPOUND)
                : new ListTag();
        ListTag originalRecords = originalData.getList(DATA_MODIFIERS, Tag.TAG_COMPOUND);
        for (int index = 0; index < originalRecords.size(); index++) {
            CompoundTag record = originalRecords.getCompound(index);
            if (record.getLong(TAG_EXPIRES_AT) != EXPIRES_UNTIL_DEATH && !containsRecord(clonedRecords, record)) {
                clonedRecords.add(record.copy());
            }
        }

        if (!clonedRecords.isEmpty()) {
            cloneData.put(DATA_MODIFIERS, clonedRecords);
        }
    }

    private static boolean containsRecord(ListTag records, CompoundTag candidate) {
        String candidateAttribute = candidate.getString(TAG_ATTRIBUTE);
        String candidateModifier = candidate.getString(TAG_MODIFIER);
        for (int index = 0; index < records.size(); index++) {
            CompoundTag record = records.getCompound(index);
            if (candidateAttribute.equals(record.getString(TAG_ATTRIBUTE)) && candidateModifier.equals(record.getString(TAG_MODIFIER))) {
                return true;
            }
        }
        return false;
    }

    private static void removeRecord(LivingEntity entity, ResourceLocation attributeId, ResourceLocation modifierId) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(DATA_MODIFIERS, Tag.TAG_LIST)) {
            return;
        }

        ListTag records = data.getList(DATA_MODIFIERS, Tag.TAG_COMPOUND);
        for (int index = records.size() - 1; index >= 0; index--) {
            CompoundTag record = records.getCompound(index);
            if (attributeId.toString().equals(record.getString(TAG_ATTRIBUTE)) && modifierId.toString().equals(record.getString(TAG_MODIFIER))) {
                records.remove(index);
            }
        }

        if (records.isEmpty()) {
            data.remove(DATA_MODIFIERS);
        }
    }

    private static void addRecord(LivingEntity entity, ResourceLocation attributeId, ResourceLocation modifierId, long expiresAt) {
        CompoundTag data = entity.getPersistentData();
        ListTag records = data.contains(DATA_MODIFIERS, Tag.TAG_LIST)
                ? data.getList(DATA_MODIFIERS, Tag.TAG_COMPOUND)
                : new ListTag();

        CompoundTag record = new CompoundTag();
        record.putString(TAG_ATTRIBUTE, attributeId.toString());
        record.putString(TAG_MODIFIER, modifierId.toString());
        record.putLong(TAG_EXPIRES_AT, expiresAt);
        records.add(record);
        data.put(DATA_MODIFIERS, records);
    }

    private static void removeModifier(LivingEntity entity, CompoundTag record) {
        try {
            Optional<Holder.Reference<Attribute>> attribute = BuiltInRegistries.ATTRIBUTE.getHolder(ResourceLocation.parse(record.getString(TAG_ATTRIBUTE)));
            if (attribute.isEmpty()) {
                return;
            }

            AttributeInstance instance = entity.getAttribute(attribute.get());
            if (instance != null) {
                instance.removeModifier(ResourceLocation.parse(record.getString(TAG_MODIFIER)));
            }
        } catch (RuntimeException exception) {
            SetAnger.LOGGER.warn("Ignored invalid Set Anger scaled modifier record on {}", entity.getDisplayName().getString(), exception);
        }
    }

    private static ResourceLocation attributeName(Holder<Attribute> attribute) {
        ResourceLocation name = BuiltInRegistries.ATTRIBUTE.getKey(attribute.value());
        return name == null ? ResourceLocation.fromNamespaceAndPath(SetAnger.MOD_ID, "unknown_attribute") : name;
    }

    public record ModifierDuration(Kind kind, long ticks) {
        public enum Kind {
            INFINITE,
            TIMED,
            UNTIL_DEATH
        }
    }
}
