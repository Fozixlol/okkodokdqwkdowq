package dev.humanbot.modules;

import java.util.HashSet;
import java.util.Set;

import dev.humanbot.BotConfig;
import dev.humanbot.module.Process;
import dev.humanbot.path.PathExecutor;
import dev.humanbot.path.PathFinder;
import dev.humanbot.rotation.HumanRotator;
import dev.humanbot.util.InputUtil;
import dev.humanbot.util.Rand;
import dev.humanbot.util.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Walks to the nearest block of a type and opens it if it's something you
 * can use (crafting table, chest, furnace...). Baritone's #goto &lt;block&gt;.
 */
public final class GetTo extends Process
{
	private final PathExecutor exec = new PathExecutor();
	private final Set<BlockPos> unreachable = new HashSet<>();
	private String blockId;
	private BlockPos target;
	private int openDelay;
	private int tries;
	
	public GetTo()
	{
		super("GetTo", "Go to the nearest block of a type and open it", 55);
	}
	
	public void setBlock(String id)
	{
		blockId = id.replace("minecraft:", "");
	}
	
	public String block()
	{
		return blockId;
	}
	
	@Override
	protected void onStart()
	{
		target = null;
		unreachable.clear();
		tries = 0;
	}
	
	@Override
	public void tickActive(HumanRotator rot)
	{
		if(blockId == null)
		{
			finish(null);
			return;
		}
		if(target == null || !WorldUtil.id(target).equals(blockId))
		{
			int r = Math.max(32, BotConfig.get().scanRadius * 2);
			target = WorldUtil.findNearest(
				(pos, s, id) -> id.equals(blockId) && !unreachable.contains(pos),
				r, 32);
			if(target == null)
			{
				finish("No " + blockId + " found nearby");
				return;
			}
			exec.stop();
		}
		
		double dist = WorldUtil.dist(WorldUtil.eyes(), WorldUtil.center(target));
		if(dist > 4.2)
		{
			if(!exec.isActive())
				exec.start(PathFinder.near(target, 2.5),
					BotConfig.get().allowBreakingForPaths);
			PathExecutor.Status s = exec.tick(rot);
			if(s == PathExecutor.Status.FAILED)
			{
				unreachable.add(target);
				target = null;
				if(++tries > 5)
					finish("Couldn't reach any " + blockId);
			}
			return;
		}
		
		// arrived: look at it, and open it if it's usable
		exec.stop();
		rot.lookAt(WorldUtil.center(target));
		if(!WorldUtil.isInteractable(target))
		{
			finish("Reached " + blockId);
			return;
		}
		boolean onIt = MC.hitResult instanceof BlockHitResult bhr
			&& MC.hitResult.getType() == HitResult.Type.BLOCK
			&& bhr.getBlockPos().equals(target);
		if(!onIt || rot.isReacting())
		{
			openDelay = Rand.ticks(4, 2, 1, 10);
			return;
		}
		if(--openDelay <= 0)
		{
			InputUtil.useOnLookedAtBlock();
			finish(null);
		}
	}
	
	@Override
	public void onLoseControl()
	{
		exec.stop();
	}
	
	@Override
	public String status()
	{
		return "going to " + blockId + (target != null
			? " §8(" + exec.status().name().toLowerCase() + ")" : "");
	}
}
