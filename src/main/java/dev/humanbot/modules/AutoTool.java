package dev.humanbot.modules;

import dev.humanbot.module.Module;
import dev.humanbot.util.InputUtil;
import dev.humanbot.util.Rand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Swaps to the fastest hotbar tool when you start mining a block. */
public final class AutoTool extends Module
{
	private BlockPos lastPos;
	private int delay;
	
	public AutoTool()
	{
		super("AutoTool", "Pick the best hotbar tool for the block", true, -1);
	}
	
	@Override
	public void tickHelper()
	{
		if(!(InputUtil.opt().keyAttack.isDown() || InputUtil.isMining())
			|| MC.player.isUsingItem()
			|| !(MC.hitResult instanceof BlockHitResult hit)
			|| MC.hitResult.getType() != HitResult.Type.BLOCK)
		{
			lastPos = null;
			return;
		}
		
		BlockPos pos = hit.getBlockPos();
		if(!pos.equals(lastPos))
		{
			lastPos = pos;
			// people need a split second to reach for the right slot
			delay = Rand.ticks(3, 1.5, 1, 7);
		}
		if(delay > 0 && --delay > 0)
			return;
		
		BlockState state = MC.level.getBlockState(pos);
		int cur = InputUtil.selectedSlot();
		float best = MC.player.getInventory().getItem(cur).getDestroySpeed(state);
		int bestSlot = cur;
		for(int i = 0; i < 9; i++)
		{
			ItemStack s = MC.player.getInventory().getItem(i);
			if(s.isEmpty())
				continue;
			// keep tools that are about to break
			if(s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() < 5)
				continue;
			float speed = s.getDestroySpeed(state);
			if(speed > best * 1.15f)
			{
				best = speed;
				bestSlot = i;
			}
		}
		if(bestSlot != cur)
			InputUtil.selectSlot(bestSlot);
	}
}
