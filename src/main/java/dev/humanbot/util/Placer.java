package dev.humanbot.util;

import dev.humanbot.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Works out where to look to place a block at a position: some visible
 * face of a neighbouring solid block, like a player aiming at the side of
 * the block they're standing on while sneak-bridging.
 */
public final class Placer
{
	private static final Minecraft MC = Minecraft.getInstance();
	private static final double REACH = 4.5;
	
	public record Target(BlockPos support, Direction face, Vec3 point)
	{}
	
	private Placer()
	{}
	
	/** Best visible face to click so a block appears at {@code place}. */
	public static Target find(BlockPos place, double jitter)
	{
		if(!WorldUtil.isReplaceable(place))
			return null;
		Vec3 eyes = WorldUtil.eyes();
		float yaw = MC.player.getYRot(), pitch = MC.player.getXRot();
		
		Target best = null;
		double bestAngle = Double.MAX_VALUE;
		for(Direction d : Direction.values())
		{
			BlockPos support = place.relative(d);
			if(!WorldUtil.isSolid(support) || WorldUtil.isInteractable(support))
				continue;
			Direction face = d.getOpposite();
			
			// the face between the two blocks, nudged randomly within it
			double ox = support.getX() - place.getX();
			double oy = support.getY() - place.getY();
			double oz = support.getZ() - place.getZ();
			Vec3 c = WorldUtil.center(place);
			Vec3 point = new Vec3(
				c.x + ox * 0.5 + (ox == 0 ? Rand.uniform(-jitter, jitter) : 0),
				c.y + oy * 0.5 + (oy == 0 ? Rand.uniform(-jitter, jitter) : 0),
				c.z + oz * 0.5 + (oz == 0 ? Rand.uniform(-jitter, jitter) : 0));
			
			// eyes must be on the open side of that face
			double side = (eyes.x - point.x) * -ox + (eyes.y - point.y) * -oy
				+ (eyes.z - point.z) * -oz;
			if(side <= 0.01 || WorldUtil.dist(eyes, point) > REACH)
				continue;
			
			Vec3 into = point.add(ox * 0.05, oy * 0.05, oz * 0.05);
			BlockHitResult hit = WorldUtil.raycast(eyes, into);
			if(hit.getType() != HitResult.Type.BLOCK
				|| !hit.getBlockPos().equals(support) || hit.getDirection() != face)
				continue;
			
			double dx = into.x - eyes.x, dy = into.y - eyes.y,
				dz = into.z - eyes.z;
			float ty = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90f;
			float tp = (float)-Math.toDegrees(
				Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			double angle = Math.abs(Mth.wrapDegrees(ty - yaw))
				+ Math.abs(tp - pitch);
			if(angle < bestAngle)
			{
				bestAngle = angle;
				best = new Target(support, face, point);
			}
		}
		return best;
	}
	
	/** Is the crosshair right now on the face that places at the target? */
	public static boolean crosshairOn(Target t)
	{
		return MC.hitResult instanceof BlockHitResult bhr
			&& MC.hitResult.getType() == HitResult.Type.BLOCK
			&& bhr.getBlockPos().equals(t.support())
			&& bhr.getDirection() == t.face();
	}
	
	// ---------------------------------------------------------- throwaway
	
	public static boolean isThrowaway(ItemStack s)
	{
		if(s.isEmpty())
			return false;
		String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return BotConfig.get().throwawayBlocks.contains(id);
	}
	
	/** Hotbar slot with the most throwaway blocks, or -1. */
	public static int throwawaySlot()
	{
		int best = -1, bestCount = 0;
		for(int i = 0; i < 9; i++)
		{
			ItemStack s = MC.player.getInventory().getItem(i);
			if(isThrowaway(s) && s.getCount() > bestCount)
			{
				bestCount = s.getCount();
				best = i;
			}
		}
		return best;
	}
	
	public static int throwawayCount()
	{
		int n = 0;
		for(int i = 0; i < 9; i++)
		{
			ItemStack s = MC.player.getInventory().getItem(i);
			if(isThrowaway(s))
				n += s.getCount();
		}
		return n;
	}
	
	/** Hold a throwaway block. True once it's in hand. */
	public static boolean holdThrowaway()
	{
		if(isThrowaway(MC.player.getMainHandItem()))
			return true;
		int slot = throwawaySlot();
		if(slot < 0)
			return false;
		InputUtil.selectSlot(slot);
		return false; // takes effect next tick, like a real key press
	}
}
