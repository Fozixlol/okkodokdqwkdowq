package dev.humanbot.modules;

import dev.humanbot.HumanBot;
import dev.humanbot.Waypoints;
import dev.humanbot.module.Module;
import dev.humanbot.util.Rand;
import net.minecraft.client.gui.screens.DeathScreen;

/**
 * Clicks "Respawn" after a believable moment of staring at the screen, and
 * saves a "death" waypoint so you can /hb wp goto death.
 */
public final class AutoRespawn extends Module
{
	private int timer = -1;
	
	public AutoRespawn()
	{
		super("AutoRespawn", "Respawn after dying, remember where", true, -1);
	}
	
	@Override
	public void tickHelper()
	{
		if(!(MC.gui.screen() instanceof DeathScreen))
		{
			timer = -1;
			return;
		}
		if(timer < 0)
		{
			timer = Rand.ticks(45, 15, 20, 100);
			Waypoints.save("death", MC.player.blockPosition());
			HumanBot.chat("Saved a \"death\" waypoint where you died");
		}
		if(--timer == 0)
		{
			MC.player.respawn();
			MC.gui.setScreen(null);
		}
	}
}
