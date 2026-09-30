package dev.humanbot.module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.humanbot.modules.AntiAfk;
import dev.humanbot.modules.AutoEat;
import dev.humanbot.modules.AutoRespawn;
import dev.humanbot.modules.AutoSprint;
import dev.humanbot.modules.AutoTool;
import dev.humanbot.modules.Explore;
import dev.humanbot.modules.Farm;
import dev.humanbot.modules.Follow;
import dev.humanbot.modules.GetTo;
import dev.humanbot.modules.Goto;
import dev.humanbot.modules.Miner;
import dev.humanbot.modules.MobFighter;
import dev.humanbot.modules.TreeChopper;
import dev.humanbot.rotation.HumanRotator;
import dev.humanbot.util.InputUtil;
import net.minecraft.client.Minecraft;

/**
 * Holds every module and decides, each tick, which one gets the body.
 * Like Baritone's control manager: highest-priority module that wants
 * control wins; automation (eating, fighting) can interrupt a process and
 * the process picks up again afterwards.
 */
public final class ModuleManager
{
	private final List<Module> modules = new ArrayList<>();
	private final HumanRotator rotator = new HumanRotator();
	private Module active;
	private boolean paused;

	public final Goto gotoProcess = new Goto();
	public final Miner mine = new Miner();
	public final TreeChopper chop = new TreeChopper();
	public final Follow follow = new Follow();
	public final Farm farm = new Farm();
	public final Explore explore = new Explore();
	public final GetTo getTo = new GetTo();

	public ModuleManager()
	{
		// automation
		modules.add(new AutoEat());
		modules.add(new MobFighter());
		modules.add(new AntiAfk());
		// processes (one at a time)
		modules.add(gotoProcess);
		modules.add(getTo);
		modules.add(follow);
		modules.add(farm);
		modules.add(mine);
		modules.add(chop);
		modules.add(explore);
		// helpers
		modules.add(new AutoSprint());
		modules.add(new AutoTool());
		modules.add(new AutoRespawn());
		modules.sort(Comparator.comparingInt(Module::priority).reversed());
	}

	public List<Module> all()
	{
		return modules;
	}

	public List<Process> processes()
	{
		List<Process> out = new ArrayList<>();
		for(Module m : modules)
			if(m instanceof Process p)
				out.add(p);
		return out;
	}

	/** AutoEat, MobFighter, AntiAFK and the helpers. */
	public List<Module> toggles()
	{
		List<Module> out = new ArrayList<>();
		for(Module m : modules)
			if(!(m instanceof Process))
				out.add(m);
		return out;
	}

	public Module get(String name)
	{
		for(Module m : modules)
			if(m.name().equalsIgnoreCase(name))
				return m;
		return null;
	}

	public boolean isEnabled(String name)
	{
		Module m = get(name);
		return m != null && m.isEnabled();
	}

	/** The process that's running (even if something interrupted it). */
	public Process runningProcess()
	{
		for(Module m : modules)
			if(m instanceof Process p && p.isEnabled())
				return p;
		return null;
	}

	void stopOtherProcesses(Process keep)
	{
		for(Module m : modules)
			if(m instanceof Process p && p != keep && p.isEnabled())
				p.setEnabled(false);
	}

	/** Stop every process (like Baritone's #stop). */
	public void stopAll()
	{
		for(Process p : processes())
			p.setEnabled(false);
		switchTo(null);
	}

	public Module active()
	{
		return active;
	}

	public boolean isPaused()
	{
		return paused;
	}

	public void setPaused(boolean paused)
	{
		this.paused = paused;
		if(paused)
			switchTo(null);
	}

	public HumanRotator rotator()
	{
		return rotator;
	}

	public void tick(Minecraft mc)
	{
		if(mc.player == null || mc.level == null)
		{
			active = null;
			return;
		}

		boolean busy = !paused && runningProcess() != null;
		InputUtil.setBackgroundMode(busy);

		// helpers (respawn works even on the death screen)
		if(!paused)
			for(Module m : modules)
				if(m.isHelper() && m.isEnabled())
					m.tickHelper();

		// a menu is open, or we're paused: hands off the controls
		if(paused || mc.gui.screen() != null || !mc.player.isAlive())
		{
			switchTo(null);
			InputUtil.endTick();
			return;
		}

		Module want = null;
		for(Module m : modules)
			if(!m.isHelper() && m.isEnabled() && m.wantsControl())
			{
				want = m;
				break;
			}

		switchTo(want);
		if(active != null)
			active.tickActive(rotator);

		rotator.tick();
		InputUtil.endTick();
	}

	private void switchTo(Module next)
	{
		if(next == active)
			return;
		if(active != null)
			active.onLoseControl();
		InputUtil.releaseAll();
		active = next;
	}
}
