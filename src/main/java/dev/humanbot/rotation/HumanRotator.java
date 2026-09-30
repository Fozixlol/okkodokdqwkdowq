package dev.humanbot.rotation;

import dev.humanbot.BotConfig;
import dev.humanbot.util.Rand;
import dev.humanbot.util.WorldUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Turns the camera the way a hand on a mouse does: a short reaction delay,
 * accelerate, decelerate, sometimes overshoot and correct, and a little
 * tremor on top. Modules only say where to look; this decides how.
 */
public final class HumanRotator
{
	private static final Minecraft MC = Minecraft.getInstance();

	private boolean hasTarget;
	private float targetYaw, targetPitch;
	private float lastTargetYaw, lastTargetPitch;
	private boolean hadTargetLastTick;

	private float velYaw, velPitch;
	private float overshootYaw, overshootPitch;
	private float speedMul = 1;
	private int reactionTimer;

	private double phaseA = Rand.uniform(0, 100), phaseB = Rand.uniform(0, 100);

	/** Ask to look at a world position this tick. */
	public void lookAt(Vec3 point)
	{
		Vec3 eyes = WorldUtil.eyes();
		double dx = point.x - eyes.x, dy = point.y - eyes.y,
			dz = point.z - eyes.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		float pitch = (float)-Math.toDegrees(Math.atan2(dy, h));
		look(yaw, pitch);
	}

	/** Ask to look in a direction this tick. */
	public void look(float yaw, float pitch)
	{
		hasTarget = true;
		targetYaw = yaw;
		targetPitch = Mth.clamp(pitch, -89.5f, 89.5f);
	}

	/** Ask to face a yaw but keep the current pitch roughly where it is. */
	public void lookYaw(float yaw, float pitch)
	{
		look(yaw, pitch);
	}

	/** Degrees between where we're looking and where we want to look. */
	public float error()
	{
		LocalPlayer p = MC.player;
		if(p == null || !hasTargetOrHad())
			return 0;
		float ey = Mth.wrapDegrees(targetYaw - p.getYRot());
		float ep = targetPitch - p.getXRot();
		return (float)Math.sqrt(ey * ey + ep * ep);
	}

	private boolean hasTargetOrHad()
	{
		return hasTarget || hadTargetLastTick;
	}

	public boolean isReacting()
	{
		return reactionTimer > 0;
	}

	/** Run once per client tick, after modules have chosen a target. */
	public void tick()
	{
		LocalPlayer p = MC.player;
		if(p == null)
			return;

		BotConfig cfg = BotConfig.get();

		if(!hasTarget)
		{
			// let go of the mouse: no movement, the player's own mouse rules
			velYaw = velPitch = 0;
			overshootYaw = overshootPitch = 0;
			hadTargetLastTick = false;
			return;
		}

		float yaw = p.getYRot();
		float pitch = p.getXRot();

		// a new target that jumps far from the old one needs reaction time
		float jump = hadTargetLastTick
			? angleDist(targetYaw, targetPitch, lastTargetYaw, lastTargetPitch)
			: angleDist(targetYaw, targetPitch, yaw, pitch);
		// continuous tracking (a walking path, a moving mob) only re-triggers
		// a reaction when the target suddenly jumps a lot
		if(jump > (hadTargetLastTick ? 30f : 12f))
		{
			reactionTimer = Rand.reactionTicks(cfg.reactionMs);
			speedMul = (float)Rand.uniform(0.75, 1.2);
			float bigErr = Mth.wrapDegrees(targetYaw - yaw);
			if(Math.abs(bigErr) > 25f && Rand.chance(cfg.overshootChance))
			{
				overshootYaw = bigErr * (float)Rand.uniform(0.03, 0.1);
				overshootPitch = (targetPitch - pitch)
					* (float)Rand.uniform(0.0, 0.08);
			}else
				overshootYaw = overshootPitch = 0;
		}
		lastTargetYaw = targetYaw;
		lastTargetPitch = targetPitch;
		hadTargetLastTick = true;
		hasTarget = false; // modules must re-request every tick

		if(reactionTimer > 0)
		{
			reactionTimer--;
			velYaw *= 0.5f;
			velPitch *= 0.5f;
			applyTremor(p, yaw, pitch, 0.4f);
			return;
		}

		float errYaw = Mth.wrapDegrees(targetYaw + overshootYaw - yaw);
		float errPitch = targetPitch + overshootPitch - pitch;

		// once the overshoot point is reached, correct back to the real spot
		if(Math.abs(errYaw) < 1.5f && Math.abs(errPitch) < 1.5f
			&& (overshootYaw != 0 || overshootPitch != 0))
		{
			overshootYaw = overshootPitch = 0;
			reactionTimer = Rand.ticks(2, 1, 1, 4);
		}

		float max = cfg.turnSpeed * speedMul;
		float accel = max * 0.5f;
		float wantYaw = Mth.clamp(errYaw * 0.42f, -max, max);
		float wantPitch = Mth.clamp(errPitch * 0.38f, -max * 0.7f, max * 0.7f);
		velYaw += Mth.clamp(wantYaw - velYaw, -accel, accel);
		velPitch += Mth.clamp(wantPitch - velPitch, -accel, accel);

		float moving = Math.min(1f, (Math.abs(velYaw) + Math.abs(velPitch)) / 6f);
		applyTremor(p, yaw + velYaw, pitch + velPitch, 0.4f + moving);
	}

	private void applyTremor(LocalPlayer p, float yaw, float pitch, float scale)
	{
		float amp = BotConfig.get().aimShake * 0.22f * scale;
		phaseA += Rand.uniform(0.15, 0.35);
		phaseB += Rand.uniform(0.25, 0.55);
		float ny = (float)(Math.sin(phaseA) * 0.6 + Math.sin(phaseB * 1.7) * 0.4)
			* amp;
		float np = (float)(Math.cos(phaseB) * 0.6 + Math.sin(phaseA * 1.3) * 0.4)
			* amp * 0.6f;
		p.setYRot(yaw + ny);
		p.setXRot(Mth.clamp(pitch + np, -90f, 90f));
	}

	private static float angleDist(float y1, float p1, float y2, float p2)
	{
		float dy = Mth.wrapDegrees(y1 - y2);
		float dp = p1 - p2;
		return (float)Math.sqrt(dy * dy + dp * dp);
	}

	public static float yawTo(Vec3 from, Vec3 to)
	{
		return (float)Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x))
			- 90f;
	}
}
