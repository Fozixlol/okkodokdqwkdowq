package dev.humanbot.util;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import dev.humanbot.BotConfig;
import dev.humanbot.HumanBot;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Everything the bot does to the game goes through here.
 * <p>
 * Movement uses the normal key mappings. Breaking and placing talk to the
 * game's interaction manager directly (the same calls vanilla makes when
 * you click), so they keep working while the window is in the background.
 * Nothing is sent that a normal click wouldn't send.
 */
public final class InputUtil
{
	private static final Minecraft MC = Minecraft.getInstance();
	private static final Set<KeyMapping> HELD = new HashSet<>();

	private static Field clickCountField;
	private static Field isDestroyingField;
	private static Field pauseOnLostFocusField;
	private static boolean reflectionDone;

	// mining state (applied once per tick in endTick)
	private static boolean mineRequested;
	private static boolean wasMining;

	// background mode
	private static Boolean savedPauseOnLostFocus;

	private InputUtil()
	{}

	public static Options opt()
	{
		return MC.options;
	}

	// ------------------------------------------------------------ keys

	public static void hold(KeyMapping key, boolean down)
	{
		key.setDown(down);
		if(down)
			HELD.add(key);
		else
			HELD.remove(key);
	}

	/** Release every key this mod is holding and stop mining. */
	public static void releaseAll()
	{
		for(KeyMapping k : HELD)
			k.setDown(false);
		HELD.clear();
		mineRequested = false;
	}

