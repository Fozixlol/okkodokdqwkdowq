package dev.humanbot.util;

import com.mojang.blaze3d.platform.InputConstants;

import dev.humanbot.HumanBot;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;

/** The few calls that differ between Minecraft versions: this is 26.3. */
public final class Compat
{
	private static final Minecraft MC = Minecraft.getInstance();
	private static boolean punchPacketBroken;

	private Compat()
	{}

	public static InputConstants.Type keyType()
	{
		return InputConstants.Type.KEYBOARD;
	}

	// SDL scancodes
	public static int keyRightShift()
	{
		return 229;
	}

	public static int keyJ()
	{
		return 13;
	}

	/** Arm swing after right-clicking a block. */
	public static void swingUse()
	{
		MC.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT,
			false);
	}

	/** Arm swing after attacking. */
	public static void swingAttack()
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
}
