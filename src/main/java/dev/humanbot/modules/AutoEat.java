package dev.humanbot.modules;

import java.util.Set;

import dev.humanbot.BotConfig;
import dev.humanbot.module.Module;
import dev.humanbot.rotation.HumanRotator;
import dev.humanbot.util.InputUtil;
import dev.humanbot.util.Rand;
import dev.humanbot.util.WorldUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;

/** Eats from the hotbar when hungry, then switches back to what you held. */
public final class AutoEat extends Module
{
	private static final Set<String> BAD_FOOD = Set.of("rotten_flesh",
		"spider_eye", "poisonous_potato", "pufferfish", "chorus_fruit",
		"suspicious_stew");
	
	private enum Phase
	{
		REACH, EAT, PUT_AWAY
	}
	
	private Phase phase;
	private int timer;
	private int oldSlot = -1;
	private int foodSlot = -1;
	private int startFood;
	private float lookYaw, lookPitch;
	
	public AutoEat()
	{
		super("AutoEat", "Eat hotbar food when hungry", true, 100);
	}
	
	@Override
	public boolean wantsControl()
	{
		if(phase != null)
			return true;
		int food = MC.player.getFoodData().getFoodLevel();
		boolean hungry = food <= BotConfig.get().eatBelowHunger
			|| (MC.player.getHealth() < 12 && food < 18);
		return hungry && findFood() >= 0 && !enemyClose();
	}
	
	@Override
	public void tickActive(HumanRotator rot)
	{
		if(phase == null)
		{
			foodSlot = findFood();
			if(foodSlot < 0)
				return;
			oldSlot = InputUtil.selectedSlot();
			startFood = MC.player.getFoodData().getFoodLevel();
			phase = Phase.REACH;
			timer = Rand.reactionTicks(BotConfig.get().reactionMs);
			// people tend to look a bit down while munching
			lookYaw = MC.player.getYRot() + (float)Rand.gaussian(0, 8);
			lookPitch = (float)Rand.gaussian(35, 12, 5, 70);
		}
		
		rot.look(lookYaw, lookPitch);
		
		switch(phase)
		{
			case REACH ->
			{
				if(--timer > 0)
					return;
				InputUtil.selectSlot(foodSlot);
				phase = Phase.EAT;
				timer = 70; // eating takes 32 ticks, give up after this
			}
			case EAT ->
			{
				InputUtil.hold(InputUtil.opt().keyUse, true);
				timer--;
				boolean ate =
					MC.player.getFoodData().getFoodLevel() > startFood;
				if(ate || timer <= 0 || !isFood(MC.player.getInventory()
					.getItem(InputUtil.selectedSlot())))
				{
					InputUtil.hold(InputUtil.opt().keyUse, false);
					phase = Phase.PUT_AWAY;
					timer = Rand.ticks(6, 3, 2, 14);
				}
			}
			case PUT_AWAY ->
			{
				if(--timer > 0)
					return;
				if(oldSlot >= 0)
					InputUtil.selectSlot(oldSlot);
				phase = null;
			}
		}
	}
	
	@Override
	public void onLoseControl()
	{
		InputUtil.hold(InputUtil.opt().keyUse, false);
		if(phase != null && oldSlot >= 0)
			InputUtil.selectSlot(oldSlot);
		phase = null;
	}
	
	@Override
	public String status()
	{
		return phase == null ? "" : "eating";
	}
	
	private int findFood()
	{
		int best = -1;
		int bestNutrition = -1;
		for(int i = 0; i < 9; i++)
		{
			ItemStack s = MC.player.getInventory().getItem(i);
			if(!isFood(s))
				continue;
			int n = s.get(DataComponents.FOOD).nutrition();
			if(n > bestNutrition)
			{
				bestNutrition = n;
				best = i;
			}
		}
		return best;
	}
	
	private static boolean isFood(ItemStack s)
	{
		if(s.isEmpty() || !s.has(DataComponents.FOOD))
			return false;
		String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		return !BAD_FOOD.contains(id);
	}
	
	private static boolean enemyClose()
	{
		for(Entity e : MC.level.entitiesForRendering())
			if(e instanceof Enemy && e.isAlive() && WorldUtil.distTo(e) < 5)
				return true;
		return false;
	}
}
