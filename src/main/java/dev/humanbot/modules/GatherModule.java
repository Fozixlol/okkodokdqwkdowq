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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Shared logic for "find a block, walk there, mine it, pick up the drops".
 * Miner and TreeChopper only differ in which blocks they want.
 */
public abstract class GatherModule extends Process
{
	private static final double REACH = 4.3;
	
	private final PathExecutor exec = new PathExecutor();
	private final Set<BlockPos> blacklist = new HashSet<>();
	private BlockPos target;
	private Vec3 aimOffset = Vec3.ZERO;
	private int scanCooldown;
	private int pause;
	private int collectTicks;
	private int mineTicks;
	private String state = "";
	private int goalCount;
	private int mined;
	
	protected GatherModule(String name, String desc, int priority)
	{
		super(name, desc, priority);
	}
	
	/** Stop after this many blocks (0 = keep going). */
	public void setGoalCount(int count)
	{
		goalCount = count;
	}
	
	@Override
	protected void onStart()
	{
		mined = 0;
		blacklist.clear();
		scanCooldown = 0;
	}
	
	protected abstract boolean wants(BlockState state, String id);
	
	@Override
	public boolean wantsControl()
	{
		return isEnabled();
	}
	
	@Override
	public void tickActive(HumanRotator rot)
	{
		if(pause > 0)
		{
			pause--;
			state = "thinking";
			return;
		}
		
		if(collectTicks > 0)
		{
			collect(rot);
			return;
		}
		
		if(target == null)
		{
			if(goalCount > 0 && mined >= goalCount)
			{
				finish("Done: " + mined + " blocks");
				return;
			}
			if(scanCooldown-- > 0)
			{
				state = "looking around";
				return;
			}
			scanCooldown = 40;
			target = scan();
			if(target == null)
			{
				state = "nothing nearby";
				idleTicks++;
				if(idleTicks > 20 * 60)
					finish("Nothing left to " + verb() + " nearby");
				return;
			}
			idleTicks = 0;
		}
		
		// give up on blocks that take forever to reach or break
		if(++mineTicks > 900)
		{
			blacklist.add(target);
			finishBlock();
			collectTicks = 0;
			return;
		}
		
		// already gone (we broke it, or someone else did)
		if(!wants(WorldUtil.state(target), WorldUtil.id(target)))
		{
			finishBlock();
			return;
		}
		
		double dist = WorldUtil.dist(WorldUtil.eyes(), WorldUtil.center(target));
		boolean digThrough = BotConfig.get().allowBreakingForPaths;
		if(dist <= REACH && !exec.isActive()
			&& (digThrough || WorldUtil.canSeeBlock(target)))
		{
			mine(rot);
			return;
		}
		
		// walk (and dig) until the block is within reach
		if(!exec.isActive())
			exec.start(PathFinder.near(target, 2.5),
				BotConfig.get().allowBreakingForPaths);
		
		PathExecutor.Status s = exec.tick(rot);
		state = s.name().toLowerCase();
		if(s == PathExecutor.Status.FAILED)
		{
			blacklist.add(target);
			target = null;
			exec.stop();
		}else if(s == PathExecutor.Status.ARRIVED
			|| dist <= REACH - 0.8 && (digThrough || WorldUtil.canSeeBlock(target)))
		{
			exec.stop();
			// got there but it's hidden and we may not dig: skip it
			if(!digThrough && !WorldUtil.canSeeBlock(target))
			{
				blacklist.add(target);
				target = null;
			}
		}
	}
	
	private void mine(HumanRotator rot)
	{
		state = "mining " + WorldUtil.id(target);
		InputUtil.releaseMovement();
		
		Vec3 c = WorldUtil.center(target);
		rot.lookAt(c.add(aimOffset.x, aimOffset.y, aimOffset.z));
		
		HitResult hr = MC.hitResult;
		boolean hitBlock = hr instanceof BlockHitResult
			&& hr.getType() == HitResult.Type.BLOCK;
		BlockPos looking = hitBlock ? ((BlockHitResult)hr).getBlockPos() : null;
		
		boolean ok = false;
		if(looking != null && !rot.isReacting() && rot.error() < 8)
		{
			// the block itself, or something in front of it we can dig through
			ok = looking.equals(target) || (BotConfig.get().allowBreakingForPaths
				&& WorldUtil.isSafeToBreak(looking));
		}
		InputUtil.mine(ok);
	}
	
	private int idleTicks;
	
	/** Skip blocks buried with no open side. */
	protected boolean onlyExposed()
	{
		return BotConfig.get().onlyExposedOres;
	}
	
	/** "mine", "chop"... for messages. */
	protected abstract String verb();
	
	private void finishBlock()
	{
		if(target != null && WorldUtil.isPassable(target))
			mined++;
		exec.stop();
		target = null;
		mineTicks = 0;
		scanCooldown = 0;
		aimOffset = new Vec3(Rand.uniform(-0.3, 0.3), Rand.uniform(-0.3, 0.3),
			Rand.uniform(-0.3, 0.3));
		collectTicks = 80;
		pause = BotConfig.get().microPauses ? Rand.ticks(5, 3, 1, 14) : 0;
	}
	
	/** Walk over nearby dropped items so they get picked up. */
	private void collect(HumanRotator rot)
	{
		collectTicks--;
		ItemEntity nearest = null;
		double best = 6;
		for(Entity e : MC.level.entitiesForRendering())
			if(e instanceof ItemEntity item && item.isAlive())
			{
				double d = WorldUtil.distTo(item);
				if(d < best)
				{
					best = d;
					nearest = item;
				}
			}
		if(nearest == null || best < 0.8)
		{
			if(nearest == null)
				collectTicks = 0;
			exec.stop();
			return;
		}
		state = "collecting";
		if(!exec.isActive())
			exec.start(PathFinder.near(nearest.blockPosition(), 0.5), false);
		PathExecutor.Status s = exec.tick(rot);
		if(s == PathExecutor.Status.FAILED)
			collectTicks = 0;
	}
	
	private BlockPos scan()
	{
		int r = BotConfig.get().scanRadius;
		int ry = Math.min(r, 16);
		BlockPos me = MC.player.blockPosition();
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for(int x = -r; x <= r; x++)
			for(int z = -r; z <= r; z++)
				for(int y = -ry; y <= ry; y++)
				{
					m.set(me.getX() + x, me.getY() + y, me.getZ() + z);
					BlockState s = MC.level.getBlockState(m);
					if(s.isAir())
						continue;
					String id = WorldUtil.id(s);
					if(!wants(s, id))
						continue;
					BlockPos pos = m.immutable();
					if(blacklist.contains(pos) || WorldUtil.lavaNearby(pos))
						continue;
					// a person only goes for ores they can actually see:
					// nothing buried inside solid stone
					if(onlyExposed() && !WorldUtil.isExposed(pos))
						continue;
					// prefer close, level, visible blocks
					double score = x * x + z * z + y * y * 2.5;
					if(WorldUtil.canSeeBlock(pos))
						score *= 0.5;
					if(score < bestScore)
					{
						bestScore = score;
						best = pos;
					}
				}
		return best;
	}
	
	@Override
	public void onLoseControl()
	{
		exec.stop();
	}
	
	@Override
	protected void onDisable()
	{
		onLoseControl();
		target = null;
		collectTicks = 0;
		pause = 0;
		blacklist.clear();
		goalCount = 0;
	}
	
	@Override
	public String status()
	{
		return state + (goalCount > 0 ? " \u00a78(" + mined + "/" + goalCount
			+ ")" : mined > 0 ? " \u00a78(" + mined + ")" : "");
	}
}
