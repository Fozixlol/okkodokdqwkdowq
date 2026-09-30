package dev.humanbot.modules;

import dev.humanbot.BotConfig;
import dev.humanbot.module.Process;
import dev.humanbot.path.PathExecutor;
import dev.humanbot.path.PathFinder;
import dev.humanbot.rotation.HumanRotator;
import dev.humanbot.util.Rand;
import dev.humanbot.util.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Follows a player around, keeping a comfortable distance (#follow). */
public final class Follow extends Process
{
	private final PathExecutor exec = new PathExecutor();
	private String targetName;
	private Player target;
	private BlockPos goalPos;
	private int lookTimer;
	
	public Follow()
	{
		super("Follow", "Follow a player around", 50);
	}
	
	/** Follow this player (null = whoever is closest). */
	public void setTarget(String name)
	{
		targetName = name;
	}
	
	@Override
	protected void onStart()
	{
		target = null;
		goalPos = null;
	}
	
	@Override
	public void tickActive(HumanRotator rot)
	{
		if(target == null || !target.isAlive())
		{
			target = find();
			if(target == null)
			{
				finish(targetName == null ? "No one around to follow"
					: targetName + " isn't nearby");
				return;
			}
		}
		
		double dist = WorldUtil.distTo(target);
		BlockPos tp = target.blockPosition();
		
		if(dist < 3.5)
		{
			// close enough: stand around and glance at them now and then
			exec.stop();
			goalPos = null;
			if(--lookTimer < 0)
				lookTimer = Rand.ticks(50, 25, 10, 140);
			if(lookTimer > 20)
			{
				Vec3 head = new Vec3(target.getX(), target.getEyeY(), target.getZ());
				rot.lookAt(head);
			}
			return;
		}
		
		// (re)plan when they've moved away from where we were heading
		if(!exec.isActive() || goalPos == null
			|| goalPos.distSqr(tp) > 9)
		{
			goalPos = tp;
			exec.start(PathFinder.near(tp, 2.5),
				BotConfig.get().allowBreakingForPaths);
		}
		PathExecutor.Status s = exec.tick(rot);
		if(s == PathExecutor.Status.FAILED)
			goalPos = null; // try again next tick from wherever we are
	}
	
	private Player find()
	{
		Player best = null;
		double bestD = Double.MAX_VALUE;
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(!(e instanceof Player pl) || pl == MC.player || !pl.isAlive())
				continue;
			if(targetName != null
				&& !pl.getPlainTextName().equalsIgnoreCase(targetName))
				continue;
			double d = WorldUtil.distTo(pl);
			if(d < bestD)
			{
				bestD = d;
				best = pl;
			}
		}
		return best;
	}
	
	@Override
	public void onLoseControl()
	{
		exec.stop();
		goalPos = null;
	}
	
	@Override
	public String status()
	{
		return target == null ? "looking for " + (targetName == null
			? "someone" : targetName)
			: "following " + target.getPlainTextName();
	}
}
