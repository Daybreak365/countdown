package dev.newyear.countdown.ai.compat;

import dev.newyear.countdown.ModReg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import java.util.Set;

/** Save compatibility only. No authoring API, placeable items, simulation or custom renderer. */
public final class RetiredMaterials {
    private RetiredMaterials() {}

    public static boolean isRetired(String state) {
        if (state == null) return false;
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        return Set.of("newyearcountdown:wish_solid", "newyearcountdown:wish_liquid", "newyearcountdown:wish_gas").contains(id);
    }

    // Lazy holder keeps pure policy/compatibility checks independent of Minecraft registry bootstrap.
    public static void init() { RegistryEntries.init(); }
    private static final class RegistryEntries {
        static final Placeholder SOLID = ModReg.block("wish_solid", Placeholder::new, BlockBehaviour.Properties.of().strength(1.5f));
        static final Placeholder LIQUID = ModReg.block("wish_liquid", Placeholder::new, BlockBehaviour.Properties.of().strength(.1f).noCollision().noOcclusion());
        static final Placeholder GAS = ModReg.block("wish_gas", Placeholder::new, BlockBehaviour.Properties.of().strength(.1f).noCollision().noOcclusion());
        static final BlockEntityType<SavedData> ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModReg.id("wish_material"),
                new BlockEntityType<>(SavedData::new, Set.of(SOLID, LIQUID, GAS)));
        static void init() {
            for (String id : new String[]{"wish_solid", "wish_liquid", "wish_gas"})
                ModReg.item(id, Item::new, new Item.Properties().useBlockDescriptionPrefix());
        }
    }

    private static final class Placeholder extends BaseEntityBlock {
        Placeholder(Properties properties) { super(properties); }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new SavedData(pos, state); }
        @Override protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean data) { return ItemStack.EMPTY; }
    }

    /** Preserve the old chunk payload verbatim, without interpreting or executing its definition. */
    private static final class SavedData extends BlockEntity {
        private String snapshot = "";
        SavedData(BlockPos pos, BlockState state) { super(RegistryEntries.ENTITY, pos, state); }
        @Override protected void saveAdditional(ValueOutput out) { super.saveAdditional(out); out.putString("Material", snapshot); }
        @Override protected void loadAdditional(ValueInput in) { super.loadAdditional(in); snapshot = in.getStringOr("Material", ""); }
    }
}
