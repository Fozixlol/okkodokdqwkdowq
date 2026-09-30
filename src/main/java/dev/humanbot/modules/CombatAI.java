package dev.humanbot.modules;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.humanbot.BotConfig;
import dev.humanbot.path.PathExecutor;
import dev.humanbot.path.PathFinder;
import dev.humanbot.rotation.HumanRotator;
import dev.humanbot.util.InputUtil;
import dev.humanbot.util.Rand;
import dev.humanbot.util.WorldUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The actual fighting, shared by {@link MobFighter} (defends you) and
 * {@link Fight} (goes hunting). It walks up to the mob (straight when it can
 * see it, with the pathfinder when there are walls, water or ledges in the
 * way), faces it, and hits it when the crosshair really is on it and the
 * attack cooldown is charged.
 */
final class CombatAI
{
	private static final Minecraft MC = Minecraft.getInstance();

	/** Mobs that are neutral, deadly or pointless to pick a fight with. */
	private static final Set<String> IGNORE = Set.of("enderman",
		"zombified_piglin", "piglin", "piglin_brute", "shulker", "ghast",
		"warden", "elder_guardian", "ender_dragon", "wither", "guardian");

	/** Attack range of the player, from the eyes to the hitbox. */
	private static final double REACH = 3.0;

	private final PathExecutor exec = new PathExecutor();
	private final Map<Integer, Long> ignored = new HashMap<>();
	private long now;

	private double aimX, aimY, aimZ;
	private int aimTimer;
	private int attackDelay;
	private int strafeTimer;
	private int strafe;
	private int backOff;
	private int weaponDelay = -1;
	private int pathMode;
	private int noProgress;
	private double bestReach = Double.MAX_VALUE;
	private net.minecraft.core.BlockPos pathGoal;

	// ------------------------------------------------------------ targets

	static String typeId(Entity e)
	{
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
	}

	static boolean isHostile(Entity e)
	{
		return e instanceof Enemy && e.isAlive() && e != MC.player
			&& !IGNORE.contains(typeId(e));
	}

	/** Stop considering this mob for a while (it can't be reached). */
	void ignore(Entity e, int ticks)
	{
		ignored.put(e.getId(), now + ticks);
	}

	boolean isIgnored(Entity e)
	{
		Long until = ignored.get(e.getId());
		return until != null && until > now;
	}

