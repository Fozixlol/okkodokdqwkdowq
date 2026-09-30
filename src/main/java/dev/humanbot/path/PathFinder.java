package dev.humanbot.path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import dev.humanbot.BotConfig;
import dev.humanbot.util.WorldUtil;
import net.minecraft.core.BlockPos;

/**
 * A* pathfinder over block positions (the position is where the player's
 * feet go). Inspired by Baritone: costs are measured in ticks, and it can
 * walk, jump, drop, dig, tower up, bridge gaps and parkour small gaps.
 * Runs a limited number of nodes per tick so the game never stutters, and
 * returns the best partial path when the goal is too far.
 */
public final class PathFinder
{
	// rough tick costs (20 ticks = 1 second)
	static final double WALK = 4.63;
	static final double SPRINT = 3.56;
	static final double JUMP_UP = 2.5;
	static final double FALL_PER_BLOCK = 1.2;
	static final double PLACE = 12;
	static final double PILLAR = 14;
	/** Digging is slow and looks odd: only worth it when walking round is far. */
	static final double BREAK_BASE = 18;
	static final double PARKOUR_EXTRA = 6;

	public interface Goal
	{
		boolean reached(BlockPos pos);

		double heuristic(BlockPos pos);

		String describe();
	}

	public static final class Node
	{
		public final BlockPos pos;
		public final Move move;
		public final List<BlockPos> toBreak;
		/** Block to place for this move (under the destination), or null. */
		public final BlockPos toPlace;
		final Node parent;
		final double g, f;
		final int placesUsed;

		Node(BlockPos pos, Move move, Node parent, double g, double h,
			List<BlockPos> toBreak, BlockPos toPlace, int placesUsed)
		{
			this.pos = pos;
			this.move = move;
			this.parent = parent;
			this.g = g;
			this.f = g + h;
			this.toBreak = toBreak;
			this.toPlace = toPlace;
			this.placesUsed = placesUsed;
		}
	}

	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
		{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private final Goal goal;
	private final boolean allowBreak;
	private final boolean allowPlace;
	private final boolean allowParkour;
	private final int maxFall;
	private final int placeBudget;
	private final int maxNodes;
	private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> {
		int c = Double.compare(a.f, b.f);
		return c != 0 ? c : Double.compare(b.g, a.g);
	});
	private final Map<BlockPos, Double> bestG = new HashMap<>();
	private final BlockPos start;
	private Node closest;
	private double closestH;
	private int expanded;
	/** Baritone's trick for partial paths: best node per h-weighting. */
	private static final double[] COEFF = {1.5, 2, 2.5, 3, 4, 5, 10};
	private final Node[] bestSoFar = new Node[COEFF.length];
	private final double[] bestVal = new double[COEFF.length];
	private long searchNanos;
	private final long timeoutNanos;
	private List<Node> result;
	private boolean reachesGoal;
	private boolean done;

	public PathFinder(BlockPos start, Goal goal, boolean allowBreak,
		int placeBudget, int maxNodes, long timeoutMs)
	{
		this.start = start;
		this.timeoutNanos = timeoutMs * 1_000_000L;
		java.util.Arrays.fill(bestVal, Double.MAX_VALUE);
		BotConfig c = BotConfig.get();
		this.goal = goal;
		this.allowBreak = allowBreak;
		this.allowPlace = c.allowPlace && placeBudget > 0;
		this.allowParkour = c.allowParkour;
		this.maxFall = Math.max(1, c.maxFall);
		this.placeBudget = placeBudget;
		this.maxNodes = maxNodes;
		double h = goal.heuristic(start);
		Node n = new Node(start, Move.START, null, 0, h, List.of(), null, 0);
		open.add(n);
		bestG.put(start, 0.0);
		closest = n;
		closestH = h;
	}

	public boolean isDone()
	{
		return done;
	}

	/** Path including the start node, or null if nothing useful was found. */
	public List<Node> getResult()
	{
		return result;
	}

	/** False if this is only a partial path towards a far-away goal. */
	public boolean reachesGoal()
	{
		return reachesGoal;
	}

	/**
	 * Search for at most {@code millis} ms (checked every 100 nodes), so a
	 * long search is spread over several ticks instead of freezing a frame.
	 */
	public boolean stepFor(double millis)
	{
		long begin = System.nanoTime();
		long end = begin + (long)(millis * 1_000_000);
		while(!done)
		{
			step(100);
			long now = System.nanoTime();
			if(!done && searchNanos + (now - begin) > timeoutNanos)
			{
				// out of time: walk the best partial path found so far
				finish(bestPartial(), false);
				break;
			}
			if(now > end)
				break;
		}
		searchNanos += System.nanoTime() - begin;
		return done;
	}

