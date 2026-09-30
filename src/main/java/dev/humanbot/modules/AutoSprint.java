package dev.humanbot.modules;

import dev.humanbot.module.Module;
import dev.humanbot.util.InputUtil;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;

/** Sprints whenever you (or the bot) walk forward and have the food for it. */
public final class AutoSprint extends Module
{
	private boolean holding;
	
	public AutoSprint()
	{
		super("AutoSprint", "Sprint while walking forward", true, -1);
	}
	
	@Override
	public void tickHelper()
	{
		LocalPlayer p = MC.player;
		Options o = InputUtil.opt();
		boolean want = o.keyUp.isDown() && !p.isShiftKeyDown()
			&& !p.isUsingItem() && p.getFoodData().getFoodLevel() > 6
			&& !p.horizontalCollision;
		if(want)
			InputUtil.hold(o.keySprint, true);
		else if(holding)
			InputUtil.hold(o.keySprint, false);
		holding = want;
	}
	
	@Override
	protected void onDisable()
	{
		if(holding)
			InputUtil.hold(InputUtil.opt().keySprint, false);
		holding = false;
	}
}
