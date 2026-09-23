package com.example.pianoshow;

import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Places the currently loaded piano show at the top face of a block. */
public final class PianoStagePlacerItem extends Item {
    public PianoStagePlacerItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (context.getWorld().isClient()) return ActionResult.SUCCESS;
        if (!(context.getWorld() instanceof ServerWorld world)) return ActionResult.PASS;
        if (context.getSide() != Direction.UP) {
            message(context, "请右键方块顶面放置钢琴舞台");
            return ActionResult.FAIL;
        }
        if (!PianoShowMod.SHOW_MANAGER.isLoaded()) {
            message(context, "请先执行 /piano load <show.pshow>");
            return ActionResult.FAIL;
        }
        if (PianoShowMod.SHOW_MANAGER.isPlaying()) {
            message(context, "演出播放中，停止后才能移动舞台");
            return ActionResult.FAIL;
        }
        BlockPos origin = context.getBlockPos().up();
        try {
            PianoShowMod.SHOW_MANAGER.placeAt(world, origin);
            message(context, "已在 " + origin.toShortString() + " 放置钢琴舞台");
            return ActionResult.SUCCESS;
        } catch (RuntimeException exception) {
            message(context, "无法放置钢琴舞台：" + exception.getMessage());
            return ActionResult.FAIL;
        }
    }

    private static void message(ItemUsageContext context, String text) {
        if (context.getPlayer() != null) context.getPlayer().sendMessage(Text.literal(text), true);
    }
}
