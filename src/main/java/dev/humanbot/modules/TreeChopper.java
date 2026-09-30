package dev.humanbot.modules;

import net.minecraft.world.level.block.state.BlockState;

/** Chops down nearby trees, log by log. */
public final class TreeChopper extends GatherModule
{
	public TreeChopper()
	{
		super("Chop", "Chop down nearby trees", 39);
	}
	
	@Override
	protected boolean wants(BlockState state, String id)
	{
		return (id.endsWith("_log") || id.endsWith("_stem"))
			&& !id.startsWith("stripped_");
	}
	
	@Override
	protected String verb()
	{
		return "chop";
	}
}
