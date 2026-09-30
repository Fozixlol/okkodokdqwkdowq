package dev.humanbot.util;

import java.util.concurrent.ThreadLocalRandom;

/** Randomness helpers tuned for human-looking timing. */
public final class Rand
{
	private Rand()
	{}

	public static double uniform(double min, double max)
	{
		return min + ThreadLocalRandom.current().nextDouble() * (max - min);
	}

	public static boolean chance(double p)
	{
		return ThreadLocalRandom.current().nextDouble() < p;
	}

	public static double gaussian(double mean, double sd)
	{
		return mean + ThreadLocalRandom.current().nextGaussian() * sd;
	}

	/** Gaussian, clamped to [min, max]. */
	public static double gaussian(double mean, double sd, double min, double max)
	{
		return Math.max(min, Math.min(max, gaussian(mean, sd)));
	}

	/**
	 * Human reaction times are right-skewed (log-normal-ish): mostly around
	 * the mean, occasionally much slower, never instant.
	 */
	public static int reactionTicks(int meanMs)
	{
		double ms = meanMs * Math.exp(gaussian(0, 0.28));
		return (int)Math.max(1, Math.min(30, Math.round(ms / 50.0)));
	}

	public static int ticks(double meanTicks, double sd, int min, int max)
	{
		return (int)Math.round(gaussian(meanTicks, sd, min, max));
	}
}
