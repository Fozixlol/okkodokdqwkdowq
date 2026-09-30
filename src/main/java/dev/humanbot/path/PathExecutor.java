package dev.humanbot.path;

import java.util.List;

import dev.humanbot.BotConfig;
import dev.humanbot.HumanBot;
import dev.humanbot.rotation.HumanRotator;
import dev.humanbot.util.InputUtil;
import dev.humanbot.util.Placer;
import dev.humanbot.util.Rand;
import dev.humanbot.util.WorldUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Follows a path the way a player would: holds W, turns with the
 * {@link HumanRotator}, jumps at steps, digs what's in the way, sneaks to
 * the edge to bridge, looks down to tower up, sprint-jumps small gaps.
 * <p>
 * Like Baritone, when a path is only partial it plans the next segment
 * while still walking, so long trips don't stop and start.
 */
public final class PathExecutor
{
	public enum Status
	{
		IDLE, THINKING, WALKING, MINING, PLACING, ARRIVED, FAILED
	}

	private static final Minecraft MC = Minecraft.getInstance();

	private PathFinder.Goal goal;
	private boolean allowBreak;
	private PathFinder finder;
	private PathFinder nextFinder;
	private List<PathFinder.Node> path;
	private boolean pathReachesGoal;
	private int index;
	private int thinkTimer;
	private int plansNoProgress;
	private double bestGoalH = Double.MAX_VALUE;
	private int groundWait;
	private int stuckTicks;
	private double bestDist = Double.MAX_VALUE;
	private Status status = Status.IDLE;

	private float walkPitch = (float)Rand.uniform(8, 22);
	private int glanceTimer = Rand.ticks(120, 40, 40, 300);
	private float glanceOffset;
	private int glanceLeft;
	private int breakPause;
	private int placeCooldown;
	private int jumpTimer;
	private double placeJitter = 0.3;
	private double jumpAt;

	public void start(PathFinder.Goal goal, boolean allowBreak)
	{
		this.goal = goal;
		this.allowBreak = allowBreak;
		this.plansNoProgress = 0;
		this.bestGoalH = Double.MAX_VALUE;
		plan();
	}

	public boolean isActive()
	{
		return goal != null;
	}

	public Status status()
	{
		return status;
	}

	public PathFinder.Goal goal()
	{
		return goal;
	}

	public int nodesLeft()
	{
		return path == null ? 0 : Math.max(0, path.size() - index);
	}

	public Move currentMove()
	{
		return path == null || index >= path.size() ? null
			: path.get(index).move;
	}

	public void stop()
	{
		goal = null;
		finder = null;
		nextFinder = null;
		path = null;
		status = Status.IDLE;
		InputUtil.releaseMovement();
	}

	private PathFinder newFinder(BlockPos from, boolean prefetch)
	{
		int budget = BotConfig.get().allowPlace ? Placer.throwawayCount() : 0;
		// like Baritone: think a bit, then go with the best partial path
		// found so far and keep planning the next stretch while walking
		return new PathFinder(from, goal, allowBreak, budget, 150_000,
			prefetch ? 5000 : 2500);
	}

	private void plan()
	{
		BlockPos feet = WorldUtil.feet(MC.player);
		double h = goal.heuristic(feet);
		if(h < bestGoalH - 1.0)
		{
			bestGoalH = h;
			plansNoProgress = 0;
		}else if(++plansNoProgress > 8)
		{
			// planning again and again without getting any closer
			stop();
			status = Status.FAILED;
			return;
		}
		finder = newFinder(feet, false);
		nextFinder = null;
		path = null;
		index = 1;
		stuckTicks = 0;
		groundWait = 0;
		bestDist = Double.MAX_VALUE;
		status = Status.THINKING;
		// people take a moment to decide where to go
		thinkTimer = BotConfig.get().microPauses ? Rand.ticks(6, 3, 2, 14) : 0;
	}

