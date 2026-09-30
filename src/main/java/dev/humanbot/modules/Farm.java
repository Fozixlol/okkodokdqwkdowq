package dev.humanbot.modules;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dev.humanbot.BotConfig;
import dev.humanbot.module.Process;
import dev.humanbot.path.PathExecutor;
import dev.humanbot.path.PathFinder;
import dev.humanbot.rotation.HumanRotator;
import dev.humanbot.util.InputUtil;
import dev.humanbot.util.Placer;
import dev.humanbot.util.Rand;
import dev.humanbot.util.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Harvests fully grown crops around where you started it and replants them
 * with seeds from your hotbar (Baritone's #farm). Also breaks pumpkins and
 * melons and picks up what drops.
 */
public final class Farm extends Process
{
	/** crop block -> item to replant it with */
	private static final Map<String, String> SEEDS = Map.of("wheat",
		"wheat_seeds", "carrots", "carrot", "potatoes", "potato", "beetroots",
		"beetroot_seeds", "nether_wart", "nether_wart");

	private enum Job
	{
		HARVEST, PLANT
	}

	private final PathExecutor exec = new PathExecutor();
	private final Set<BlockPos> skip = new HashSet<>();
	private BlockPos center;
	private BlockPos target;
	private Job job;
	private String replantSeed;
	private int scanCooldown;
	private int timeout;
	private int collectTicks;
	private int pause;
	private int harvested;
	private String state = "";

	public Farm()
	{
		super("Farm", "Harvest grown crops and replant them", 45);
	}

	@Override
	protected void onStart()
	{
		center = MC.player.blockPosition();
		target = null;
		skip.clear();
		harvested = 0;
	}

	@Override
	public void tickActive(HumanRotator rot)
	{
		if(pause > 0)
		{
			pause--;
			return;
		}
		if(collectTicks > 0 && collect(rot))
			return;

		if(target == null)
		{
			if(scanCooldown-- > 0)
			{
				state = "waiting for crops";
				return;
			}
			scanCooldown = 20;
			pickJob();
			if(target == null)
				return;
			timeout = 0;
		}

		if(++timeout > 600)
		{
			skip.add(target);
			target = null;
			exec.stop();
			return;
		}

		// still valid?
		if(job == Job.HARVEST && !isHarvestable(WorldUtil.state(target),
			WorldUtil.id(target)))
		{
			target = null;
			return;
		}
		if(job == Job.PLANT && (!WorldUtil.state(target).isAir()
			|| seedSlot(replantSeed) < 0))
		{
			target = null;
			return;
		}

		double dist = WorldUtil.dist(WorldUtil.eyes(), WorldUtil.center(target));
		if(dist > 4.0)
		{
			state = (job == Job.HARVEST ? "walking to crop" : "going to replant");
			if(!exec.isActive())
				exec.start(PathFinder.near(target, 2.2), false);
			if(exec.tick(rot) == PathExecutor.Status.FAILED)
			{
				skip.add(target);
				target = null;
			}
			return;
		}
		exec.stop();
		InputUtil.releaseMovement();

		if(job == Job.HARVEST)
			harvest(rot);
		else
			plant(rot);
	}

	private void harvest(HumanRotator rot)
	{
		state = "harvesting " + WorldUtil.id(target);
		rot.lookAt(WorldUtil.center(target).add(0, -0.25, 0));
		boolean onIt = MC.hitResult instanceof BlockHitResult bhr
			&& MC.hitResult.getType() == HitResult.Type.BLOCK
			&& bhr.getBlockPos().equals(target);
		InputUtil.mine(onIt && !rot.isReacting());

		if(WorldUtil.state(target).isAir())
		{
			harvested++;
			String seed = SEEDS.get(lastCrop);
			collectTicks = 60;
			pause = BotConfig.get().microPauses ? Rand.ticks(4, 2, 1, 10) : 0;
			// replant right away if we have the seed
			if(seed != null && seedSlot(seed) >= 0
				&& isSoil(WorldUtil.id(target.below())))
			{
				job = Job.PLANT;
				replantSeed = seed;
				collectTicks = 0;
			}else
				target = null;
		}
	}

	private void plant(HumanRotator rot)
	{
		state = "planting " + replantSeed;
		int slot = seedSlot(replantSeed);
		if(slot < 0)
		{
			target = null;
			return;
		}
		if(InputUtil.selectedSlot() != slot)
		{
			InputUtil.selectSlot(slot);
			return;
		}
		Placer.Target t = Placer.find(target, 0.3);
		if(t == null || t.face() != Direction.UP)
		{
			rot.lookAt(WorldUtil.center(target.below()).add(0, 0.5, 0));
			return;
		}
		rot.lookAt(t.point());
		if(Placer.crosshairOn(t) && !rot.isReacting()
			&& InputUtil.useOnLookedAtBlock())
		{
			target = null;
			collectTicks = 60;
			pause = BotConfig.get().microPauses ? Rand.ticks(4, 2, 1, 10) : 0;
		}
	}

	private String lastCrop = "";

	private void pickJob()
	{
		int r = BotConfig.get().farmRadius;
		// harvesting first, then empty soil we can plant
		BlockPos crop = WorldUtil.findNearest((pos, s, id) -> !skip
			.contains(pos) && inRange(pos, r) && isHarvestable(s, id), r + 8, 8);
		if(crop != null)
		{
			target = crop;
			job = Job.HARVEST;
			lastCrop = WorldUtil.id(crop);
			return;
		}
		BlockPos soil = WorldUtil.findNearest((pos, s, id) -> {
			if(!isSoil(id) || skip.contains(pos.above()) || !inRange(pos, r))
				return false;
			if(!WorldUtil.state(pos.above()).isAir())
				return false;
			String seed = id.equals("soul_sand") ? "nether_wart" : anySeed();
			return seed != null && seedSlot(seed) >= 0;
		}, r + 8, 8);
		if(soil != null)
		{
			target = soil.above();
			job = Job.PLANT;
			replantSeed = WorldUtil.id(soil).equals("soul_sand") ? "nether_wart"
				: anySeed();
			return;
		}
		target = null;
	}

	private boolean inRange(BlockPos pos, int r)
	{
		return Math.abs(pos.getX() - center.getX()) <= r
			&& Math.abs(pos.getZ() - center.getZ()) <= r
			&& Math.abs(pos.getY() - center.getY()) <= 6;
	}

	private static boolean isSoil(String id)
	{
		return id.equals("farmland") || id.equals("soul_sand");
	}

	private static boolean isHarvestable(BlockState s, String id)
	{
		if(s.getBlock() instanceof CropBlock crop)
			return crop.isMaxAge(s);
		if(id.equals("nether_wart"))
			return s.getValue(NetherWartBlock.AGE) >= 3;
		return id.equals("pumpkin") || id.equals("melon");
	}

	/** Some seed we have in the hotbar, for planting empty farmland. */
	private String anySeed()
	{
		for(String seed : new String[]{"wheat_seeds", "carrot", "potato",
			"beetroot_seeds"})
			if(seedSlot(seed) >= 0)
				return seed;
		return null;
	}

	private int seedSlot(String itemId)
	{
		if(itemId == null)
			return -1;
		for(int i = 0; i < 9; i++)
		{
			ItemStack s = MC.player.getInventory().getItem(i);
			if(!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem())
				.getPath().equals(itemId))
				return i;
		}
		return -1;
	}

	/** Walk over nearby drops. Returns true while busy collecting. */
	private boolean collect(HumanRotator rot)
	{
		collectTicks--;
		ItemEntity nearest = null;
		double best = 5;
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
			collectTicks = 0;
			exec.stop();
			return false;
		}
		state = "picking up";
		if(!exec.isActive())
			exec.start(PathFinder.near(nearest.blockPosition(), 0.5), false);
		if(exec.tick(rot) == PathExecutor.Status.FAILED)
			collectTicks = 0;
		return true;
	}

	@Override
	public void onLoseControl()
	{
		exec.stop();
	}

	@Override
	public String status()
	{
		return state + " §8(" + harvested + " harvested)";
	}
}
