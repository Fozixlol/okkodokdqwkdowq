package dev.humanbot.module;

import dev.humanbot.BotConfig;
import dev.humanbot.rotation.HumanRotator;
import net.minecraft.client.Minecraft;

/**
 * A behaviour. Three kinds:
 * <ul>
 * <li><b>Helper</b> (priority &lt; 0): runs alongside anything, e.g.
 * AutoSprint.</li>
 * <li><b>Automation</b> (persistent toggle): AutoEat, MobFighter, AntiAFK.
 * Can interrupt a process when needed.</li>
 * <li><b>{@link Process}</b>: a job you give the bot (goto, mine, farm...).
 * Only one process runs at a time, like Baritone.</li>
 * </ul>
 */
public abstract class Module
{
	protected static final Minecraft MC = Minecraft.getInstance();

	private final String name;
	private final String description;
	private final boolean defaultOn;
	private final int priority;

	protected Module(String name, String description, boolean defaultOn,
		int priority)
	{
		this.name = name;
		this.description = description;
		this.defaultOn = defaultOn;
		this.priority = priority;
	}

	public final String name()
	{
		return name;
	}

	public final String description()
	{
		return description;
	}

	/** Higher runs first. Negative = helper module (never takes control). */
	public final int priority()
	{
		return priority;
	}

	public final boolean isHelper()
	{
		return priority < 0;
	}

	public boolean isEnabled()
	{
		return BotConfig.get().isEnabled(name, defaultOn);
	}

	public void setEnabled(boolean on)
	{
		if(on == isEnabled())
			return;
		BotConfig.get().enabled.put(name, on);
		BotConfig.save();
		if(!on)
			onDisable();
	}

	/** Task modules: does this want the body right now? */
	public boolean wantsControl()
	{
		return false;
	}

	/** Task modules: called every tick while in control. */
	public void tickActive(HumanRotator rot)
	{}

	/** Task modules: another module took over (or we're paused). */
	public void onLoseControl()
	{}

	/** Helper modules: every tick while enabled. */
	public void tickHelper()
	{}

	protected void onDisable()
	{
		onLoseControl();
	}

	/** One-line status for the HUD. */
	public String status()
	{
		return "";
	}
}