	public Status tick(HumanRotator rot)
	{
		if(goal == null)
			return status = Status.IDLE;

		LocalPlayer p = MC.player;
		boolean grounded = p.onGround() || p.isInWater();

		if(goal.reached(WorldUtil.feet(p)) && grounded)
		{
			InputUtil.releaseMovement();
			goal = null;
			return status = Status.ARRIVED;
		}

		// ---- still thinking ----
		if(finder != null)
		{
			InputUtil.releaseMovement();
			// can't plan from mid-air (knocked off a ledge, mid-jump)
			if(!grounded && groundWait++ < 60)
				return status = Status.THINKING;
			finder.stepFor(8);
			if(thinkTimer > 0)
				thinkTimer--;
			if(!finder.isDone() || thinkTimer > 0)
				return status = Status.THINKING;

			path = finder.getResult();
			pathReachesGoal = finder.reachesGoal();
			finder = null;
			index = 1;
			if(path == null || path.size() < 2)
			{
				goal = null;
				return status = Status.FAILED;
			}
		}

		// ---- did we already get further than the plan thinks? ----
		syncProgress(grounded);

		// ---- plan the next segment in the background ----
		if(!pathReachesGoal && nextFinder == null && nodesLeft() <= 40)
			nextFinder = newFinder(path.get(path.size() - 1).pos, true);
		if(nextFinder != null && !nextFinder.isDone())
			nextFinder.stepFor(4);

		if(index >= path.size())
		{
			if(nextFinder != null && nextFinder.isDone()
				&& nextFinder.getResult() != null
				&& nextFinder.getResult().size() >= 2
				&& WorldUtil.feet(p).distSqr(
					nextFinder.getResult().get(0).pos) <= 2.25)
			{
				path = nextFinder.getResult();
				pathReachesGoal = nextFinder.reachesGoal();
				nextFinder = null;
				index = 1;
			}else if(nextFinder != null && !nextFinder.isDone()
				&& WorldUtil.feet(p).distSqr(path.get(path.size() - 1).pos)
					<= 2.25)
			{
				// nearly there: wait for the next stretch instead of
				// throwing the half-done search away
				InputUtil.stopWalking();
				nextFinder.stepFor(10);
				return status = Status.THINKING;
			}else
			{
				plan();
				return status;
			}
		}

		PathFinder.Node node = path.get(index);
		PathFinder.Node prev = path.get(index - 1);

		// ---- dig out whatever is in the way ----
		for(BlockPos b : node.toBreak)
		{
			if(WorldUtil.isPassable(b))
				continue;
			if(!WorldUtil.isSafeToBreak(b))
			{
				plan(); // world changed (lava appeared...), think again
				return status;
			}
			InputUtil.releaseMovement();
			mineBlock(rot, b);
			stuckTicks = 0;
			return status = Status.MINING;
		}
		if(breakPause > 0)
		{
			breakPause--;
			return status = Status.MINING;
		}

		return switch(node.move)
		{
			case PILLAR -> pillar(rot, prev, node);
			case BRIDGE -> bridge(rot, prev, node);
			case PARKOUR -> parkour(rot, prev, node);
			default -> walk(rot, prev, node);
		};
	}

	// ------------------------------------------------------------ walking

