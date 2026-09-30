package dev.humanbot.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.humanbot.BotConfig;
import dev.humanbot.BotConfig.Waypoint;
import dev.humanbot.HumanBot;
import dev.humanbot.Waypoints;
import dev.humanbot.module.Module;
import dev.humanbot.module.ModuleManager;
import dev.humanbot.module.Process;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Right Shift menu. Five tabs: Tasks (give the bot a job), Automation,
 * Humanizer, Pathing and Waypoints.
 */
public final class BotScreen extends Screen
{
	private enum Tab
	{
		TASKS("Tasks"), AUTOMATION("Automation"), HUMANIZER("Humanizer"),
		PATHING("Pathing"), WAYPOINTS("Waypoints");

		final String title;

		Tab(String title)
		{
			this.title = title;
		}
	}

	private record Label(String text, int x, int y, int color)
	{}

	private record Row(String label, Supplier<String> value, Runnable minus,
		Runnable plus)
	{}

	// remembered between openings
	private static Tab tab = Tab.TASKS;
	private static String gx = "", gy = "", gz = "", mineBlocks = "",
		mineCount = "", followName = "", getToBlock = "crafting_table",
		wpName = "", throwawayInput = "";
	private static int wpPage;

	private final List<Label> labels = new ArrayList<>();
	private int left, top, panelW, panelH;

	public BotScreen()
	{
		super(Component.literal("HumanBot"));
	}

	// ================================================================ layout

	@Override
	public void init()
	{
		labels.clear();
		panelW = Math.min(440, width - 16);
		panelH = Math.min(290, height - 16);
		left = (width - panelW) / 2;
		top = (height - panelH) / 2;

		// tab bar
		int tw = (panelW - 8) / Tab.values().length;
		for(int i = 0; i < Tab.values().length; i++)
		{
			Tab t = Tab.values()[i];
			Button b = Button.builder(
				Component.literal(t == tab ? "§e" + t.title : t.title),
				btn -> {
					tab = t;
					rebuildWidgets();
				}).bounds(left + 4 + i * tw, top + 20, tw - 2, 18).build();
			addRenderableWidget(b);
		}

		int y = top + 46;
		switch(tab)
		{
			case TASKS -> buildTasks(y);
			case AUTOMATION -> buildAutomation(y);
			case HUMANIZER -> buildRows(y, humanizerRows());
			case PATHING -> buildPathing(y);
			case WAYPOINTS -> buildWaypoints(y);
		}

		addRenderableWidget(Button.builder(Component.literal("Done"),
			b -> onClose()).bounds(left + panelW - 64, top + panelH - 24, 60, 20)
			.build());
	}

	// ---------------------------------------------------------------- tasks