	/** Nearest hostile mob within range (optionally only ones in view). */
	Entity findTarget(double range, boolean needSight)
	{
		Entity best = null;
		double bestD = Double.MAX_VALUE;
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(!isHostile(e) || isIgnored(e))
				continue;
			double d = WorldUtil.distTo(e);
			if(d > range || d >= bestD)
				continue;
			if(needSight && !WorldUtil.canSee(WorldUtil.boxCenter(e)))
				continue;
			bestD = d;
			best = e;
		}
		return best;
	}

	boolean stillValid(Entity e, double range)
	{
		return e != null && isHostile(e) && !isIgnored(e)
			&& WorldUtil.distTo(e) <= range;
	}

	// ------------------------------------------------------------ fighting

	/** Distance from the eyes to the closest point of the mob's hitbox. */
	static double reachDist(Entity e)
	{
		Vec3 eyes = WorldUtil.eyes();
		AABB b = e.getBoundingBox();
		double x = Math.max(b.minX, Math.min(eyes.x, b.maxX));
		double y = Math.max(b.minY, Math.min(eyes.y, b.maxY));
		double z = Math.max(b.minZ, Math.min(eyes.z, b.maxZ));
		return WorldUtil.dist(eyes, new Vec3(x, y, z));
	}

	/** Is the player's actual line of sight on the mob, with nothing between? */
	private static boolean crosshairOn(Entity e)
	{
		LocalPlayer p = MC.player;
		Vec3 eyes = WorldUtil.eyes();
		Vec3 end = eyes.add(p.getViewVector(1.0f).scale(REACH));
		AABB box = e.getBoundingBox().inflate(0.1);
		Optional<Vec3> hit = box.clip(eyes, end);
		if(hit.isEmpty())
			return false;
		return WorldUtil.raycast(eyes, hit.get())
			.getType() == HitResult.Type.MISS;
	}

	/**
	 * One tick of fighting {@code target}.
	 *
	 * @return false if the mob can't be reached (the caller should drop it)
	 */
	boolean tick(HumanRotator rot, Entity target)
	{
		now++;
		LocalPlayer p = MC.player;
		Options o = InputUtil.opt();

		double reach = reachDist(target);
		String type = typeId(target);
		Vec3 center = WorldUtil.boxCenter(target);
		boolean sight = WorldUtil.canSee(center);
		double dy = target.getY() - p.getY();

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

		equipWeapon();

		// stuck trying to get close in a straight line? use the pathfinder
		if(reach < bestReach - 0.15)
		{
			bestReach = reach;
			noProgress = 0;
		}else if(reach > REACH - 0.3 && ++noProgress > 30 && pathMode <= 0)
			pathMode = 160;
		if(pathMode > 0)
			pathMode--;
		boolean usePath = pathMode > 0 || !sight || Math.abs(dy) > 2.2;

		// ---- far away or out of sight: walk there properly ----
		if(reach > REACH - 0.4 && usePath)
		{
			net.minecraft.core.BlockPos tp = target.blockPosition();
			if(!exec.isActive() || pathGoal == null
				|| pathGoal.distSqr(tp) > 4)
			{
				pathGoal = tp;
				exec.start(PathFinder.near(tp, 1.6),
					BotConfig.get().allowBreakingForPaths);
			}
			PathExecutor.Status s = exec.tick(rot);
			if(s == PathExecutor.Status.FAILED
				|| s == PathExecutor.Status.ARRIVED && reach > REACH + 1.5)
			{
				exec.stop();
				pathGoal = null;
				return false;
			}
			return true;
		}
		if(exec.isActive())
		{
			exec.stop();
			pathGoal = null;
		}

		// ---- close: face it, close in, dodge about and hit it ----
		rot.lookAt(aim);
		boolean creeper = type.equals("creeper");
		boolean facing = rot.error() < 40;
		boolean forward = reach > 2.3 && backOff <= 0;
		boolean back = backOff > 0 || reach < 0.9 || creeper && reach < 3.0;
		if(backOff > 0)
			backOff--;
		InputUtil.hold(o.keyUp, forward && facing);
		InputUtil.hold(o.keyDown, back);
		InputUtil.hold(o.keySprint, forward && facing && reach > 4.5
			&& p.getFoodData().getFoodLevel() > 6);
		InputUtil.hold(o.keyJump, p.horizontalCollision && p.onGround());

		if(--strafeTimer <= 0)
		{
			strafe = Rand.chance(0.45) ? 0 : (Rand.chance(0.5) ? -1 : 1);
			strafeTimer = Rand.ticks(22, 10, 8, 50);
		}
		boolean dodge = reach < 4.5 && !creeper;
		InputUtil.hold(o.keyLeft, strafe < 0 && dodge);
		InputUtil.hold(o.keyRight, strafe > 0 && dodge);

		// attack: only when the crosshair is really on it and the cooldown
		// is charged
		if(attackDelay > 0)
			attackDelay--;
		if(attackDelay <= 0 && !rot.isReacting() && reach <= REACH
			&& p.getAttackStrengthScale(0) >= 0.93f && !p.isUsingItem()
			&& crosshairOn(target))
		{
			InputUtil.attack(target);
			float sloppy = BotConfig.get().clickSloppiness;
			attackDelay = Rand.ticks(sloppy * 4, 1 + sloppy * 2, 0, 10);
			if(creeper)
				backOff = Rand.ticks(14, 4, 8, 24);
		}
		return true;
	}

	void reset()
	{
		exec.stop();
		pathGoal = null;
		weaponDelay = -1;
		pathMode = 0;
		noProgress = 0;
		bestReach = Double.MAX_VALUE;
		InputUtil.releaseMovement();
	}

	// ------------------------------------------------------------ weapon

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
		if(id.equals("mace") || id.equals("trident"))
			return 8;
		return 0;
	}
}