	/**
	 * Best node to head for when the goal wasn't reached: the first h-weighting
	 * whose best node is at least 5 blocks from the start (so we actually
	 * make progress), else the node closest to the goal.
	 */
	private Node bestPartial()
	{
		for(Node n : bestSoFar)
			if(n != null && n.pos.distSqr(start) > 25)
				return n;
		return closest != null && closest.parent != null ? closest : null;
	}

	/** Expand up to {@code budget} nodes. Returns true when finished. */
	public boolean step(int budget)
	{
		if(done)
			return true;

		for(int i = 0; i < budget; i++)
		{
			Node cur = open.poll();
			if(cur == null || expanded++ > maxNodes)
			{
				finish(bestPartial(), false);
				return true;
			}

			Double known = bestG.get(cur.pos);
			if(known != null && known < cur.g)
				continue;

			if(goal.reached(cur.pos))
			{
				finish(cur, true);
				return true;
			}

			double h = goal.heuristic(cur.pos);
			if(h < closestH)
			{
				closestH = h;
				closest = cur;
			}
			for(int k = 0; k < COEFF.length; k++)
			{
				double v = cur.g + h * COEFF[k];
				if(v < bestVal[k])
				{
					bestVal[k] = v;
					bestSoFar[k] = cur;
				}
			}

			expand(cur);
		}
		return false;
	}

	private void finish(Node end, boolean complete)
	{
		done = true;
		reachesGoal = complete;
		if(end == null)
			return;
		ArrayList<Node> path = new ArrayList<>();
		for(Node n = end; n != null; n = n.parent)
			path.add(0, n);
		result = path;
	}

	private void add(Node cur, BlockPos next, Move move, double cost,
		List<BlockPos> breaks, BlockPos place)
	{
		if(cost < 0)
			return;
		int places = cur.placesUsed + (place != null ? 1 : 0)
			+ (move == Move.PILLAR ? 1 : 0);
		if(places > placeBudget)
			return;
		double g = cur.g + cost;
		Double known = bestG.get(next);
		if(known != null && known <= g)
			return;
		bestG.put(next, g);
		open.add(new Node(next, move, cur, g, goal.heuristic(next),
			breaks.isEmpty() ? List.of() : breaks, place, places));
	}

	private void expand(Node cur)
	{
		BlockPos c = cur.pos;
		boolean grounded = standsOnSomething(cur);

		for(int[] d : DIRS)
		{
			boolean diag = d[0] != 0 && d[1] != 0;
			if(diag)
			{
				if(!grounded)
					continue;
				BlockPos n = c.offset(d[0], 0, d[1]);
				add(cur, n, Move.DIAGONAL, diagonal(c, n), List.of(), null);
				continue;
			}

			// ascend
			if(grounded)
			{
				BlockPos n = c.offset(d[0], 1, d[1]);
				List<BlockPos> br = new ArrayList<>(3);
				add(cur, n, Move.ASCEND, ascend(c, n, br), br, null);
			}

			// traverse or bridge
			if(grounded)
			{
				BlockPos n = c.offset(d[0], 0, d[1]);
				List<BlockPos> br = new ArrayList<>(2);
				double cost = body(n, br);
				if(cost >= 0)
				{
					if(floor(n))
						add(cur, n, Move.TRAVERSE, WALK + cost + hazard(n), br,
							null);
					else if(allowPlace && WorldUtil.isReplaceable(n.below())
						&& !WorldUtil.isDangerous(n.below())
						&& !WorldUtil.isLowFloor(n))
						add(cur, n, Move.BRIDGE, WALK * 2 + PLACE + cost, br,
							n.below());
				}
			}

			// descend / fall (walking off an edge works from anywhere
			// we can stand)
			if(grounded)
				for(int dy = 1; dy <= maxFall; dy++)
				{
					BlockPos n = c.offset(d[0], -dy, d[1]);
					List<BlockPos> br = new ArrayList<>(dy + 1);
					double cost = descend(c, n, br);
					if(cost == -2)
						break; // something solid in the way below: stop looking
					add(cur, n, Move.DESCEND, cost, br, null);
					if(cost >= 0)
						break; // landed, no deeper fall in this column
				}

			// parkour over 1-2 block gaps
			if(allowParkour && grounded)
				for(int gap = 1; gap <= 2; gap++)
				{
					if(gap == 2 && !(cur.move == Move.TRAVERSE
						&& cur.parent != null
						&& cur.pos.getX() - cur.parent.pos.getX() == d[0]
						&& cur.pos.getZ() - cur.parent.pos.getZ() == d[1]))
						continue; // long jumps need a run-up
					BlockPos n = c.offset(d[0] * (gap + 1), 0, d[1] * (gap + 1));
					add(cur, n, Move.PARKOUR, parkour(c, d, gap), List.of(),
						null);
				}
		}

		// pillar straight up
		if(allowPlace && grounded && !WorldUtil.isWater(c)
			&& !WorldUtil.isLowFloor(c))
		{
			BlockPos n = c.above();
			List<BlockPos> br = new ArrayList<>(1);
			double cost = clearance(c.above(2), br, 1);
			if(cost >= 0)
				add(cur, n, Move.PILLAR, PILLAR + cost, br, null);
		}
	}

