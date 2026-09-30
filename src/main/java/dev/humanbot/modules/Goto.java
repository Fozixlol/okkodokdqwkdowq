package dev.humanbot.modules;

import dev.humanbot.BotConfig;
import dev.humanbot.module.Process;
import dev.humanbot.path.PathExecutor;
import dev.humanbot.path.PathFinder;
import dev.humanbot.rotation.HumanRotator;

/** Walks to a goal: coordinates, a waypoint, or a height. */
public final class Goto extends Process
{
	private final PathExecutor exec = new PathExecutor();
	private PathFinder.Goal goal;
	
	public Goto()
	{
		super("Goto", "Walk to coordinates or a waypoint", 60);
	}
	
	/** Start walking to this goal. */
	public void go(PathFinder.Goal goal)
	{
		this.goal = goal;
		exec.stop();
		if(isEnabled())
			return;
		setEnabled(true);
	}
	
	@Override
	public void tickActive(HumanRotator rot)
	{
		if(goal == null)
		{
			finish(null);
			return;
		}
		if(!exec.isActive())
			exec.start(goal, BotConfig.get().allowBreakingForPaths);
		
		PathExecutor.Status s = exec.tick(rot);
		if(s == PathExecutor.Status.ARRIVED)
			finish("Arrived (" + goal.describe() + ")");
		else if(s == PathExecutor.Status.FAILED)
			finish("Couldn't find a way to " + goal.describe());
	}
	
	@Override
	public void onLoseControl()
	{
		exec.stop(); // re-plans from wherever we are when we get it back
	}
	
	@Override
	protected void onDisable()
	{
		exec.stop();
		goal = null;
	}
	
	@Override
	public String status()
	{
		if(goal == null)
			return "";
		return goal.describe() + " §8(" + exec.status().name().toLowerCase()
			+ (exec.currentMove() != null
				? ", " + exec.currentMove().name().toLowerCase() : "")
			+ ", " + exec.nodesLeft() + " steps)";
	}
}
