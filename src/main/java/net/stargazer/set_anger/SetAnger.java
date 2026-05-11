package net.stargazer.set_anger;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import org.slf4j.Logger;

@Mod(SetAnger.MOD_ID)
public final class SetAnger {
    public static final String MOD_ID = "set_anger";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SetAnger(IEventBus modEventBus, ModContainer modContainer) {
        SetAngerConfig.loadFromDisk();
        SetAngerSelectors.register();
        modEventBus.addListener(SetAnger::addAttackDamageToMobs);
    }

    private static void addAttackDamageToMobs(EntityAttributeModificationEvent event) {
        for (var type : event.getTypes()) {
            if (Mob.class.isAssignableFrom(type.getBaseClass()) && !event.has(type, Attributes.ATTACK_DAMAGE)) {
                event.add(type, Attributes.ATTACK_DAMAGE, 1.0D);
            }
        }
    }
}