	private void buildTasks(int y)
	{
		ModuleManager mm = HumanBot.modules();
		int x = left + 8;
		int col = left + 76;

		// go to
		label("Go to", x, y + 6);
		box(col, y, 50, gx, v -> gx = v);
		box(col + 54, y, 50, gy, v -> gy = v);
		box(col + 108, y, 50, gz, v -> gz = v);
		label("§8x   y (optional)   z", col, y + 21);
		button("Go", col + 162, y, 40, () -> {
			try
			{
				int ix = Integer.parseInt(gx.trim());
				int iz = Integer.parseInt(gz.trim());
				Integer iy = gy.isBlank() ? null : Integer.parseInt(gy.trim());
				HumanBot.goTo(ix, iy, iz);
				onClose();
			}catch(NumberFormatException e)
			{
				HumanBot.chat("Enter numbers for x and z (y is optional)");
			}
		});
		button("Here", col + 206, y, 44, () -> {
			BlockPos p = minecraft.player.blockPosition();
			gx = "" + p.getX();
			gy = "" + p.getY();
			gz = "" + p.getZ();
			rebuildWidgets();
		});
		y += 34;

		// mine
		label("Mine", x, y + 6);
		box(col, y, 158, mineBlocks, v -> mineBlocks = v);
		box(col + 162, y, 40, mineCount, v -> mineCount = v);
		label("§8blocks, e.g. diamond_ore (empty = ores)   count", col,
			y + 21);
		button("Start", col + 206, y, 44, () -> {
			List<String> blocks = new ArrayList<>();
			for(String s : mineBlocks.trim().split("[\\s,]+"))
				if(!s.isEmpty())
					blocks.add(s);
			int count = 0;
			try
			{
				if(!mineCount.isBlank())
					count = Integer.parseInt(mineCount.trim());
			}catch(NumberFormatException ignored)
			{}
			HumanBot.mine(blocks, count);
			onClose();
		});
		y += 34;

		// follow
		label("Follow", x, y + 6);
		box(col, y, 202, followName, v -> followName = v);
		label("§8player name (empty = nearest)", col, y + 21);
		button("Start", col + 206, y, 44, () -> {
			HumanBot.follow(followName.trim());
			onClose();
		});
		y += 34;

		// get to
		label("Get to", x, y + 6);
		box(col, y, 202, getToBlock, v -> getToBlock = v);
		label("§8block, e.g. crafting_table, chest, furnace", col, y + 21);
		button("Go", col + 206, y, 44, () -> {
			if(!getToBlock.isBlank())
			{
				HumanBot.getTo(getToBlock.trim());
				onClose();
			}
		});
		y += 36;

		// one-click jobs
		int bw = (panelW - 16 - 12) / 4;
		button("Chop trees", x, y, bw, () -> {
			HumanBot.chop(0);
			onClose();
		});
		button("Farm", x + (bw + 4), y, bw, () -> {
			HumanBot.farm(null);
			onClose();
		});
		button("Explore", x + (bw + 4) * 2, y, bw, () -> {
			HumanBot.explore();
			onClose();
		});
		button("Mine ores", x + (bw + 4) * 3, y, bw, () -> {
			HumanBot.mine(List.of(), 0);
			onClose();
		});
		y += 24;

		button("§cSTOP", x, y, bw * 2 + 4, HumanBot::stop);
		button(mm.isPaused() ? "§aResume" : "Pause (J)", x + (bw + 4) * 2, y,
			bw * 2 + 4, () -> {
				HumanBot.togglePause();
				rebuildWidgets();
			});
	}

	// ----------------------------------------------------------- automation

	private void buildAutomation(int y)
	{
		int x = left + 8;
		for(Module m : HumanBot.modules().toggles())
		{
			Button[] self = new Button[1];
			self[0] = Button.builder(toggleLabel(m), b -> {
				m.setEnabled(!m.isEnabled());
				self[0].setMessage(toggleLabel(m));
			}).bounds(x, y, 120, 18).build();
			addRenderableWidget(self[0]);
			label("§7" + m.description(), x + 126, y + 5);
			y += 22;
		}
	}

	// ------------------------------------------------------------ humanizer

	private List<Row> humanizerRows()
	{
		BotConfig c = BotConfig.get();
		List<Row> rows = new ArrayList<>();
		rows.add(new Row("Turn speed", () -> Math.round(c.turnSpeed) + "°/tick",
			() -> c.turnSpeed = clamp(c.turnSpeed - 2, 4, 60),
			() -> c.turnSpeed = clamp(c.turnSpeed + 2, 4, 60)));
		rows.add(new Row("Reaction time", () -> c.reactionMs + " ms",
			() -> c.reactionMs = (int)clamp(c.reactionMs - 20, 60, 800),
			() -> c.reactionMs = (int)clamp(c.reactionMs + 20, 60, 800)));
		rows.add(new Row("Aim shake", () -> pct(c.aimShake),
			() -> c.aimShake = clamp(c.aimShake - 0.05f, 0, 1),
			() -> c.aimShake = clamp(c.aimShake + 0.05f, 0, 1)));
		rows.add(new Row("Overshoot chance", () -> pct(c.overshootChance),
			() -> c.overshootChance = clamp(c.overshootChance - 0.05f, 0, 1),
			() -> c.overshootChance = clamp(c.overshootChance + 0.05f, 0, 1)));
		rows.add(new Row("Click sloppiness", () -> pct(c.clickSloppiness),
			() -> c.clickSloppiness = clamp(c.clickSloppiness - 0.05f, 0, 1),
			() -> c.clickSloppiness = clamp(c.clickSloppiness + 0.05f, 0, 1)));
		rows.add(toggleRow("Thinking pauses", () -> c.microPauses,
			v -> c.microPauses = v));
		rows.add(new Row("Eat below hunger", () -> c.eatBelowHunger + " / 20",
			() -> c.eatBelowHunger = (int)clamp(c.eatBelowHunger - 1, 1, 19),
			() -> c.eatBelowHunger = (int)clamp(c.eatBelowHunger + 1, 1, 19)));
		rows.add(new Row("Fight range", () -> c.fightRange + " blocks",
			() -> c.fightRange = (int)clamp(c.fightRange - 1, 3, 32),
			() -> c.fightRange = (int)clamp(c.fightRange + 1, 3, 32)));
		return rows;
	}