	// ---- per-search caches (the same blocks get asked about many times) ----
	private final Map<BlockPos, Boolean> passCache = new HashMap<>();
	private final Map<BlockPos, Boolean> feetCache = new HashMap<>();
	private final Map<BlockPos, Boolean> standCache = new HashMap<>();
	private final Map<BlockPos, Boolean> lowCache = new HashMap<>();

	private boolean passable(BlockPos p)
	{
		return passCache.computeIfAbsent(p, WorldUtil::isPassable);
	}

	/** Passable for the feet cell (also a cell holding a bottom slab). */
	private boolean feetOk(BlockPos p)
	{
		return feetCache.computeIfAbsent(p, WorldUtil::isFeetCell);
	}

	private boolean lowFloor(BlockPos p)
	{
		return lowCache.computeIfAbsent(p, WorldUtil::isLowFloor);
	}

	/** Something solid enough to stand on (top above half a block). */
	private boolean standable(BlockPos p)
	{
		return standCache.computeIfAbsent(p,
			q -> !WorldUtil.isDangerous(q) && (WorldUtil.collisionTop(q) > 0.5
				|| WorldUtil.isWater(q)));
	}

	/** Does a player whose feet are in cell n have a floor? */
	private boolean floor(BlockPos n)
	{
		return standable(n.below()) || lowFloor(n);
	}

	/**
	 * True if the node's floor exists: a real block, or one this path will
	 * already have placed (after towering or bridging).
	 */
	private boolean standsOnSomething(Node n)
	{
		if(n.move == Move.PILLAR)
			return true; // stands on the block it just placed
		if(n.move == Move.BRIDGE && n.toPlace != null
			&& n.toPlace.equals(n.pos.below()))
			return true;
		return floor(n.pos);
	}

	/** Cost of making the feet cell and the one above it free. */
	private double body(BlockPos n, List<BlockPos> breaks)
	{
		double cost = 0;
		if(!feetOk(n))
		{
			double b = breakCost(n, breaks);
			if(b < 0)
				return -1;
			cost += b;
		}
		double up = clearance(n.above(), breaks, 1);
		if(up < 0)
			return -1;
		cost += up;
		// standing on a slab puts the head higher
		if(lowFloor(n))
		{
			double top = clearance(n.above(2), breaks, 1);
			if(top < 0)
				return -1;
			cost += top;
		}
		return cost;
	}

	private double breakCost(BlockPos p, List<BlockPos> breaks)
	{
		if(!allowBreak || WorldUtil.isDangerous(p) || !WorldUtil.isSafeToBreak(p))
			return -1;
		breaks.add(p);
		return BREAK_BASE + WorldUtil.breakTicks(p) * 1.5;
	}

	/** Cost of making {@code count} cells from {@code from} upward free. */
	private double clearance(BlockPos from, List<BlockPos> breaks, int count)
	{
		double cost = 0;
		for(int i = 0; i < count; i++)
		{
			BlockPos p = from.above(i);
			if(passable(p))
				continue;
			double b = breakCost(p, breaks);
			if(b < 0)
				return -1;
			cost += b;
		}
		return cost;
	}

	private double hazard(BlockPos n)
	{
		double c = 0;
		if(WorldUtil.isWater(n))
			c += WALK * 1.5;
		if(WorldUtil.lavaNearby(n) || WorldUtil.lavaNearby(n.above()))
			c += 200;
		return c;
	}

	private double diagonal(BlockPos c, BlockPos n)
	{
		if(!floor(n) || !feetOk(n) || !passable(n.above())
			|| lowFloor(n) && !passable(n.above(2)))
			return -1;
		BlockPos s1 = new BlockPos(n.getX(), c.getY(), c.getZ());
		BlockPos s2 = new BlockPos(c.getX(), c.getY(), n.getZ());
		// no cutting corners through walls
		if(!feetOk(s1) || !passable(s1.above())
			|| !feetOk(s2) || !passable(s2.above()))
			return -1;
		// stepping between a slab and a full floor: keep it simple
		if(lowFloor(n) != lowFloor(c) || lowFloor(s1) || lowFloor(s2))
			return -1;
		return WALK * 1.414 + hazard(n);
	}

