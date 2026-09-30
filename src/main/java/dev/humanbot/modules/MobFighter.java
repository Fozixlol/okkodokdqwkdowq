package dev.humanbot.modules;

import java.util.Set;

import dev.humanbot.BotConfig;
import dev.humanbot.module.Module;
import dev.humanbot.rotation.HumanRotator;
import dev.humanbot.util.InputUtil;
import dev.humanbot.util.Rand;
import dev.humanbot.util.WorldUtil;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fights hostile mobs the way a decent player would: turns to face them,
 * closes distance, strafes a little, waits for the attack cooldown and
 * clicks. Never targets players or neutral mobs.
 */
public final class MobFighter extends Module
{
	private static final Set<String> IGNORE = Set.of("enderman",
		"zombified_piglin", "piglin", "piglin_brute", "shulker", "ghast");
	
	private Entity target;
	private double aimX, aimY, aimZ;
	private int aimTimer;
	private int attackDelay;
	private int strafeTimer;
	private int strafe; // -1 left, 0 none, 1 right
	private int backOff;
	private int weaponDelay = -1;
	
	public MobFighter()
	{
		super("MobFighter", "Fight hostile mobs that come close", false, 80);
	}
	
	@Override
	public boolean wantsControl()
	{
		if(target != null && isValid(target, BotConfig.get().fightRange + 4))
			return true;
		target = findTarget();
		return target != null;
	}
	
	@Override
	public void tickActive(HumanRotator rot)
	{
		LocalPlayer p = MC.player;
		Options o = InputUtil.opt();
		if(target == null)
			return;
		
		// pick a spot on the mob's body and keep it for a little while
		if(--aimTimer <= 0)
		{
			aimX = Rand.uniform(-0.25, 0.25);
			aimY = Rand.uniform(0.45, 0.85);
			aimZ = Rand.uniform(-0.25, 0.25);
			aimTimer = Rand.ticks(20, 8, 6, 40);
		}
		AABB box = target.getBoundingBox();
		Vec3 aim = new Vec3((box.minX + box.maxX) / 2 + aimX * box.getXsize(),
			box.minY + aimY * (box.maxY - box.minY),
			(box.minZ + box.maxZ) / 2 + aimZ * box.getZsize());
		rot.lookAt(aim);
		
		equipWeapon();
		
		double dist = WorldUtil.distTo(target);
		String type = typeId(target);
		
		// movement: close in, keep ~2.6 blocks, back off from creepers
		boolean forward = dist > 2.8 && backOff <= 0;
		boolean back = backOff > 0 || dist < 1.6
			|| (type.equals("creeper") && dist < 3.2);
		if(backOff > 0)
			backOff--;
		InputUtil.hold(o.keyUp, forward && rot.error() < 45);
		InputUtil.hold(o.keyDown, back);
		InputUtil.hold(o.keyJump, p.horizontalCollision && p.onGround());
		
		if(--strafeTimer <= 0)
		{
			strafe = Rand.chance(0.45) ? 0 : (Rand.chance(0.5) ? -1 : 1);
			strafeTimer = Rand.ticks(22, 10, 8, 50);
		}
		InputUtil.hold(o.keyLeft, strafe < 0 && dist < 4.5);
		InputUtil.hold(o.keyRight, strafe > 0 && dist < 4.5);
		
		// attack: only when the crosshair is really on it and cooldown is up
		if(attackDelay > 0)
			attackDelay--;
		boolean onTarget = MC.hitResult instanceof EntityHitResult ehr
			&& ehr.getEntity() == target;
		if(onTarget && attackDelay <= 0 && !rot.isReacting()
			&& p.getAttackStrengthScale(0) >= 0.93f)
		{
			InputUtil.leftClick(target);
			float sloppy = BotConfig.get().clickSloppiness;
			attackDelay = Rand.ticks(1 + sloppy * 4, 1 + sloppy * 2, 0, 12);
			if(type.equals("creeper"))
				backOff = Rand.ticks(14, 4, 8, 24);
		}
	}
	
	@Override
	public void onLoseControl()
	{
		target = null;
		weaponDelay = -1;
		InputUtil.releaseMovement();
	}
	
	@Override
	public String status()
	{
		return target == null ? "" : "fighting " + typeId(target);
	}
	
	private void equipWeapon()
	{
		if(weaponDelay < 0)
			weaponDelay = Rand.reactionTicks(BotConfig.get().reactionMs);
		if(weaponDelay > 0 && --weaponDelay > 0)
			return;
		weaponDelay = 0;
		int best = -1;
		int bestScore = score(MC.player.getInventory()
			.getItem(InputUtil.selectedSlot()));
		for(int i = 0; i < 9; i++)
		{
			int s = score(MC.player.getInventory().getItem(i));
			if(s > bestScore)
			{
				bestScore = s;
				best = i;
			}
		}
		if(best >= 0)
			InputUtil.selectSlot(best);
	}
	
	private static int score(ItemStack s)
	{
		if(s.isEmpty())
			return 0;
		String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		int tier = id.startsWith("netherite") ? 6 : id.startsWith("diamond") ? 5
			: id.startsWith("iron") ? 4 : id.startsWith("stone") ? 3
				: id.startsWith("golden") ? 2 : 1;
		if(id.endsWith("_sword"))
			return 20 + tier;
		if(id.endsWith("_axe"))
			return 10 + tier;
		return 0;
	}
	
	private Entity findTarget()
	{
		Entity best = null;
		double bestD = Double.MAX_VALUE;
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(!isValid(e, BotConfig.get().fightRange))
				continue;
			double d = WorldUtil.distTo(e);
			if(d < bestD)
			{
				bestD = d;
				best = e;
			}
		}
		return best;
	}
	
	private boolean isValid(Entity e, double range)
	{
		return e instanceof Enemy && e.isAlive()
			&& !IGNORE.contains(typeId(e)) && WorldUtil.distTo(e) <= range
			&& WorldUtil.canSee(WorldUtil.boxCenter(e));
	}
	
	private static String typeId(Entity e)
	{
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}
}
