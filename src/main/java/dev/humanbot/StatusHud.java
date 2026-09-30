package dev.humanbot;

import dev.humanbot.module.Module;
import dev.humanbot.module.ModuleManager;
import dev.humanbot.module.Process;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small status readout in the top-left corner. */
final class StatusHud
{
	private StatusHud()
	{}

	static void draw(GuiGraphicsExtractor g)
	{
		Minecraft mc = Minecraft.getInstance();
		ModuleManager mm = HumanBot.modules();
		if(mc.player == null || mm == null || !BotConfig.get().showHud)
			return;

		Font font = mc.font;
		String line1;
		String line2 = null;
		if(mm.isPaused())
			line1 = "§eHumanBot paused§r §7(J to resume)";
		else
		{
			Process proc = mm.runningProcess();
			Module a = mm.active();
			if(proc != null)
			{
				line1 = "§bHumanBot§r » " + proc.name() + " §7"
					+ proc.status();
				if(a != null && a != proc)
					line2 = "§6interrupted by " + a.name() + "§7 "
						+ a.status();
			}else if(a != null && !a.status().isEmpty())
				line1 = "§bHumanBot§r » " + a.name() + " §7"
					+ a.status();
			else
				line1 = "§bHumanBot§r idle §8(Right Shift for menu)";
		}

		int w = font.width(line1);
		if(line2 != null)
			w = Math.max(w, font.width(line2));
		int h = line2 == null ? 12 : 22;
		g.fill(2, 2, 8 + w, 2 + h, 0x80000000);
		g.text(font, line1, 5, 4, 0xFFFFFFFF, true);
		if(line2 != null)
			g.text(font, line2, 5, 14, 0xFFFFFFFF, true);
	}
}
