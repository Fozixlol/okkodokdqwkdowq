package dev.humanbot.util;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/** The few calls that differ between Minecraft versions: this is 26.2. */
public final class Compat
{
	private static final Minecraft MC = Minecraft.getInstance();

	private Compat()
	{}

	public static InputConstants.Type keyType()
	{
		return InputConstants.Type.KEYSYM;
	}

	// GLFW key codes
	public static int keyRightShift()
	{
		return 344;
	}

	public static int keyJ()
	{
		return 74;
	}

	public static void swingUse()
	{
		MC.player.swing(InteractionHand.MAIN_HAND);
	}

	public static void swingAttack()
	{
		MC.player.swing(InteractionHand.MAIN_HAND);
	}
}
