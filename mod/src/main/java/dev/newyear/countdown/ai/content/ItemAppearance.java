package dev.newyear.countdown.ai.content;

import dev.newyear.countdown.ModReg;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.SwingAnimation;
import java.util.Set;

/** Persisted display presets shared by both hands, dropped items and inventory views. */
public final class ItemAppearance {
    public static final Set<String> PROFILES = Set.of("flat", "handheld", "staff", "bow", "shield", "block");
    private ItemAppearance() {}

    public static void requireGenerated(int version, boolean custom, dev.newyear.countdown.ai.script.Textures.Def texture) {
        if (version>=1 && custom && (texture==null || texture.imageModel==null))
            throw new IllegalArgumentException("Every new custom item requires an original generated appearance, even when the player did not ask for one. Call generate_texture first, then give with {texture:id,appearance:profile}. A renamed vanilla item is not complete.");
    }

    public static String infer(String item) {
        if (item.endsWith("shield")) return "shield";
        if (item.endsWith(":bow") || item.equals("bow")) return "bow";
        if (item.matches(".*(sword|axe|shovel|hoe|trident|spear|mace)$")) return "handheld";
        return "flat";
    }

    public static void apply(ItemStack stack, String profile, String swing, int ticks) {
        if (!PROFILES.contains(profile)) throw new IllegalArgumentException("appearance must be flat/handheld/staff/bow/shield/block");
        if (!Set.of("whack", "stab", "none").contains(swing)) throw new IllegalArgumentException("swing must be whack/stab/none");
        if (ticks < 1 || ticks > 40) throw new IllegalArgumentException("swingTicks must be 1..40");
        stack.set(DataComponents.ITEM_MODEL, ModReg.id("wish_" + profile));
        stack.set(DataComponents.ATTACK_ANIMATION, new SwingAnimation(SwingAnimationType.byName(swing, SwingAnimationType.WHACK), ticks));
        stack.set(DataComponents.INTERACT_ANIMATION, new SwingAnimation(SwingAnimationType.byName(swing, SwingAnimationType.WHACK), ticks));
    }

    public static ItemUseAnimation useAnimation(ItemStack stack) {
        // Custom pose must never suppress the consumable pipeline or consume ability equipment.
        if (stack.has(DataComponents.CONSUMABLE)) return ItemUseAnimation.NONE;
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return ItemUseAnimation.NONE;
        CompoundTag wish = data.copyTag().getCompoundOrEmpty("wishai");
        if (!wish.contains("texture")) return ItemUseAnimation.NONE;
        return switch (wish.getStringOr("useAnimation", "none")) {
            case "bow" -> ItemUseAnimation.BOW;
            case "block" -> ItemUseAnimation.BLOCK;
            case "spear" -> ItemUseAnimation.TRIDENT;
            default -> ItemUseAnimation.NONE;
        };
    }
}
