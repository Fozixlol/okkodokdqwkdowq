package dev.humanbot.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Block and world queries used by the pathfinder and the modules. */
public final class WorldUtil
{
	private static final Minecraft MC = Minecraft.getInstance();

	private WorldUtil()
	{}

	public static BlockState state(BlockPos pos)
	{
		return MC.level.getBlockState(pos);
	}

	/** Registry path of the block, e.g. "diamond_ore". */
	public static String id(BlockState state)
	{
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
	}

	public static String id(BlockPos pos)
	{
		return id(state(pos));
	}

	public static boolean isSolid(BlockPos pos)
	{
		ClientLevel level = MC.level;
		return !level.getBlockState(pos).getCollisionShape(level, pos)
			.isEmpty();
	}

	/** Things a sensible player never walks into or stands on. */
	public static boolean isDangerous(BlockPos pos)
	{
		String id = id(pos);
		return id.equals("lava") || id.equals("fire") || id.equals("soul_fire")
			|| id.equals("magma_block") || id.equals("cactus")
			|| id.equals("sweet_berry_bush") || id.equals("powder_snow")
			|| id.equals("campfire") || id.equals("soul_campfire")
			|| id.equals("wither_rose") || id.equals("cobweb");
	}

	public static boolean isWater(BlockPos pos)
	{
		return id(pos).equals("water");
	}

	/** A cell the player's body can occupy right now. */
	public static boolean isPassable(BlockPos pos)
	{
		return !isSolid(pos) && !isDangerous(pos);
	}

	public static boolean isStandable(BlockPos below)
	{
		return (isSolid(below) || isWater(below)) && !isDangerous(below);
	}

	public static boolean lavaNearby(BlockPos pos)
	{
		for(BlockPos p : new BlockPos[]{pos.above(), pos.below(), pos.north(),
			pos.south(), pos.east(), pos.west()})
			if(id(p).equals("lava"))
				return true;
		return false;
	}

	/** Can this block be mined without doing something stupid? */
	public static boolean isSafeToBreak(BlockPos pos)
	{
		BlockState s = state(pos);
		if(s.isAir() || !s.getFluidState().isEmpty())
			return false;
		String id = id(s);
		if(id.equals("bedrock") || id.contains("chest") || id.contains("spawner")
			|| id.contains("portal") || id.equals("barrier"))
			return false;
		if(s.getDestroyProgress(MC.player, MC.level, pos) <= 0)
			return false;
		// never open a hole into lava or let gravel fall onto our head
		if(lavaNearby(pos))
			return false;
		String above = id(pos.above());
		if(above.equals("lava") || above.equals("water"))
			return false;
		return true;
	}

	/** Roughly how many ticks it takes to break this block right now. */
	public static double breakTicks(BlockPos pos)
	{
		float p = state(pos).getDestroyProgress(MC.player, MC.level, pos);
		return p <= 0 ? 10_000 : Math.min(10_000, 1.0 / p);
	}

	public static Vec3 eyes()
	{
		LocalPlayer p = MC.player;
		return new Vec3(p.getX(), p.getEyeY(), p.getZ());
	}

	public static BlockHitResult raycast(Vec3 from, Vec3 to)
	{
		return MC.level.clip(new ClipContext(from, to,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
	}

	/** True if nothing blocks the view between the eyes and the point. */
	public static boolean canSee(Vec3 to)
	{
		return raycast(eyes(), to).getType() == HitResult.Type.MISS;
	}

	/** Can we actually see some part of this block (not what's in front)? */
	public static boolean canSeeBlock(BlockPos pos)
	{
		Vec3 e = eyes();
		Vec3 c = center(pos);
		// try the centre and the face centres pointing our way
		double[][] offs = {{0, 0, 0}, {Math.signum(e.x - c.x) * 0.45, 0, 0},
			{0, Math.signum(e.y - c.y) * 0.45, 0},
			{0, 0, Math.signum(e.z - c.z) * 0.45}};
		for(double[] o : offs)
		{
			BlockHitResult hit = raycast(e, c.add(o[0], o[1], o[2]));
			if(hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(pos))
				return true;
		}
		return false;
	}
	
	/** Touches air or water on at least one side, i.e. not buried. */
	public static boolean isExposed(BlockPos pos)
	{
		for(BlockPos n : new BlockPos[]{pos.above(), pos.below(), pos.north(),
			pos.south(), pos.east(), pos.west()})
			if(!isSolid(n))
				return true;
		return false;
	}
	
	private static final String[] INTERACTABLE = {"chest", "barrel",
		"shulker", "furnace", "smoker", "crafting", "table", "anvil", "door",
		"gate", "button", "lever", "bed", "hopper", "dispenser", "dropper",
		"note_block", "bell", "jukebox", "lectern", "loom", "stonecutter",
		"grindstone", "brewing", "beacon", "repeater", "comparator", "sign",
		"cake", "anchor", "crafter", "composter", "daylight", "vault",
		"trial_spawner", "decorated_pot", "chiseled_bookshelf", "cauldron"};
	
	/** Right-clicking this block would open or toggle something. */
	public static boolean isInteractable(BlockPos pos)
	{
		String id = id(pos);
		for(String s : INTERACTABLE)
			if(id.contains(s))
				return true;
		return false;
	}
	
	/** Air, water, grass... anything a placed block can go into. */
	public static boolean isReplaceable(BlockPos pos)
	{
		BlockState s = state(pos);
		if(s.isAir())
			return true;
		if(!s.getCollisionShape(MC.level, pos).isEmpty())
			return false;
		String id = id(s);
		return id.equals("water") || id.equals("short_grass")
			|| id.equals("tall_grass") || id.equals("fern")
			|| id.equals("large_fern") || id.equals("snow")
			|| id.equals("dead_bush") || id.equals("vine")
			|| id.equals("seagrass") || id.equals("glow_lichen");
	}
	
	public interface BlockFilter
	{
		boolean test(BlockPos pos, BlockState state, String id);
	}
	
	/** Nearest matching block around the player (weighted: level first). */
	public static BlockPos findNearest(BlockFilter filter, int r, int ry)
	{
		BlockPos me = MC.player.blockPosition();
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for(int x = -r; x <= r; x++)
			for(int z = -r; z <= r; z++)
				for(int y = -ry; y <= ry; y++)
				{
					double score = x * x + z * z + y * y * 2.5;
					if(score >= bestScore)
						continue;
					m.set(me.getX() + x, me.getY() + y, me.getZ() + z);
					BlockState s = MC.level.getBlockState(m);
					if(s.isAir())
						continue;
					BlockPos pos = m.immutable();
					if(filter.test(pos, s, id(s)))
					{
						bestScore = score;
						best = pos;
					}
				}
		return best;
	}
	
	public static double dist(Vec3 a, Vec3 b)
	{
		return Math.sqrt(a.distanceToSqr(b));
	}
	
	/** Distance from the player to an entity. */
	public static double distTo(net.minecraft.world.entity.Entity e)
	{
		return dist(MC.player.position(), e.position());
	}
	
	/** Centre of an entity's hitbox. */
	public static Vec3 boxCenter(net.minecraft.world.entity.Entity e)
	{
		net.minecraft.world.phys.AABB b = e.getBoundingBox();
		return new Vec3((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2,
			(b.minZ + b.maxZ) / 2);
	}
	
	public static Vec3 center(BlockPos pos)
	{
		return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}
}
