package net.stargazer.set_anger;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.damagesource.DamageSource;

public final class SetAngerAttackGoal extends MeleeAttackGoal {
    private static final float FALLBACK_ATTACK_DAMAGE = 1.0F;

    public SetAngerAttackGoal(PathfinderMob mob) {
        super(mob, 1.15D, true);
    }

    @Override
    protected void checkAndPerformAttack(LivingEntity target) {
        if (!this.canPerformAttack(target)) {
            return;
        }

        this.resetAttackCooldown();
        this.mob.swing(InteractionHand.MAIN_HAND);
        if (this.mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) {
            this.mob.doHurtTarget(target);
            return;
        }

        DamageSource source = this.mob.damageSources().mobAttack(this.mob);
        if (target.hurt(source, FALLBACK_ATTACK_DAMAGE)) {
            this.mob.setLastHurtMob(target);
        }
    }
}
