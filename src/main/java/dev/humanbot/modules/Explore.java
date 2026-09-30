package dev.humanbot.modules;

import java.util.HashSet;
import java.util.Set;

import dev.humanbot.BotConfig;
import dev.humanbot.module.Process;
import dev.humanbot.path.PathExecutor;
import dev.humanbot.path.PathFinder;
import dev.humanbot.rotation.HumanRotator;
import net.minecraft.core.BlockPos;

/**
 * Wanders outward in a spiral of chunks, visiting each one once
 * (Baritone's #explore).
 */
public final class Explore extends Process
{
	private final PathExecutor exec = new PathExecutor();
	private final Set<Long> visited = new HashSet<>();
	private int originX, originZ;
	private int step;
	private BlockPos goal;
	private int fails;
	
	public Explore()
	{
		super("Explore", "Wander out in a spiral, seeing new chunks", 30);
	}
	
	@Override
	protected void onStart()
	{
		BlockPos me = MC.player.blockPosition();
		originX = me.getX() >> 4;
		originZ = me.getZ() >> 4;
		step = 0;
		visited.clear();
		goal = null;
		fails = 0;
	}
	
	@Override
	public void tickActive(HumanRotator rot)
	{
		BlockPos me = MC.player.blockPosition();
		visited.add(key(me.getX() >> 4, me.getZ() >> 4));
		
		if(goal == null)
		{
			goal = nextChunk();
			exec.start(PathFinder.at(goal, true),
				BotConfig.get().allowBreakingForPaths);
		}
		
		PathExecutor.Status s = exec.tick(rot);
		if(s == PathExecutor.Status.ARRIVED)
		{
			goal = null;
			fails = 0;
		}else if(s == PathExecutor.Status.FAILED)
		{
			visited.add(key(goal.getX() >> 4, goal.getZ() >> 4));
			goal = null;
			if(++fails > 25)
				finish("Explore: can't get anywhere new from here");
		}
	}
	
	/** Walk a square spiral of chunks, skipping ones we've been in. */
	private BlockPos nextChunk()
	{
		while(true)
		{
			int[] c = spiral(step++);
			int cx = originX + c[0] * 2, cz = originZ + c[1] * 2;
			if(visited.contains(key(cx, cz)))
				continue;
			return new BlockPos(cx * 16 + 8, MC.player.blockPosition().getY(),
				cz * 16 + 8);
		}
	}
	
	/** n-th point of a square spiral around (0,0). */
	private static int[] spiral(int n)
	{
		if(n == 0)
			return new int[]{0, 0};
		int k = (int)Math.ceil((Math.sqrt(n + 1) - 1) / 2);
		int t = 2 * k + 1;
		int m = t * t;
		t -= 1;
		if(n >= m - t)
			return new int[]{k - (m - n), -k};
		m -= t;
		if(n >= m - t)
			return new int[]{-k, -k + (m - n)};
		m -= t;
		if(n >= m - t)
			return new int[]{-k + (m - n), k};
		return new int[]{k, k - (m - n - t)};
	}
	
	private static long key(int cx, int cz)
	{
		return ((long)cx << 32) ^ (cz & 0xFFFFFFFFL);
	}
	
	@Override
	public void onLoseControl()
	{
		exec.stop();
		goal = null;
	}
	
	@Override
	public String status()
	{
		return "exploring §8(" + visited.size() + " chunks seen)";
	}
}
