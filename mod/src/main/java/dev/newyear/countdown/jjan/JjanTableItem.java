package dev.newyear.countdown.jjan;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 짠 테이블 설치 아이템. 기존 대형 블록과 같은 흐름: 들고 있으면 홀로그램(BellPreview), 우클릭으로 설치, 서버가 같은 판정으로 재검사.
 * 실패하면 월드를 바꾸지 않고 아이템도 쓰지 않는다.
 */
public class JjanTableItem extends BlockItem {
    public final JjanTables.Def def;

    public JjanTableItem(Block block, Properties settings, JjanTables.Def def) {
        super(block, settings);
        this.def = def;
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level world = context.getLevel();
        BlockPos master = context.getClickedPos();
        Direction facing = context.getHorizontalDirection().getOpposite();
        if (!JjanLayout.canPlace(world, master, facing, def)) {
            if (!world.isClientSide() && context.getPlayer() != null) {
                context.getPlayer().sendOverlayMessage(Component.translatable("jjan.newyearcountdown.no_space"));
            }
            return InteractionResult.FAIL;
        }
        if (world.isClientSide()) return InteractionResult.SUCCESS;
        JjanLayout.place(world, master, facing, def);
        world.playSound(null, master, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0f, 0.9f);
        context.getItemInHand().consume(1, context.getPlayer());
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag type) {
        tooltip.accept(Component.translatable("jjan.newyearcountdown.table.desc", def.capacity).withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, display, tooltip, type);
    }
}
