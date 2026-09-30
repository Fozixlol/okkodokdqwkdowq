package dev.humanbot.modules;

import java.util.ArrayList;
import java.util.List;

import dev.humanbot.BotConfig;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mines blocks: the ore list from the settings, or whatever blocks you name
 * with /hb mine &lt;block&gt; [count] (Baritone's #mine).
 */
public final class Miner extends GatherModule
{
	private final List<String> custom = new ArrayList<>();
	
	public Miner()
	{
		super("Mine", "Find and mine ores (or any block you name)", 40);
	}
	
	/** Mine only these blocks this time (empty = the ore list). */
	public void setTargets(List<String> ids)
	{
		custom.clear();
		for(String id : ids)
			custom.add(id.replace("minecraft:", ""));
	}
	
	public List<String> targets()
	{
		return custom.isEmpty() ? BotConfig.get().mineTargets : custom;
	}
	
	@Override
	protected boolean wants(BlockState state, String id)
	{
		return targets().contains(id);
	}
	
	@Override
	protected String verb()
	{
		return "mine";
	}
	
	@Override
	protected void onDisable()
	{
		super.onDisable();
		custom.clear();
	}
}
