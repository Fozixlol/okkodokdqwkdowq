package dev.humanbot;

import java.util.ArrayList;
import java.util.List;

import dev.humanbot.BotConfig.Waypoint;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Named places, per dimension, saved in the config file. */
public final class Waypoints
{
	private Waypoints()
	{}

	public static String dimension()
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.level == null)
			return "?";
		return mc.level.dimension().identifier().toString();
	}

	/** Save (or overwrite) a waypoint in the current dimension. */
	public static void save(String name, BlockPos pos)
	{
		String dim = dimension();
		BotConfig.get().waypoints.removeIf(
			w -> w.name.equalsIgnoreCase(name) && dim.equals(w.dimension));
		BotConfig.get().waypoints.add(
			new Waypoint(name, pos.getX(), pos.getY(), pos.getZ(), dim));
		BotConfig.save();
	}

	public static Waypoint get(String name)
	{
		String dim = dimension();
		for(Waypoint w : BotConfig.get().waypoints)
			if(w.name.equalsIgnoreCase(name) && dim.equals(w.dimension))
				return w;
		return null;
	}

	public static boolean delete(String name)
	{
		String dim = dimension();
		boolean removed = BotConfig.get().waypoints.removeIf(
			w -> w.name.equalsIgnoreCase(name) && dim.equals(w.dimension));
		if(removed)
			BotConfig.save();
		return removed;
	}

	/** Waypoints in the dimension you're in. */
	public static List<Waypoint> here()
	{
		String dim = dimension();
		List<Waypoint> out = new ArrayList<>();
		for(Waypoint w : BotConfig.get().waypoints)
			if(dim.equals(w.dimension))
				out.add(w);
		return out;
	}

	public static BlockPos pos(Waypoint w)
	{
		return new BlockPos(w.x, w.y, w.z);
	}
}
