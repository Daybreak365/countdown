package dev.newyear.countdown.photo;

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

/** 포토부스 설치 아이템. 5x5x4 공간이 비어 있어야 하며, 들고 있으면 설치될 모습이 홀로그램으로 보인다(BellPreview). */
public class PhotoBoothItem extends BlockItem {
    public PhotoBoothItem(Block block, Properties settings) {
        super(block, settings);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        Level world = context.getLevel();
        BlockPos master = context.getClickedPos();
        Direction facing = context.getHorizontalDirection().getOpposite();
        if (!PhotoBoothLayout.canPlace(world, master, facing)) {
            if (!world.isClientSide() && context.getPlayer() != null) {
                context.getPlayer().sendOverlayMessage(Component.translatable("photobooth.newyearcountdown.no_space"));
            }
            return InteractionResult.FAIL;
        }
        if (world.isClientSide()) return InteractionResult.SUCCESS;
        PhotoBoothLayout.place(world, master, facing);
        world.playSound(null, master, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0f, 0.9f);
        world.playSound(null, master, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8f, 1.4f);
        context.getItemInHand().consume(1, context.getPlayer());
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag type) {
        tooltip.accept(Component.translatable("block.newyearcountdown.photo_booth.desc").withStyle(ChatFormatting.GRAY));
        super.appendHoverText(stack, context, display, tooltip, type);
    }
}