	private double ascend(BlockPos c, BlockPos n, List<BlockPos> breaks)
	{
		if(!floor(n))
			return -1;
		// a jump is 1.25 high: can't hop from ground onto a slab a block up
		if(lowFloor(n) && !lowFloor(c))
			return -1;
		double a = clearance(c.above(2), breaks, 1);
		if(a < 0)
			return -1;
		double b = body(n, breaks);
		if(b < 0)
			return -1;
		return WALK + JUMP_UP + a + b + hazard(n);
	}

	/**
	 * -1: impossible here but a deeper landing might work; -2: blocked,
	 * stop searching deeper.
	 */
	private double descend(BlockPos c, BlockPos n, List<BlockPos> breaks)
	{
		int dy = c.getY() - n.getY();
		// the column we fall down: from our head height to the landing spot
		double cost = 0;
		for(int y = c.getY() + 1; y >= n.getY(); y--)
		{
			BlockPos p = new BlockPos(n.getX(), y, n.getZ());
			if(y == n.getY() ? feetOk(p) : passable(p))
				continue;
			// we only dig the part we'd walk into, not the floor below
			if(y >= c.getY())
			{
				double b = breakCost(p, breaks);
				if(b >= 0)
				{
					cost += b;
					continue;
				}
			}
			return y < c.getY() ? -2 : -1;
		}
		if(!floor(n))
			return -1;
		if(dy > 3 && !WorldUtil.isWater(n))
			cost += (dy - 3) * 30; // fall damage: avoid unless worth it
		return WALK + FALL_PER_BLOCK * dy + cost + hazard(n);
	}

	private double parkour(BlockPos c, int[] d, int gap)
	{
		if(lowFloor(c) || !passable(c.above(2)))
			return -1;
		for(int i = 1; i <= gap; i++)
		{
			BlockPos p = c.offset(d[0] * i, 0, d[1] * i);
			if(floor(p))
				return -1; // not a gap
			for(int y = 0; y <= 2; y++)
				if(!passable(p.above(y)))
					return -1;
			// don't jump over lava
			for(int y = 1; y <= 4; y++)
				if(WorldUtil.isLava(p.below(y)))
					return -1;
		}
		BlockPos land = c.offset(d[0] * (gap + 1), 0, d[1] * (gap + 1));
		if(!floor(land) || lowFloor(land) || WorldUtil.isWater(land)
			|| !passable(land) || !passable(land.above())
			|| !passable(land.above(2)))
			return -1;
		return SPRINT * (gap + 1) + PARKOUR_EXTRA + gap * 4 + hazard(land);
	}

	// ---- goals ----

	public static Goal near(BlockPos target, double radius)
	{
		return new Goal()
		{
			@Override
			public boolean reached(BlockPos pos)
			{
				return pos.distSqr(target) <= radius * radius;
			}

			@Override
			public double heuristic(BlockPos pos)
			{
				return Math.max(0, Math.sqrt(pos.distSqr(target)) - radius)
					* SPRINT;
			}

			@Override
			public String describe()
			{
				return "near " + target.toShortString();
			}
		};
	}

	/** Reach the block exactly (or any Y for {@code xzOnly}). */
	public static Goal at(BlockPos target, boolean xzOnly)
	{
		return new Goal()
		{
			@Override
			public boolean reached(BlockPos pos)
			{
				return pos.getX() == target.getX() && pos.getZ() == target.getZ()
					&& (xzOnly || pos.getY() == target.getY());
			}

			@Override
			public double heuristic(BlockPos pos)
			{
				double dx = Math.abs(pos.getX() - target.getX());
				double dz = Math.abs(pos.getZ() - target.getZ());
				double dy = xzOnly ? 0 : pos.getY() - target.getY();
				double diag = Math.min(dx, dz);
				return (diag * 1.414 + (Math.max(dx, dz) - diag)) * SPRINT
					+ Math.abs(dy) * (dy > 0 ? FALL_PER_BLOCK : WALK + JUMP_UP);
			}

			@Override
			public String describe()
			{
				return xzOnly ? "x " + target.getX() + " z " + target.getZ()
					: target.toShortString();
			}
		};
	}

	/** Get to a given height (Baritone's GoalYLevel). */
	public static Goal yLevel(int y)
	{
		return new Goal()
		{
			@Override
			public boolean reached(BlockPos pos)
			{
				return pos.getY() == y;
			}

			@Override
			public double heuristic(BlockPos pos)
			{
				int dy = pos.getY() - y;
				return Math.abs(dy) * (dy > 0 ? WALK : WALK + JUMP_UP);
			}

			@Override
			public String describe()
			{
				return "y=" + y;
			}
		};
	}
}