	public static void releaseMovement()
	{
		Options o = opt();
		for(KeyMapping k : new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft,
			o.keyRight, o.keyJump, o.keySprint, o.keyShift})
			if(HELD.contains(k))
				hold(k, false);
	}

	/**
	 * Walk towards a direction using whichever of W/A/S/D gets there from
	 * where the camera is actually pointing (the same idea as Baritone's
	 * moveTowardsWithoutRotation). While the aim is still turning, or the
	 * player glances away, the body keeps going the right way instead of
	 * drifting into a wall.
	 *
	 * @return true if W is the key being used (so sprinting makes sense)
	 */
	public static boolean moveTowards(float targetYaw, boolean allowSprint)
	{
		Options o = opt();
		float d = net.minecraft.util.Mth
			.wrapDegrees(targetYaw - MC.player.getYRot());
		boolean fwd = Math.abs(d) <= 67.5f;
		boolean back = Math.abs(d) >= 112.5f;
		boolean left = d <= -22.5f && d >= -157.5f;
		boolean right = d >= 22.5f && d <= 157.5f;
		hold(o.keyUp, fwd);
		hold(o.keyDown, back);
		hold(o.keyLeft, left);
		hold(o.keyRight, right);
		boolean sprint = allowSprint && fwd && Math.abs(d) < 30f;
		hold(o.keySprint, sprint);
		return fwd;
	}
	
	public static void stopWalking()
	{
		Options o = opt();
		hold(o.keyUp, false);
		hold(o.keyDown, false);
		hold(o.keyLeft, false);
		hold(o.keyRight, false);
		hold(o.keySprint, false);
	}
	
	// ------------------------------------------------------------ mining

	/**
	 * Keep mining whatever block the crosshair is on. Must be requested
	 * every tick, like holding the mouse button.
	 */
	public static void mine(boolean want)
	{
		mineRequested = want;
	}

	public static boolean isMining()
	{
		return wasMining;
	}

	/** Called once per tick after all modules ran. */
	public static void endTick()
	{
		lookupReflection();
		MultiPlayerGameMode gm = MC.gameMode;
		if(gm == null || MC.player == null)
		{
			wasMining = false;
			return;
		}

		if(isDestroyingField == null)
		{
			// fallback: plain held left click (needs the window focused)
			hold(opt().keyAttack, mineRequested);
			wasMining = mineRequested;
			mineRequested = false;
			return;
		}

		HitResult hr = MC.hitResult;
		boolean onBlock = hr instanceof BlockHitResult
			&& hr.getType() == HitResult.Type.BLOCK;

		if(mineRequested && onBlock && !MC.player.isUsingItem())
		{
			BlockHitResult bhr = (BlockHitResult)hr;
			// restore the flag we cleared last tick (see below)
			setDestroying(gm, wasMining);
			if(gm.continueDestroyBlock(bhr.getBlockPos(), bhr.getDirection()))
				swing();
			wasMining = isDestroying(gm);
			// vanilla stops breaking every tick the attack key isn't held;
			// with the flag cleared that stop does nothing, so progress keeps
			// going even when the window isn't focused
			setDestroying(gm, false);
		}else if(wasMining)
		{
			setDestroying(gm, true);
			gm.stopDestroyBlock();
			wasMining = false;
		}
		mineRequested = false;
	}

	// ------------------------------------------------------------ clicks

	/** Right-click the block the crosshair is on. True if it did something. */
	public static boolean useOnLookedAtBlock()
	{
		if(!(MC.hitResult instanceof BlockHitResult bhr)
			|| MC.hitResult.getType() != HitResult.Type.BLOCK)
			return false;
		InteractionResult r =
			MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND, bhr);
		if(r.consumesAction())
		{
			MC.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT,
				false);
			return true;
		}
		return false;
	}

	/**
	 * One left click. When possible it's queued as a real click so vanilla
	 * decides what gets hit, exactly like a mouse would.
	 */
	public static void leftClick(Entity target)
	{
		lookupReflection();
		if(clickCountField != null)
			try
			{
				KeyMapping k = opt().keyAttack;
				clickCountField.setInt(k, clickCountField.getInt(k) + 1);
				return;
			}catch(Exception ignored)
			{}

		if(target != null)
			MC.gameMode.attack(MC.player, target);
		swing();
	}

	/**
	 * Hit a mob directly, the way a click on it does: attack, then swing.
	 * Doesn't depend on the window being focused or on the click queue.
	 */
	public static void attack(Entity target)
	{
		MC.gameMode.attack(MC.player, target);
		swing();
	}

	private static boolean punchPacketBroken;

	private static void swing()
	{
		MC.player.swing(InteractionHand.MAIN_HAND,
			MC.player.getMainHandItem().getAttackAnimation(), false);
		if(punchPacketBroken)
			return;
		try
		{
			// tells the server to show the arm swing, like vanilla does
			MC.player.connection.send(ServerboundPunchPacket.INSTANCE);
		}catch(LinkageError e)
		{
			punchPacketBroken = true;
			HumanBot.LOGGER.warn("HumanBot: swing packet unavailable", e);
		}
	}

	// ------------------------------------------------------------ hotbar

	public static int selectedSlot()
	{
		return MC.player.getInventory().getSelectedSlot();
	}

	public static void selectSlot(int slot)
	{
		MC.player.getInventory().setSelectedSlot(slot);
	}

	// ------------------------------------------------------------ focus

	/**
	 * While the bot is busy, stop the game from pausing when you alt-tab,
	 * so it can keep working in the background. Restored when idle.
	 */
	public static void setBackgroundMode(boolean busy)
	{
		lookupReflection();
		if(pauseOnLostFocusField == null)
			return;
		boolean want = busy && BotConfig.get().backgroundMode;
		try
		{
			if(want && savedPauseOnLostFocus == null)
			{
				savedPauseOnLostFocus =
					pauseOnLostFocusField.getBoolean(MC.options);
				pauseOnLostFocusField.setBoolean(MC.options, false);
			}else if(!want && savedPauseOnLostFocus != null)
			{
				pauseOnLostFocusField.setBoolean(MC.options,
					savedPauseOnLostFocus);
				savedPauseOnLostFocus = null;
			}
		}catch(Exception e)
		{
			pauseOnLostFocusField = null;
		}
	}

	// ------------------------------------------------------------ internals

	private static void setDestroying(MultiPlayerGameMode gm, boolean v)
	{
		if(isDestroyingField != null)
			try
			{
				isDestroyingField.setBoolean(gm, v);
			}catch(Exception ignored)
			{}
	}

	private static boolean isDestroying(MultiPlayerGameMode gm)
	{
		if(isDestroyingField != null)
			try
			{
				return isDestroyingField.getBoolean(gm);
			}catch(Exception ignored)
			{}
		return gm.isDestroying();
	}

	private static void lookupReflection()
	{
		if(reflectionDone)
			return;
		reflectionDone = true;
		clickCountField = field(KeyMapping.class, "clickCount");
		isDestroyingField = field(MultiPlayerGameMode.class, "isDestroying");
		pauseOnLostFocusField = field(Options.class, "pauseOnLostFocus");
	}

	private static Field field(Class<?> c, String name)
	{
		try
		{
			Field f = c.getDeclaredField(name);
			f.setAccessible(true);
			return f;
		}catch(Exception e)
		{
			HumanBot.LOGGER.warn("HumanBot: field " + c.getSimpleName() + "."
				+ name + " not found, using fallback");
			return null;
		}
	}
}
