package dev.humanbot.modules;

import dev.humanbot.BotConfig;
import dev.humanbot.module.Module;
import dev.humanbot.rotation.HumanRotator;
import net.minecraft.world.entity.Entity;

/**
 * Automation: whenever a hostile mob comes close, fight it, then let the
 * current job carry on. Never targets players or neutral mobs.
 */
public final class MobFighter extends Module
{
	private final CombatAI combat = new CombatAI();
	private Entity target;

	public MobFighter()
	{
		super("MobFighter", "Fight hostile mobs that come close", false, 80);
	}

	@Override
	public boolean wantsControl()
	{
		double range = BotConfig.get().fightRange;
		if(combat.stillValid(target, range + 4))
			return true;
		target = combat.findTarget(range, true);
		return target != null;
	}

	@Override
	public void tickActive(HumanRotator rot)
	{
		if(target == null)
			return;
		if(!combat.tick(rot, target))
		{
			combat.ignore(target, 200);
			target = null;
			combat.reset();
		}
	}

	@Override
	public void onLoseControl()
	{
		target = null;
		combat.reset();
	}

	@Override
	public String status()
	{
		return target == null ? "" : "fighting " + CombatAI.typeId(target);
	}
}
