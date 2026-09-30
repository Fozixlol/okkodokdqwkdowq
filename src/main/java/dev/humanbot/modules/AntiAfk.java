package dev.humanbot.modules;

import dev.humanbot.module.Module;
import dev.humanbot.rotation.HumanRotator;
import dev.humanbot.util.InputUtil;
import dev.humanbot.util.Rand;
import net.minecraft.client.Options;

/**
 * When nothing else is going on, fidget like a person at the keyboard:
 * glance around, take a couple of steps, hop, scroll the hotbar.
 */
public final class AntiAfk extends Module
{
	private enum Action
	{
		NONE, LOOK, STEP, JUMP, SCROLL, SNEAK
	}
	
	private Action action = Action.NONE;
	private int wait = Rand.ticks(100, 40, 40, 240);
	private int duration;
	private float yaw, pitch;
	private int scrollBack = -1;
	
	public AntiAfk()
	{
		super("AntiAFK", "Fidget around when idle", false, 5);
	}
	
	@Override
	public boolean wantsControl()
	{
		return true; // lowest priority, only runs when nothing else wants to
	}
	
	@Override
	public void tickActive(HumanRotator rot)
	{
		Options o = InputUtil.opt();
		
		if(action == Action.NONE)
		{
			if(scrollBack >= 0 && Rand.chance(0.05))
			{
				InputUtil.selectSlot(scrollBack);
				scrollBack = -1;
			}
			if(--wait > 0)
				return;
			pick();
		}
		
		duration--;
		switch(action)
		{
			case LOOK -> rot.look(yaw, pitch);
			case STEP ->
			{
				rot.look(yaw, pitch);
				InputUtil.hold(o.keyUp, rot.error() < 20);
			}
			case JUMP -> InputUtil.hold(o.keyJump, duration > 1);
			case SNEAK -> InputUtil.hold(o.keyShift, true);
			default ->
			{}
		}
		
		if(duration <= 0 || MC.player.horizontalCollision
			&& action == Action.STEP)
		{
			InputUtil.releaseMovement();
			action = Action.NONE;
			wait = Rand.ticks(110, 50, 30, 320);
		}
	}
	
	private void pick()
	{
		double r = Rand.uniform(0, 1);
		float curYaw = MC.player.getYRot();
		if(r < 0.5)
		{
			action = Action.LOOK;
			yaw = curYaw + (float)Rand.gaussian(0, 35);
			pitch = (float)Rand.gaussian(10, 18, -45, 60);
			duration = Rand.ticks(25, 10, 8, 60);
		}else if(r < 0.7)
		{
			action = Action.STEP;
			yaw = curYaw + (float)Rand.gaussian(0, 60);
			pitch = (float)Rand.gaussian(12, 8, -10, 35);
			duration = Rand.ticks(14, 6, 5, 30);
		}else if(r < 0.82)
		{
			action = Action.JUMP;
			duration = 3;
		}else if(r < 0.92)
		{
			action = Action.SCROLL;
			int cur = InputUtil.selectedSlot();
			scrollBack = cur;
			InputUtil.selectSlot((cur + (Rand.chance(0.5) ? 1 : 8)) % 9);
			duration = 1;
		}else
		{
			action = Action.SNEAK;
			duration = Rand.ticks(6, 3, 2, 14);
		}
	}
	
	@Override
	public void onLoseControl()
	{
		action = Action.NONE;
		wait = Rand.ticks(100, 40, 40, 240);
		InputUtil.releaseMovement();
	}
}
