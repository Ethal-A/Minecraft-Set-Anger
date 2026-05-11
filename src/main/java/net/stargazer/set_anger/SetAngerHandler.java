package net.stargazer.set_anger;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@EventBusSubscriber(modid = SetAnger.MOD_ID)
public final class SetAngerHandler {
    private static final String DATA_TARGET = SetAnger.MOD_ID + ":target";
    private static final int SET_ANGER_ATTACK_PRIORITY = 2;

    private SetAngerHandler() {
    }

    public static void setAnger(Mob subject, LivingEntity target) {
        ensureAttackSupport(subject);
        subject.setTarget(target);
        subject.setAggressive(true);
        subject.setLastHurtByMob(target);
        subject.setPersistenceRequired();
        rememberTarget(subject, target.getUUID());

        if (subject instanceof NeutralMob neutralMob) {
            neutralMob.setPersistentAngerTarget(target.getUUID());
            neutralMob.startPersistentAngerTimer();
        }
    }

    public static void clearAnger(Mob subject) {
        subject.setTarget(null);
        subject.setAggressive(false);
        forgetTarget(subject);

        if (subject instanceof NeutralMob neutralMob) {
            neutralMob.stopBeingAngry();
        }
    }

    public static Optional<UUID> rememberedTarget(Mob subject) {
        CompoundTag data = subject.getPersistentData();
        return data.hasUUID(DATA_TARGET) ? Optional.of(data.getUUID(DATA_TARGET)) : Optional.empty();
    }

    private static void rememberTarget(Mob subject, UUID targetUuid) {
        subject.getPersistentData().putUUID(DATA_TARGET, targetUuid);
    }

    private static void forgetTarget(Mob subject) {
        subject.getPersistentData().remove(DATA_TARGET);
    }

    private static void ensureAttackSupport(Mob subject) {
        if (!(subject instanceof PathfinderMob pathfinderMob)) {
            return;
        }

        boolean hasMeleeGoal = pathfinderMob.goalSelector.getAvailableGoals()
                .stream()
                .anyMatch(goal -> goal.getGoal() instanceof MeleeAttackGoal);
        if (!hasMeleeGoal) {
            pathfinderMob.goalSelector.addGoal(SET_ANGER_ATTACK_PRIORITY, new SetAngerAttackGoal(pathfinderMob));
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof Mob mob)) {
            return;
        }

        if (rememberedTarget(mob).isPresent()) {
            ensureAttackSupport(mob);
        }
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        if (event.getEntity().level().isClientSide || !(event.getEntity() instanceof Mob mob)) {
            return;
        }

        rememberedTarget(mob).ifPresent(targetUuid -> maintainRememberedTarget(mob, targetUuid));
    }

    @SubscribeEvent
    public static void onShieldBlock(LivingShieldBlockEvent event) {
        if (!SetAngerConfig.updateAttackerOnBlockedDamage() || !event.getBlocked()) {
            return;
        }

        DamageSource source = event.getDamageSource();
        Entity attackerEntity = source.getEntity();
        if (attackerEntity instanceof LivingEntity attacker) {
            LivingEntity defender = event.getEntity();
            defender.setLastHurtByMob(attacker);
            attacker.setLastHurtMob(defender);
        }
    }

    private static void maintainRememberedTarget(Mob mob, UUID targetUuid) {
        if (!mob.isAlive() || mob.isNoAi()) {
            return;
        }

        Entity targetEntity = mob.level() instanceof net.minecraft.server.level.ServerLevel serverLevel ? serverLevel.getEntity(targetUuid) : null;
        if (!(targetEntity instanceof LivingEntity target) || !target.isAlive()) {
            clearAnger(mob);
            return;
        }

        ensureAttackSupport(mob);
        if (mob.getTarget() != target) {
            mob.setTarget(target);
        }
        mob.setAggressive(true);
    }
}