	private Status walk(HumanRotator rot, PathFinder.Node prev,
		PathFinder.Node node)
	{
		LocalPlayer p = MC.player;
		Options o = InputUtil.opt();
		InputUtil.hold(o.keyShift, false);
		
		// something new blocks us (gravel fell, a door closed...) → rethink
		if(!WorldUtil.isFeetCell(node.pos)
			|| !WorldUtil.isPassable(node.pos.above()))
		{
			plan();
			return status;
		}
		
		// Like Baritone: a step is done as soon as the feet are in the
		// destination block (standing, or swimming). No stopping at centres,
		// so the walk flows from one block into the next.
		BlockPos feet = WorldUtil.feet(p);
		if(feet.equals(node.pos) && (p.onGround() || p.isInWater()))
		{
			advance();
			if(index >= path.size())
			{
				InputUtil.stopWalking();
				return status = Status.WALKING;
			}
			PathFinder.Node next = path.get(index);
			// only keep flowing into plain walking steps; anything special
			// (dig, bridge, tower, jump) starts from a standstill next tick
			if(!isPlainStep(next))
				return status = Status.WALKING;
			prev = node;
			node = next;
		}
		
		// wandered off the path (knocked back, slid...) → rethink
		if(distanceToPath() > 2.2)
		{
			plan();
			return status;
		}
		
		// aim at the centre of the next block, or further down a straight
		// line so the walk doesn't wobble from block to block
		Vec3 aim = aimPoint();
		Vec3 pos = p.position();
		double dx = aim.x - pos.x, dz = aim.z - pos.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		
		// the camera follows smoothly (with a glance now and then); the
		// legs use whichever keys go the right way from where it points
		updateGlance();
		rot.look(yaw + glanceOffset, walkPitch);
		boolean sprintOk = isStraightAhead() && !p.isInWater()
			&& HumanBot.modules().isEnabled("AutoSprint")
			&& p.getFoodData().getFoodLevel() > 6;
		InputUtil.moveTowards(yaw, sprintOk);
		
		// jumping: stepping up a block, swimming, or a low obstacle
		double nodeDx = node.pos.getX() + 0.5 - pos.x;
		double nodeDz = node.pos.getZ() + 0.5 - pos.z;
		double toNode = Math.sqrt(nodeDx * nodeDx + nodeDz * nodeDz);
		boolean jump;
		if(p.isInWater())
			jump = node.pos.getY() >= feet.getY() || p.horizontalCollision;
		else
			jump = p.onGround() && ((node.move == Move.ASCEND && toNode < 1.25)
				|| (p.horizontalCollision && node.pos.getY() > feet.getY()));
		InputUtil.hold(o.keyJump, jump);
		
		trackStuck(toNode);
		return status = Status.WALKING;
	}
	
	/** Steps the walker can flow straight into without stopping. */
	private static boolean isPlainStep(PathFinder.Node n)
	{
		return n.toBreak.isEmpty() && (n.move == Move.TRAVERSE
			|| n.move == Move.DIAGONAL || n.move == Move.DESCEND
			|| n.move == Move.ASCEND);
	}
	
	/**
	 * Where to steer: the next block's centre, or the furthest block ahead
	 * on the same straight, flat, walkable line (up to 4 ahead).
	 */
	private Vec3 aimPoint()
	{
		PathFinder.Node node = path.get(index);
		Vec3 aim = WorldUtil.center(node.pos);
		int dx = node.pos.getX() - path.get(index - 1).pos.getX();
		int dz = node.pos.getZ() - path.get(index - 1).pos.getZ();
		for(int i = index + 1; i < Math.min(path.size(), index + 5); i++)
		{
			PathFinder.Node n = path.get(i), before = path.get(i - 1);
			if(n.move != Move.TRAVERSE && n.move != Move.DIAGONAL
				|| !n.toBreak.isEmpty() || n.pos.getY() != node.pos.getY()
				|| n.pos.getX() - before.pos.getX() != dx
				|| n.pos.getZ() - before.pos.getZ() != dz)
				break;
			aim = WorldUtil.center(n.pos);
		}
		return aim;
	}
	
	/** Horizontal distance from the player to the nearest nearby path block. */
	private double distanceToPath()
	{
		Vec3 pos = MC.player.position();
		double best = Double.MAX_VALUE;
		for(int i = Math.max(0, index - 2); i < Math.min(path.size(),
			index + 3); i++)
		{
			BlockPos b = path.get(i).pos;
			double dx = b.getX() + 0.5 - pos.x, dz = b.getZ() + 0.5 - pos.z;
			double dy = Math.max(0, Math.abs(b.getY() - pos.y) - 1.5);
			best = Math.min(best, Math.sqrt(dx * dx + dz * dz + dy * dy));
		}
		return best;
	}
	
	// ------------------------------------------------------------ pillar