	// -------------------------------------------------------------- pathing

	private void buildPathing(int y)
	{
		BotConfig c = BotConfig.get();
		List<Row> rows = new ArrayList<>();
		rows.add(toggleRow("Dig through blocks", () -> c.allowBreakingForPaths,
			v -> c.allowBreakingForPaths = v));
		rows.add(toggleRow("Place blocks (bridge/tower)", () -> c.allowPlace,
			v -> c.allowPlace = v));
		rows.add(toggleRow("Parkour jumps", () -> c.allowParkour,
			v -> c.allowParkour = v));
		rows.add(toggleRow("Only visible ores", () -> c.onlyExposedOres,
			v -> c.onlyExposedOres = v));
		rows.add(new Row("Max fall", () -> c.maxFall + " blocks",
			() -> c.maxFall = (int)clamp(c.maxFall - 1, 1, 20),
			() -> c.maxFall = (int)clamp(c.maxFall + 1, 1, 20)));
		rows.add(toggleRow("Work in background", () -> c.backgroundMode,
			v -> c.backgroundMode = v));
		rows.add(toggleRow("Show HUD", () -> c.showHud, v -> c.showHud = v));
		rows.add(new Row("Search radius", () -> c.scanRadius + " blocks",
			() -> c.scanRadius = (int)clamp(c.scanRadius - 4, 8, 64),
			() -> c.scanRadius = (int)clamp(c.scanRadius + 4, 8, 64)));
		rows.add(new Row("Farm radius", () -> c.farmRadius + " blocks",
			() -> c.farmRadius = (int)clamp(c.farmRadius - 2, 2, 64),
			() -> c.farmRadius = (int)clamp(c.farmRadius + 2, 2, 64)));
		y = buildRows(y, rows);

		// throwaway blocks
		int x = left + 8;
		label("Blocks to build with: §7" + summary(c.throwawayBlocks), x,
			y + 4);
		y += 16;
		box(x, y, 200, throwawayInput, v -> throwawayInput = v);
		button("Add", x + 204, y, 40, () -> {
			String id = throwawayInput.trim().replace("minecraft:", "");
			if(!id.isEmpty() && !c.throwawayBlocks.contains(id))
				c.throwawayBlocks.add(id);
			BotConfig.save();
			rebuildWidgets();
		});
		button("Remove", x + 248, y, 54, () -> {
			c.throwawayBlocks.remove(throwawayInput.trim()
				.replace("minecraft:", ""));
			BotConfig.save();
			rebuildWidgets();
		});
	}

	// ------------------------------------------------------------ waypoints

	private void buildWaypoints(int y)
	{
		int x = left + 8;
		label("Name", x, y + 6);
		box(x + 36, y, 150, wpName, v -> wpName = v);
		button("Save here", x + 190, y, 70, () -> {
			String n = wpName.trim();
			if(n.isEmpty())
				n = "wp" + (Waypoints.here().size() + 1);
			HumanBot.saveWaypoint(n.replace(' ', '_'));
			wpName = "";
			rebuildWidgets();
		});
		y += 28;

		List<Waypoint> list = Waypoints.here();
		int perPage = Math.max(1, (top + panelH - 56 - y) / 22);
		int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
		wpPage = Math.min(wpPage, pages - 1);
		if(list.isEmpty())
			label("§7No waypoints in this dimension yet.", x, y + 6);
		for(int i = wpPage * perPage; i < Math.min(list.size(),
			(wpPage + 1) * perPage); i++)
		{
			Waypoint w = list.get(i);
			label(w.name + " §7" + w.x + " " + w.y + " " + w.z, x, y + 6);
			button("Go", left + panelW - 118, y, 50, () -> {
				HumanBot.gotoWaypoint(w.name);
				onClose();
			});
			button("§cDelete", left + panelW - 64, y, 56, () -> {
				Waypoints.delete(w.name);
				rebuildWidgets();
			});
			y += 22;
		}
		if(pages > 1)
		{
			int by = top + panelH - 50;
			button("<", x, by, 20, () -> {
				wpPage = Math.max(0, wpPage - 1);
				rebuildWidgets();
			});
			label((wpPage + 1) + "/" + pages, x + 26, by + 6);
			button(">", x + 50, by, 20, () -> {
				wpPage = Math.min(pages - 1, wpPage + 1);
				rebuildWidgets();
			});
		}
	}

