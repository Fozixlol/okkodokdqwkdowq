package dev.humanbot.modules;

import dev.humanbot.BotConfig;
import dev.humanbot.module.Process;
import dev.humanbot.rotation.HumanRotator;
import net.minecraft.world.entity.Entity;

/** A job: hunt down hostile mobs nearby and kill them, one after another. */
public final class Fight extends Process
{
	private final CombatAI combat = new CombatAI();
	private Entity target;
	private int idle;
	private int kills;

	public Fight()
	{
		super("Fight", "Hunt and kill hostile mobs nearby", 42);
	}

	@Override
	protected void onStart()
	{
		target = null;
		idle = 0;
		kills = 0;
	}

	@Override
	public void tickActive(HumanRotator rot)
	{
		double range = Math.max(16, BotConfig.get().scanRadius);
		if(target != null && !target.isAlive())
		{
			kills++;
			target = null;
			combat.reset();
		}
		if(target == null || !combat.stillValid(target, range + 8))
		{
			target = combat.findTarget(range, false);
			if(target == null)
			{
				combat.reset();
				if(++idle > 20 * 90)
					finish("No more mobs around (" + kills + " killed)");
				return;
			}
		}
		idle = 0;
		if(!combat.tick(rot, target))
		{
			combat.ignore(target, 400);
			target = null;
			combat.reset();
		}
	}

	@Override
	public void onLoseControl()
	{
		combat.reset();
	}

	@Override
	protected void onDisable()
	{
		target = null;
		combat.reset();
	}

	@Override
	public String status()
	{
		return (target == null ? "looking for mobs" : "hunting "
			+ CombatAI.typeId(target)) + (kills > 0 ? " §8(" + kills
				+ " killed)" : "");
	}
}
