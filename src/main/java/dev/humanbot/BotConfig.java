package dev.humanbot;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * All user-tunable settings. Saved as JSON in config/humanbot.json.
 * Every field here has a button in the in-game menu (Right Shift).
 */
public final class BotConfig
{
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE =
		FabricLoader.getInstance().getConfigDir().resolve("humanbot.json");

	private static BotConfig instance = new BotConfig();

	// ---- which modules are switched on ----
	public Map<String, Boolean> enabled = new LinkedHashMap<>();

	// ---- humanizer ----
	/** Rough top turning speed in degrees per tick (20 ticks = 1 s). */
	public float turnSpeed = 22f;
	/** Mean reaction time in ms before reacting to a new target. */
	public int reactionMs = 220;
	/** How shaky the aim is (0 = robotic, 1 = very shaky). */
	public float aimShake = 0.35f;
	/** Chance (0-1) of overshooting a big turn and correcting back. */
	public float overshootChance = 0.35f;
	/** Chance per attack of mis-timing a click (swinging a bit late). */
	public float clickSloppiness = 0.25f;
	/** Take a short "thinking" pause between tasks. */
	public boolean microPauses = true;

	// ---- gameplay ----
	public int eatBelowHunger = 14;
	public int fightRange = 10;
	public int scanRadius = 24;
	public boolean allowBreakingForPaths = true;
	/** Only go for ores with an open side (like a person who can see them). */
	public boolean onlyExposedOres = true;
	public List<String> mineTargets = new ArrayList<>(List.of("coal_ore",
		"deepslate_coal_ore", "iron_ore", "deepslate_iron_ore", "copper_ore",
		"deepslate_copper_ore", "gold_ore", "deepslate_gold_ore",
		"redstone_ore", "deepslate_redstone_ore", "lapis_ore",
		"deepslate_lapis_ore", "diamond_ore", "deepslate_diamond_ore",
		"emerald_ore", "deepslate_emerald_ore"));

	// ---- pathing (Baritone-style) ----
	/** Place blocks to tower up and bridge gaps. */
	public boolean allowPlace = true;
	/** Sprint-jump over 1-2 block gaps. */
	public boolean allowParkour = true;
	/** Highest drop the pathfinder takes on purpose. */
	public int maxFall = 3;
	/** Keep working when the game window is in the background. */
	public boolean backgroundMode = true;
	public List<String> throwawayBlocks = new ArrayList<>(List.of("cobblestone",
		"cobbled_deepslate", "dirt", "netherrack", "stone", "andesite",
		"diorite", "granite", "tuff", "deepslate", "blackstone", "end_stone",
		"basalt", "sandstone", "coarse_dirt", "calcite"));
	
	/** Show the status line in the top-left corner. */
	public boolean showHud = true;
	
	// ---- processes ----
	public int farmRadius = 16;
	
	// ---- waypoints ----
	public List<Waypoint> waypoints = new ArrayList<>();
	
	public static final class Waypoint
	{
		public String name;
		public int x, y, z;
		public String dimension;
		
		public Waypoint()
		{}
		
		public Waypoint(String name, int x, int y, int z, String dimension)
		{
			this.name = name;
			this.x = x;
			this.y = y;
			this.z = z;
			this.dimension = dimension;
		}
	}
	
	public static BotConfig get()
	{
		return instance;
	}

	public boolean isEnabled(String module, boolean def)
	{
		return enabled.getOrDefault(module, def);
	}

	public static void load()
	{
		if(!Files.exists(FILE))
		{
			save();
			return;
		}

		try(Reader r = Files.newBufferedReader(FILE))
		{
			BotConfig loaded = GSON.fromJson(r, BotConfig.class);
			if(loaded != null)
			{
				if(loaded.enabled == null)
					loaded.enabled = new LinkedHashMap<>();
				BotConfig def = new BotConfig();
				if(loaded.mineTargets == null)
					loaded.mineTargets = def.mineTargets;
				if(loaded.throwawayBlocks == null)
					loaded.throwawayBlocks = def.throwawayBlocks;
				if(loaded.waypoints == null)
					loaded.waypoints = def.waypoints;
				instance = loaded;
			}
		}catch(Exception e)
		{
			HumanBot.LOGGER.warn("Couldn't read " + FILE + ", using defaults", e);
		}
	}

	public static void save()
	{
		try
		{
			Files.createDirectories(FILE.getParent());
			try(Writer w = Files.newBufferedWriter(FILE))
			{
				GSON.toJson(instance, w);
			}
		}catch(Exception e)
		{
			HumanBot.LOGGER.warn("Couldn't save " + FILE, e);
		}
	}
}