	// ================================================================ widgets

	/** -/+ rows in two columns. Returns the y below them. */
	private int buildRows(int y, List<Row> rows)
	{
		int colW = (panelW - 16) / 2;
		for(int i = 0; i < rows.size(); i++)
		{
			Row r = rows.get(i);
			int x = left + 8 + (i % 2) * colW;
			int ry = y + (i / 2) * 22;
			int right = x + colW - 8;
			labels.add(new Label(null, x, ry + 5, i)); // value drawn live
			button("-", right - 42, ry, 20, () -> {
				r.minus().run();
				BotConfig.save();
			});
			button("+", right - 20, ry, 20, () -> {
				r.plus().run();
				BotConfig.save();
			});
		}
		currentRows = rows;
		return y + ((rows.size() + 1) / 2) * 22 + 4;
	}

	private List<Row> currentRows = List.of();

	private static Row toggleRow(String name, Supplier<Boolean> get,
		Consumer<Boolean> set)
	{
		Runnable flip = () -> set.accept(!get.get());
		return new Row(name, () -> get.get() ? "§aon" : "§coff", flip,
			flip);
	}

	private void label(String text, int x, int y)
	{
		labels.add(new Label(text, x, y, 0xFFFFFFFF));
	}

	private void button(String text, int x, int y, int w, Runnable action)
	{
		addRenderableWidget(Button.builder(Component.literal(text), b -> action
			.run()).bounds(x, y, w, 18).build());
	}

	private void box(int x, int y, int w, String value, Consumer<String> onChange)
	{
		EditBox e = new EditBox(font, x, y, w, 18, Component.literal(""));
		e.setValue(value);
		e.setResponder(onChange);
		addRenderableWidget(e);
	}

	// ================================================================ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX,
		int mouseY, float partialTicks)
	{
		g.fill(0, 0, width, height, 0x90000000);
		g.fill(left, top, left + panelW, top + panelH, 0xF0151A21);
		g.fill(left, top, left + panelW, top + 16, 0xFF1F2A36);
		g.text(font, "§lHumanBot", left + 6, top + 4, 0xFF55DDFF, true);

		// what it's doing right now
		ModuleManager mm = HumanBot.modules();
		Process p = mm.runningProcess();
		String now = mm.isPaused() ? "§epaused"
			: p == null ? "§7idle" : "§a" + p.name() + " §7"
				+ p.status();
		String nowLine = "Now: " + now;
		int maxW = panelW - 90;
		while(font.width(nowLine) > maxW && nowLine.length() > 8)
			nowLine = nowLine.substring(0, nowLine.length() - 2);
		g.text(font, nowLine, left + 6, top + panelH - 18, 0xFFDDDDDD, true);

		for(Label l : labels)
		{
			if(l.text() == null)
			{
				Row r = currentRows.get(l.color());
				g.text(font, r.label() + ": §e" + r.value().get(), l.x(),
					l.y(), 0xFFDDDDDD, true);
			}else
				g.text(font, l.text(), l.x(), l.y(), l.color(), true);
		}

		// let the game draw the buttons and text boxes on top
		super.extractRenderState(g, mouseX, mouseY, partialTicks);
	}

	@Override
	public void onClose()
	{
		BotConfig.save();
		minecraft.gui.setScreen(null);
	}

	// ================================================================ utils

	private static Component toggleLabel(Module m)
	{
		return Component.literal(m.name() + ": "
			+ (m.isEnabled() ? "§aON" : "§cOFF"));
	}

	private static String summary(List<String> ids)
	{
		if(ids.size() <= 4)
			return String.join(", ", ids);
		return String.join(", ", ids.subList(0, 4)) + " +" + (ids.size() - 4)
			+ " more";
	}

	private static float clamp(float v, float min, float max)
	{
		return Math.max(min, Math.min(max, v));
	}

	private static String pct(float f)
	{
		return Math.round(f * 100) + "%";
	}
}