	private Status pillar(HumanRotator rot, PathFinder.Node prev,
		PathFinder.Node node)
	{
		LocalPlayer p = MC.player;
		Options o = InputUtil.opt();
		BlockPos placeAt = prev.pos; // the block we stand in becomes floor

		if(p.onGround() && WorldUtil.feet(p).equals(node.pos))
		{
			advance();
			return status = Status.PLACING;
		}

		InputUtil.hold(o.keyUp, false);
		InputUtil.hold(o.keySprint, false);
		// people look almost straight down when towering
		rot.look(p.getYRot(), (float)Rand.gaussian(86, 1.5, 80, 90));

		if(!Placer.holdThrowaway())
		{
			if(Placer.throwawaySlot() < 0)
				return outOfBlocks();
			return status = Status.PLACING;
		}

		// wait until the aim has settled before the first jump
		if(p.onGround())
		{
			InputUtil.hold(o.keyJump, rot.error() < 6 && !rot.isReacting());
			jumpTimer++;
		}else
			InputUtil.hold(o.keyJump, false);

		// place under our feet once they're above the block
		if(!p.onGround() && p.getY() > placeAt.getY() + 1.0
			&& WorldUtil.isReplaceable(placeAt) && placeCooldown <= 0)
		{
			Placer.Target t = Placer.find(placeAt, 0.25);
			if(t != null && Placer.crosshairOn(t)
				&& InputUtil.useOnLookedAtBlock())
				placeCooldown = 4;
		}
		if(placeCooldown > 0)
			placeCooldown--;

		if(jumpTimer > 120)
		{
			jumpTimer = 0;
			plan();
		}
		return status = Status.PLACING;
	}

	// ------------------------------------------------------------ bridge

	private Status bridge(HumanRotator rot, PathFinder.Node prev,
		PathFinder.Node node)
	{
		LocalPlayer p = MC.player;
		Options o = InputUtil.opt();
		BlockPos placeAt = node.toPlace;

		if(placeAt == null || !WorldUtil.isReplaceable(placeAt))
		{
			// the gap is filled: creep onto the new block, still sneaking
			Status s = walk(rot, prev, node);
			InputUtil.hold(o.keyShift, true);
			InputUtil.hold(o.keySprint, false);
			return s;
		}

		if(!Placer.holdThrowaway())
		{
			if(Placer.throwawaySlot() < 0)
				return outOfBlocks();
			return status = Status.PLACING;
		}

		// sneak so we can't fall off, then creep to the edge
		InputUtil.hold(o.keyShift, true);
		InputUtil.hold(o.keySprint, false);
		InputUtil.hold(o.keyJump, false);

		Placer.Target t = Placer.find(placeAt, placeJitter);
		Vec3 edge = WorldUtil.center(prev.pos).add(
			(node.pos.getX() - prev.pos.getX()) * 0.5, -0.5,
			(node.pos.getZ() - prev.pos.getZ()) * 0.5);
		if(t == null)
		{
			// can't see a face yet: look down at the edge and step closer
			rot.lookAt(edge);
			InputUtil.hold(o.keyUp, rot.error() < 50);
			trackStuck(WorldUtil.dist(p.position(), edge));
			return status = Status.PLACING;
		}

		InputUtil.hold(o.keyUp, false);
		rot.lookAt(t.point());
		if(placeCooldown > 0)
			placeCooldown--;
		else if(Placer.crosshairOn(t) && !rot.isReacting()
			&& InputUtil.useOnLookedAtBlock())
		{
			placeCooldown = Rand.ticks(3, 1, 2, 6);
			placeJitter = Rand.uniform(0.15, 0.35);
		}
		return status = Status.PLACING;
	}

	// ------------------------------------------------------------ parkour

	private Status parkour(HumanRotator rot, PathFinder.Node prev,
		PathFinder.Node node)
	{
		LocalPlayer p = MC.player;
		Options o = InputUtil.opt();

		int ddx = Integer.signum(node.pos.getX() - prev.pos.getX());
		int ddz = Integer.signum(node.pos.getZ() - prev.pos.getZ());
		Vec3 land = WorldUtil.center(node.pos);
		float yaw = HumanRotator.yawTo(p.position(), land);
		rot.look(yaw, (float)Rand.gaussian(12, 3, 0, 25));

		boolean facing =
			Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 12f;
		InputUtil.hold(o.keyShift, false);
		InputUtil.hold(o.keyUp, facing);
		InputUtil.hold(o.keySprint, facing);

		// how far past the centre of the take-off block we are
		double along = (p.getX() - (prev.pos.getX() + 0.5)) * ddx
			+ (p.getZ() - (prev.pos.getZ() + 0.5)) * ddz;
		boolean airborne = !p.onGround();
		// jump near the edge; if the next tick would carry us past it, now
		Vec3 v = p.getDeltaMovement();
		double next = along + v.x * ddx + v.z * ddz;
		if(jumpAt == 0)
			jumpAt = Rand.uniform(0.2, 0.42);
		InputUtil.hold(o.keyJump, p.onGround() && facing && along < 0.6
			&& (along > jumpAt || next > 0.47));

		if(p.onGround() && WorldUtil.feet(p).equals(node.pos))
			advance();
		else if(!airborne && p.getY() < prev.pos.getY() - 0.5)
			plan(); // missed the jump
		else
			trackStuck(WorldUtil.dist(p.position(), land));

		return status = Status.WALKING;
	}

