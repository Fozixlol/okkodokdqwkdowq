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

	/** Hover text shown in the footer for a screen rectangle. */
	private record Hint(int x, int y, int w, int h, String text)
	{}

	private static final int BG = 0xF0141A22, HEADER = 0xFF1B2733,
		ACCENT = 0xFF3FB6FF, CARD = 0xFF19212B, EDGE = 0xFF2B3948,
		DIM = 0xFF8A97A6, TEXT = 0xFFE4EAF0;

	// remembered between openings
	private static Tab tab = Tab.TASKS;
	private static String gx = "", gy = "", gz = "", mineBlocks = "",
		mineCount = "", followName = "", getToBlock = "crafting_table",
		wpName = "", throwawayInput = "";
	private static int wpPage;

	private final List<Label> labels = new ArrayList<>();
	private final List<Hint> hints = new ArrayList<>();
	private int left, top, panelW, panelH;
	/** Left edge and width of the area right of the tab column. */
	private int cx, cw;

	public BotScreen()
	{
		super(Component.literal("HumanBot"));
	}

	// ================================================================ layout

	@Override
	public void init()
	{
		labels.clear();
		hints.clear();
		panelW = Math.min(480, width - 12);
		panelH = Math.min(300, height - 12);
		left = (width - panelW) / 2;
		top = (height - panelH) / 2;
		cx = left + 96;
		cw = panelW - 96 - 8;

		// tab column
		Tab[] tabs = Tab.values();
		for(int i = 0; i < tabs.length; i++)
		{
			Tab t = tabs[i];
			addRenderableWidget(Button.builder(
				Component.literal(t == tab ? "§e" + t.title : t.title),
				btn -> {
					tab = t;
					rebuildWidgets();
				}).bounds(left + 8, top + 28 + i * 24, 78, 20).build());
		}

		int y = top + 28;
		switch(tab)
		{
			case TASKS -> buildTasks(y);
			case AUTOMATION -> buildAutomation(y);
			case HUMANIZER -> buildRows(y, humanizerRows());
			case PATHING -> buildPathing(y);
			case WAYPOINTS -> buildWaypoints(y);
		}

		// footer: always there, on every tab
		int fy = top + panelH - 24;
		ModuleManager mm = HumanBot.modules();
		button("§cSTOP", left + 8, fy, 78, "Stop the current job", () -> {
			HumanBot.stop();
			rebuildWidgets();
		});
		button(mm.isPaused() ? "§aResume" : "Pause (J)", cx, fy, 80,
			"Take the controls back (J), press again to resume", () -> {
				HumanBot.togglePause();
				rebuildWidgets();
			});
		button("Done", left + panelW - 68, fy, 60, "Close the menu",
			this::onClose);
	}

	// ---------------------------------------------------------------- tasks

	private void buildTasks(int y)
	{
		int x = cx;
		int fx = cx + 46; // fields start here

		// go to
		label("Go to", x, y + 5);
		box(fx, y, 46, "x", gx, v -> gx = v);
		box(fx + 49, y, 46, "y", gy, v -> gy = v);
		box(fx + 98, y, 46, "z", gz, v -> gz = v);
		button("Here", fx + 148, y, 40, "Fill in where you stand", () -> {
			BlockPos p = minecraft.player.blockPosition();
			gx = "" + p.getX();
			gy = "" + p.getY();
			gz = "" + p.getZ();
			rebuildWidgets();
		});
		button("Go", fx + 191, y, Math.max(34, cx + cw - (fx + 191)),
			"Walk there. y is optional (empty = any height)", () -> {
				try
				{
					int ix = Integer.parseInt(gx.trim());
					int iz = Integer.parseInt(gz.trim());
					Integer iy =
						gy.isBlank() ? null : Integer.parseInt(gy.trim());
					HumanBot.goTo(ix, iy, iz);
					onClose();
				}catch(NumberFormatException e)
				{
					HumanBot.chat("Enter numbers for x and z (y is optional)");
				}
			});
		y += 22;

		// get to
		label("Get to", x, y + 5);
		box(fx, y, cw - 46 - 40, "block, e.g. crafting_table", getToBlock,
			v -> getToBlock = v);
		button("Go", cx + cw - 36, y, 36,
			"Walk to the nearest one and open it", () -> {
				if(!getToBlock.isBlank())
				{
					HumanBot.getTo(getToBlock.trim());
					onClose();
				}
			});
		y += 22;

		// mine
		label("Mine", x, y + 5);
		box(fx, y, cw - 46 - 40 - 42, "blocks (empty = all ores)", mineBlocks,
			v -> mineBlocks = v);
		box(cx + cw - 76, y, 36, "how many", mineCount, v -> mineCount = v);
		button("Start", cx + cw - 36, y, 36, "Mine until you stop it, or "
			+ "until the count is reached", () -> {
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
		y += 22;

		// ore presets
		String[][] presets = {{"Diamond", "diamond_ore deepslate_diamond_ore"},
			{"Iron", "iron_ore deepslate_iron_ore"},
			{"Coal", "coal_ore deepslate_coal_ore"},
			{"Gold", "gold_ore deepslate_gold_ore"},
			{"Copper", "copper_ore deepslate_copper_ore"},
			{"Redstone", "redstone_ore deepslate_redstone_ore"},
			{"Lapis", "lapis_ore deepslate_lapis_ore"},
			{"Emerald", "emerald_ore deepslate_emerald_ore"}};
		int pw = (cw - 46 - 3 * 3) / 4;
		for(int i = 0; i < presets.length; i++)
		{
			String[] pr = presets[i];
			button(pr[0], fx + (i % 4) * (pw + 3), y + (i / 4) * 20, pw,
				"Fill in: " + pr[1], () -> {
					mineBlocks = pr[1];
					rebuildWidgets();
				});
		}
		y += 44;

		// follow
		label("Follow", x, y + 5);
		box(fx, y, cw - 46 - 40, "player name (empty = nearest)", followName,
			v -> followName = v);
		button("Go", cx + cw - 36, y, 36, "Follow them around", () -> {
			HumanBot.follow(followName.trim());
			onClose();
		});
		y += 26;

		// one-click jobs
		int bw = (cw - 3 * 4) / 4;
		button("§aChop trees", x, y, bw, "Chop down nearby trees", () -> {
			HumanBot.chop(0);
			onClose();
		});
		button("§aFarm", x + (bw + 4), y, bw,
			"Harvest ripe crops around you and replant", () -> {
				HumanBot.farm(null);
				onClose();
			});
		button("§cFight mobs", x + (bw + 4) * 2, y, bw,
			"Hunt and kill hostile mobs nearby", () -> {
				HumanBot.fight();
				onClose();
			});
		button("§bExplore", x + (bw + 4) * 3, y, bw,
			"Wander outwards to see new chunks", () -> {
				HumanBot.explore();
				onClose();
			});
	}

	// ----------------------------------------------------------- automation

	private void buildAutomation(int y)
	{
		label("§7Switch on what should happen by itself, alongside any job.",
			cx, y + 2);
		y += 16;
		int colW = (cw - 4) / 2;
		int i = 0;
		for(Module m : HumanBot.modules().toggles())
		{
			Button[] self = new Button[1];
			int bx = cx + (i % 2) * (colW + 4);
			int by = y + (i / 2) * 22;
			self[0] = Button.builder(toggleLabel(m), b -> {
				m.setEnabled(!m.isEnabled());
				self[0].setMessage(toggleLabel(m));
			}).bounds(bx, by, colW, 20).build();
			addRenderableWidget(self[0]);
			hints.add(new Hint(bx, by, colW, 20, m.description()));
			i++;
		}
		y += ((i + 1) / 2) * 22 + 6;

		// the two numbers that matter most for these
		BotConfig c = BotConfig.get();
		List<Row> rows = new ArrayList<>();
		rows.add(new Row("Fight range", () -> c.fightRange + " blocks",
			() -> c.fightRange = (int)clamp(c.fightRange - 1, 3, 32),
			() -> c.fightRange = (int)clamp(c.fightRange + 1, 3, 32)));
		rows.add(new Row("Eat below hunger", () -> c.eatBelowHunger + " / 20",
			() -> c.eatBelowHunger = (int)clamp(c.eatBelowHunger - 1, 1, 19),
			() -> c.eatBelowHunger = (int)clamp(c.eatBelowHunger + 1, 1, 19)));
		buildRows(y, rows);
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
		int x = cx;
		label("Blocks to build with: §7" + summary(c.throwawayBlocks), x,
			y + 4);
		y += 16;
		box(x, y, 180, "block id, e.g. cobblestone", throwawayInput,
			v -> throwawayInput = v);
		button("Add", x + 184, y, 40, "Allow building with this block", () -> {
			String id = throwawayInput.trim().replace("minecraft:", "");
			if(!id.isEmpty() && !c.throwawayBlocks.contains(id))
				c.throwawayBlocks.add(id);
			BotConfig.save();
			rebuildWidgets();
		});
		button("Remove", x + 228, y, 54, "Don't build with this block", () -> {
			c.throwawayBlocks.remove(throwawayInput.trim()
				.replace("minecraft:", ""));
			BotConfig.save();
			rebuildWidgets();
		});
	}

	// ------------------------------------------------------------ waypoints

	private void buildWaypoints(int y)
	{
		int x = cx;
		label("Name", x, y + 6);
		box(x + 36, y, 150, "name (optional)", wpName, v -> wpName = v);
		button("Save here", x + 190, y, 70, "Remember where you stand", () -> {
			String n = wpName.trim();
			if(n.isEmpty())
				n = "wp" + (Waypoints.here().size() + 1);
			HumanBot.saveWaypoint(n.replace(' ', '_'));
			wpName = "";
			rebuildWidgets();
		});
		y += 28;

		List<Waypoint> list = Waypoints.here();
		int perPage = Math.max(1, (top + panelH - 34 - y) / 22);
		int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
		wpPage = Math.min(wpPage, pages - 1);
		if(list.isEmpty())
			label("§7No waypoints in this dimension yet.", x, y + 6);
		for(int i = wpPage * perPage; i < Math.min(list.size(),
			(wpPage + 1) * perPage); i++)
		{
			Waypoint w = list.get(i);
			label(w.name + " §7" + w.x + " " + w.y + " " + w.z, x, y + 6);
			button("Go", cx + cw - 110, y, 50, "Walk to " + w.name, () -> {
				HumanBot.gotoWaypoint(w.name);
				onClose();
			});
			button("§cDelete", cx + cw - 56, y, 56, "Forget " + w.name, () -> {
				Waypoints.delete(w.name);
				rebuildWidgets();
			});
			y += 22;
		}
		if(pages > 1)
		{
			int by = top + panelH - 50;
			button("<", x, by, 20, "Previous page", () -> {
				wpPage = Math.max(0, wpPage - 1);
				rebuildWidgets();
			});
			label((wpPage + 1) + "/" + pages, x + 26, by + 6);
			button(">", x + 50, by, 20, "Next page", () -> {
				wpPage = Math.min(pages - 1, wpPage + 1);
				rebuildWidgets();
			});
		}
	}

	// ================================================================ widgets

	/** -/+ rows in two columns. Returns the y below them. */
	private int buildRows(int y, List<Row> rows)
	{
		int colW = (cw - 6) / 2;
		for(int i = 0; i < rows.size(); i++)
		{
			Row r = rows.get(i);
			int x = cx + (i % 2) * (colW + 6);
			int ry = y + (i / 2) * 22;
			int right = x + colW;
			labels.add(new Label(null, x, ry + 6, i)); // value drawn live
			button("-", right - 44, ry, 20, null, () -> {
				r.minus().run();
				BotConfig.save();
			});
			button("+", right - 22, ry, 22, null, () -> {
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
		labels.add(new Label(text, x, y, TEXT));
	}

	private void button(String text, int x, int y, int w, String hint,
		Runnable action)
	{
		addRenderableWidget(Button.builder(Component.literal(text), b -> action
			.run()).bounds(x, y, w, 20).build());
		if(hint != null)
			hints.add(new Hint(x, y, w, 20, hint));
	}

	private void box(int x, int y, int w, String placeholder, String value,
		Consumer<String> onChange)
	{
		EditBox e = new EditBox(font, x, y, w, 20, Component.literal(""));
		e.setHint(Component.literal("§8" + placeholder));
		e.setMaxLength(200);
		e.setValue(value);
		e.setResponder(onChange);
		addRenderableWidget(e);
		hints.add(new Hint(x, y, w, 20, placeholder));
	}

	// ================================================================ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX,
		int mouseY, float partialTicks)
	{
		g.fill(0, 0, width, height, 0x90000000);
		// panel with a border
		g.fill(left - 1, top - 1, left + panelW + 1, top + panelH + 1, EDGE);
		g.fill(left, top, left + panelW, top + panelH, BG);
		// header with the live status
		g.fill(left, top, left + panelW, top + 20, HEADER);
		g.fill(left, top + 20, left + panelW, top + 21, ACCENT);
		g.text(font, "§lHumanBot", left + 8, top + 6, ACCENT, true);

		ModuleManager mm = HumanBot.modules();
		Process p = mm.runningProcess();
		String now = mm.isPaused() ? "§epaused - you have control"
			: p == null ? "§7idle" : "§a" + p.name() + " §7" + p.status();
		int maxW = panelW - 100;
		while(font.width(now) > maxW && now.length() > 8)
			now = now.substring(0, now.length() - 2);
		g.text(font, now, left + panelW - 8 - font.width(now), top + 6, TEXT,
			true);

		// tab column and the selected tab's accent bar
		g.fill(left + 4, top + 24, left + 90, top + panelH - 30, CARD);
		g.fill(left + 4, top + 28 + tab.ordinal() * 24,
			left + 6, top + 48 + tab.ordinal() * 24, ACCENT);
		// content card
		g.fill(cx - 4, top + 24, left + panelW - 4, top + panelH - 30, CARD);

		for(Label l : labels)
		{
			if(l.text() == null)
			{
				Row r = currentRows.get(l.color());
				g.text(font, r.label() + ": §e" + r.value().get(), l.x(),
					l.y(), TEXT, true);
			}else
				g.text(font, l.text(), l.x(), l.y(), l.color(), true);
		}

		// let the game draw the buttons and text boxes on top
		super.extractRenderState(g, mouseX, mouseY, partialTicks);

		// hover hint in the footer
		String hint = "";
		for(Hint h : hints)
			if(mouseX >= h.x() && mouseX < h.x() + h.w() && mouseY >= h.y()
				&& mouseY < h.y() + h.h())
				hint = h.text();
		if(!hint.isEmpty())
		{
			int hx = cx + 88;
			int room = left + panelW - 74 - hx;
			while(font.width(hint) > room && hint.length() > 4)
				hint = hint.substring(0, hint.length() - 2);
			g.text(font, "§7" + hint, hx, top + panelH - 18, DIM, true);
		}
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
