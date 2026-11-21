package dev.newyear.countdown.mixin;

import dev.newyear.countdown.client.DiscordAvatarGlyphs;
import dev.newyear.countdown.client.SmoothNameGlyphs;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.network.chat.FontDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 채팅 속 디스코드 프로필 사진: 이 모드의 가짜 아틀라스를 가리키는 스프라이트는 직접 만든 글리프로 바꿔 준다. */
@Mixin(targets = "net.minecraft.client.gui.font.FontManager$CachedFontProvider")
public abstract class CachedFontProviderMixin {
    @Shadow public abstract GlyphSource glyphs(FontDescription description);

    @Inject(method = "invalidate", at = @At("HEAD"))
    private void newyearcountdown$reloadGradients(CallbackInfo ci) {
        SmoothNameGlyphs.clear();
        dev.newyear.countdown.client.DiscordMediaGlyphs.reload();
    }

    @Inject(method = "getGlyphSource", at = @At("HEAD"), cancellable = true)
    private void newyearcountdown$discordAvatar(FontDescription description, CallbackInfoReturnable<GlyphSource> cir) {
        if (description instanceof FontDescription.Resource resource
                && resource.id().getNamespace().equals(dev.newyear.countdown.NewYearCountdown.MOD_ID)
                && resource.id().getPath().startsWith("name_gradient/")) {
            GlyphSource source = SmoothNameGlyphs.source(resource.id(), glyphs(FontDescription.DEFAULT));
            if (source != null) cir.setReturnValue(source);
        }
        if (description instanceof FontDescription.AtlasSprite sprite) {
            GlyphSource media = dev.newyear.countdown.client.DiscordMediaGlyphs.source(sprite);
            if (media != null) { cir.setReturnValue(media); return; }
            GlyphSource source = DiscordAvatarGlyphs.source(sprite);
            if (source != null) cir.setReturnValue(source);
        }
    }
}
