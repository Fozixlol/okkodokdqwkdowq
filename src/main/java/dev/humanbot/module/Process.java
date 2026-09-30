package dev.humanbot.module;

import dev.humanbot.HumanBot;

/**
 * A job the player hands to the bot: go somewhere, mine, farm, follow...
 * Starting one stops whatever other process was running. Not saved between
 * sessions: after a restart the bot waits for new orders.
 */
public abstract class Process extends Module
{
	private boolean running;

	protected Process(String name, String description, int priority)
	{
		super(name, description, false, priority);
	}

	@Override
	public final boolean isEnabled()
	{
		return running;
	}

	@Override
	public final void setEnabled(boolean on)
	{
		if(on == running)
			return;
		if(on)
			HumanBot.modules().stopOtherProcesses(this);
		running = on;
		if(on)
			onStart();
		else
			onDisable();
	}

	/** Called when the process is started. */
	protected void onStart()
	{}

	/** End the process from inside (job done or impossible). */
	protected final void finish(String message)
	{
		if(message != null)
			HumanBot.chat(message);
		setEnabled(false);
	}

	@Override
	public boolean wantsControl()
	{
		return running;
	}
}
