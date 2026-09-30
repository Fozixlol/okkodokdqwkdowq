package dev.humanbot;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import dev.humanbot.BotConfig.Waypoint;
import dev.humanbot.gui.BotScreen;
import dev.humanbot.module.Module;
import dev.humanbot.module.ModuleManager;
import dev.humanbot.path.PathFinder;
import dev.humanbot.util.Compat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class HumanBot implements ClientModInitializer
{
	public static final String MOD_ID = "humanbot";
	public static final Logger LOGGER = LoggerFactory.getLogger("HumanBot");

	private static ModuleManager modules;
	private static KeyMapping menuKey;
	private static KeyMapping pauseKey;
	private static boolean openMenuNextTick;

	@Override
	public void onInitializeClient()
	{
		BotConfig.load();
		modules = new ModuleManager();

		KeyMapping.Category cat = KeyMapping.Category
			.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.humanbot.menu", Compat.keyType(), Compat.keyRightShift(), cat));
		pauseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.humanbot.pause", Compat.keyType(), Compat.keyJ(), cat));

		ClientTickEvents.END_CLIENT_TICK.register(HumanBot::onTick);
		HudElementRegistry.addLast(
			Identifier.fromNamespaceAndPath(MOD_ID, "status"),
			(graphics, delta) -> StatusHud.draw(graphics));
		ClientCommandRegistrationCallback.EVENT.register(
			(dispatcher, buildContext) -> registerCommands(dispatcher));

		LOGGER.info("HumanBot loaded");
	}

	private static void onTick(Minecraft mc)
	{
		if(openMenuNextTick && mc.player != null)
		{
			openMenuNextTick = false;
			mc.gui.setScreen(new BotScreen());
		}
		while(menuKey.consumeClick())
			if(mc.gui.screen() == null)
				mc.gui.setScreen(new BotScreen());
		while(pauseKey.consumeClick())
			if(mc.player != null)
				togglePause();

		modules.tick(mc);
	}

	public static ModuleManager modules()
	{
		return modules;
	}

	public static void openMenu()
	{
		openMenuNextTick = true;
	}

	public static void togglePause()
	{
		modules.setPaused(!modules.isPaused());
		chat(modules.isPaused() ? "Paused - you have control (J to resume)"
			: "Resumed");
	}

	public static void chat(String msg)
	{
		Minecraft mc = Minecraft.getInstance();
		if(mc.player != null)
			mc.gui.hud.getChat().addClientSystemMessage(
				Component.literal("§b[HumanBot]§r " + msg));
	}

	// ================================================================ actions
	// (shared by the commands and the menu)

	public static void goTo(int x, Integer y, int z)
	{
		boolean xzOnly = y == null;
		modules.gotoProcess
			.go(PathFinder.at(new BlockPos(x, xzOnly ? 0 : y, z), xzOnly));
		chat("Heading to " + x + (xzOnly ? "" : " " + y) + " " + z);
	}

	public static void goToY(int y)
	{
		modules.gotoProcess.go(PathFinder.yLevel(y));
		chat("Heading to y=" + y);
	}

	public static void mine(List<String> blocks, int count)
	{
		modules.mine.setEnabled(false);
		modules.mine.setTargets(blocks);
		modules.mine.setGoalCount(count);
		modules.mine.setEnabled(true);
		chat("Mining " + (blocks.isEmpty() ? "ores" : String.join(", ", blocks))
			+ (count > 0 ? " (" + count + ")" : ""));
	}

	public static void chop(int count)
	{
		modules.chop.setEnabled(false);
		modules.chop.setGoalCount(count);
		modules.chop.setEnabled(true);
		chat("Chopping trees" + (count > 0 ? " (" + count + " logs)" : ""));
	}

	public static void follow(String name)
	{
		boolean anyone = name == null || name.isBlank();
		modules.follow.setEnabled(false);
		modules.follow.setTarget(anyone ? null : name);
		modules.follow.setEnabled(true);
		chat("Following " + (anyone ? "nearest player" : name));
	}

	public static void farm(Integer radius)
	{
		if(radius != null)
		{
			BotConfig.get().farmRadius = radius;
			BotConfig.save();
		}
		modules.farm.setEnabled(false);
		modules.farm.setEnabled(true);
		chat("Farming within " + BotConfig.get().farmRadius + " blocks");
	}

	public static void fight()
	{
		modules.fight.setEnabled(false);
		modules.fight.setEnabled(true);
		chat("Hunting hostile mobs nearby");
	}

	public static void explore()
	{
		modules.explore.setEnabled(true);
		chat("Exploring");
	}

	public static void getTo(String block)
	{
		modules.getTo.setEnabled(false);
		modules.getTo.setBlock(block);
		modules.getTo.setEnabled(true);
		chat("Going to nearest " + modules.getTo.block());
	}

	public static void stop()
	{
		modules.stopAll();
		chat("Stopped");
	}

	public static void saveWaypoint(String name)
	{
		Minecraft mc = Minecraft.getInstance();
		Waypoints.save(name, mc.player.blockPosition());
		chat("Saved waypoint " + name + " at "
			+ mc.player.blockPosition().toShortString());
	}

	public static void gotoWaypoint(String name)
	{
		Waypoint w = Waypoints.get(name);
		if(w == null)
		{
			chat("No waypoint called " + name + " in this dimension");
			return;
		}
		modules.gotoProcess.go(PathFinder.at(Waypoints.pos(w), false));
		chat("Heading to waypoint " + w.name);
	}

	// ================================================================ commands

	private static void registerCommands(
		CommandDispatcher<FabricClientCommandSource> d)
	{
		d.register(build("humanbot"));
		d.register(build("hb"));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> build(
		String root)
	{
		return ClientCommands.literal(root).executes(ctx -> {
			openMenu();
			return 1;
		}).then(ClientCommands.literal("help").executes(ctx -> help()))
			.then(ClientCommands.literal("menu").executes(ctx -> {
				openMenu();
				return 1;
			}))
			// goto
			.then(ClientCommands.literal("goto")
				.then(ClientCommands.literal("y")
					.then(ClientCommands
						.argument("level", IntegerArgumentType.integer())
						.executes(ctx -> {
							goToY(IntegerArgumentType.getInteger(ctx, "level"));
							return 1;
						})))
				.then(ClientCommands.argument("x", IntegerArgumentType.integer())
					.then(ClientCommands
						.argument("a", IntegerArgumentType.integer())
						.executes(ctx -> {
							goTo(IntegerArgumentType.getInteger(ctx, "x"), null,
								IntegerArgumentType.getInteger(ctx, "a"));
							return 1;
						}).then(ClientCommands
							.argument("b", IntegerArgumentType.integer())
							.executes(ctx -> {
								goTo(IntegerArgumentType.getInteger(ctx, "x"),
									IntegerArgumentType.getInteger(ctx, "a"),
									IntegerArgumentType.getInteger(ctx, "b"));
								return 1;
							})))))
			// mine [blocks...] [count]
			.then(ClientCommands.literal("mine").executes(ctx -> {
				mine(List.of(), 0);
				return 1;
			}).then(ClientCommands
				.argument("blocks", StringArgumentType.greedyString())
				.executes(ctx -> {
					List<String> blocks = new ArrayList<>();
					int count = 0;
					for(String part : StringArgumentType
						.getString(ctx, "blocks").trim().split("\\s+"))
					{
						if(part.matches("\\d+"))
							count = Integer.parseInt(part);
						else if(!part.isEmpty())
							blocks.add(part.replace("minecraft:", ""));
					}
					mine(blocks, count);
					return 1;
				})))
			.then(ClientCommands.literal("chop").executes(ctx -> {
				chop(0);
				return 1;
			}).then(ClientCommands
				.argument("count", IntegerArgumentType.integer(1))
				.executes(ctx -> {
					chop(IntegerArgumentType.getInteger(ctx, "count"));
					return 1;
				})))
			.then(ClientCommands.literal("follow").executes(ctx -> {
				follow(null);
				return 1;
			}).then(ClientCommands
				.argument("player", StringArgumentType.word()).executes(ctx -> {
					follow(StringArgumentType.getString(ctx, "player"));
					return 1;
				})))
			.then(ClientCommands.literal("farm").executes(ctx -> {
				farm(null);
				return 1;
			}).then(ClientCommands
				.argument("radius", IntegerArgumentType.integer(2, 64))
				.executes(ctx -> {
					farm(IntegerArgumentType.getInteger(ctx, "radius"));
					return 1;
				})))
			.then(ClientCommands.literal("fight").executes(ctx -> {
				fight();
				return 1;
			}))
			.then(ClientCommands.literal("explore").executes(ctx -> {
				explore();
				return 1;
			}))
			.then(ClientCommands.literal("getto").then(ClientCommands
				.argument("block", StringArgumentType.word()).executes(ctx -> {
					getTo(StringArgumentType.getString(ctx, "block"));
					return 1;
				})))
			// waypoints
			.then(ClientCommands.literal("wp")
				.then(ClientCommands.literal("list").executes(ctx -> {
					List<Waypoint> here = Waypoints.here();
					if(here.isEmpty())
						chat("No waypoints in this dimension yet");
					for(Waypoint w : here)
						chat(w.name + ": " + w.x + " " + w.y + " " + w.z);
					return 1;
				}))
				.then(ClientCommands.literal("save").then(ClientCommands
					.argument("name", StringArgumentType.word()).executes(ctx -> {
						saveWaypoint(StringArgumentType.getString(ctx, "name"));
						return 1;
					})))
				.then(ClientCommands.literal("goto").then(ClientCommands
					.argument("name", StringArgumentType.word()).executes(ctx -> {
						gotoWaypoint(StringArgumentType.getString(ctx, "name"));
						return 1;
					})))
				.then(ClientCommands.literal("del").then(ClientCommands
					.argument("name", StringArgumentType.word()).executes(ctx -> {
						String n = StringArgumentType.getString(ctx, "name");
						chat(Waypoints.delete(n) ? "Deleted " + n
							: "No waypoint called " + n);
						return 1;
					}))))
			.then(ClientCommands.literal("stop").executes(ctx -> {
				stop();
				return 1;
			}))
			.then(ClientCommands.literal("pause").executes(ctx -> {
				togglePause();
				return 1;
			}))
			.then(ClientCommands.literal("toggle").then(ClientCommands
				.argument("module", StringArgumentType.word()).executes(ctx -> {
					String name = StringArgumentType.getString(ctx, "module");
					Module m = modules.get(name);
					if(m == null)
					{
						chat("No module called " + name);
						return 0;
					}
					m.setEnabled(!m.isEnabled());
					chat(m.name() + (m.isEnabled() ? " on" : " off"));
					return 1;
				})))
			.then(ClientCommands.literal("modules").executes(ctx -> {
				StringBuilder sb = new StringBuilder();
				for(Module m : modules.all())
					sb.append(m.isEnabled() ? "§a" : "§c")
						.append(m.name()).append("§r ");
				chat(sb.toString());
				return 1;
			}));
	}

	private static int help()
	{
		String[] lines = {"§lHumanBot commands§r (/hb or /humanbot):",
			"/hb §7- open the menu (or press Right Shift)",
			"/hb goto <x> <z> | <x> <y> <z> | y <level>",
			"/hb mine [blocks...] [count] §7- e.g. /hb mine diamond_ore 5",
			"/hb chop [count]   /hb farm [radius]   /hb explore   /hb fight",
			"/hb follow [player]   /hb getto <block>",
			"/hb wp save|goto|del <name>   /hb wp list",
			"/hb stop   /hb pause §7(or J)",
			"/hb toggle <module>   /hb modules"};
		for(String l : lines)
			chat(l);
		return 1;
	}
}