	// ------------------------------------------------------------ helpers

	private Status outOfBlocks()
	{
		// with no blocks in the hotbar the next plan simply won't place any
		HumanBot.chat("Out of throwaway blocks, finding another way");
		plan();
		return status;
	}

	/**
	 * Like Baritone: if the player is standing on a later node of the path
	 * (cut a corner, got flung forward, fell down early), carry on from there
	 * instead of walking back to the node we "missed".
	 */
	private void syncProgress(boolean grounded)
	{
		if(!grounded || path == null)
			return;
		BlockPos feet = WorldUtil.feet(MC.player);
		int last = Math.min(path.size() - 1, index + 4);
		for(int i = last; i >= index; i--)
			if(path.get(i).pos.equals(feet))
			{
				index = i;
				advance();
				return;
			}
	}

	private void advance()
	{
		index++;
		stuckTicks = 0;
		jumpTimer = 0;
		jumpAt = 0;
		bestDist = Double.MAX_VALUE;
	}

	private void trackStuck(double dist)
	{
		if(!MC.player.onGround() && !MC.player.isInWater())
			return; // falling or mid-jump is not "stuck"
		if(dist < bestDist - 0.05)
		{
			bestDist = dist;
			stuckTicks = 0;
			return;
		}
		stuckTicks++;
		// pressing into something for a moment: try a hop over it
		if(stuckTicks == 20 && MC.player.onGround())
			InputUtil.hold(InputUtil.opt().keyJump, true);
		// still not getting anywhere: think again from here
		if(stuckTicks > 45)
			plan();
	}

	private void mineBlock(HumanRotator rot, BlockPos b)
	{
		rot.lookAt(WorldUtil.center(b));
		HitResult hr = MC.hitResult;
		boolean onIt = hr instanceof BlockHitResult bhr
			&& hr.getType() == HitResult.Type.BLOCK
			&& bhr.getBlockPos().equals(b);
		InputUtil.mine(onIt && !rot.isReacting());
		if(onIt)
			breakPause =
				BotConfig.get().microPauses ? Rand.ticks(3, 2, 0, 8) : 0;
	}

	private boolean isStraightAhead()
	{
		if(path == null || index < 1 || index + 2 >= path.size())
			return false;
		BlockPos a = path.get(index - 1).pos, b = path.get(index).pos,
			c = path.get(index + 1).pos, d = path.get(index + 2).pos;
		int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
		for(int i = index; i <= index + 1; i++)
		{
			Move m = path.get(i).move;
			if(m != Move.TRAVERSE && m != Move.DIAGONAL)
				return false;
			if(!path.get(i).toBreak.isEmpty())
				return false;
		}
		return b.getY() == a.getY() && c.getY() == b.getY()
			&& c.getX() - b.getX() == dx && c.getZ() - b.getZ() == dz
			&& d.getX() - c.getX() == dx && d.getZ() - c.getZ() == dz;
	}

	/** Occasionally glance to the side while walking, like people do. */
	private void updateGlance()
	{
		if(glanceLeft > 0)
		{
			if(--glanceLeft == 0)
				glanceOffset = 0;
			return;
		}
		if(--glanceTimer <= 0 && isStraightAhead())
		{
			glanceOffset = (float)(Rand.uniform(8, 20)
				* (Rand.chance(0.5) ? 1 : -1));
			glanceLeft = Rand.ticks(16, 5, 8, 28);
			glanceTimer = Rand.ticks(240, 80, 100, 500);
			walkPitch = (float)Rand.gaussian(14, 5, 2, 30);
		}
	}
}
