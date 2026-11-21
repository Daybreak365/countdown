package dev.newyear.countdown.ai.agent;

import com.google.gson.*;
import dev.newyear.countdown.ai.AiConfig;
import dev.newyear.countdown.ai.script.Textures;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Server-thread preparation/publication around a worker-thread Images API request. */
final class TextureGeneration {
    private TextureGeneration() {}
    static JsonObject prepare(Tools.Ctx ctx, String args) {
        AiConfig config=AiConfig.get();
        if (!config.imageGenerationEnabled) throw new Tools.ToolError("Image generation is disabled in administrator settings");
        if (ctx.player()==null || !ctx.eng.aiEnabled()) throw new Tools.ToolError("An online owner and enabled AI are required");
        JsonObject input=JsonParser.parseString(args).getAsJsonObject();
        String subject=Tools.req(input,"description").trim();
        if (subject.length()>1200) throw new Tools.ToolError("Texture description exceeds 1200 characters");
        double requested=Tools.num(input,"pixels",16);
        if (requested!=16 && requested!=32) throw new Tools.ToolError("pixels must be 16 or 32");
        int size=(int)requested;
        if (!"item".equals(Tools.str(input,"kind","item"))) throw new Tools.ToolError("Only item sprites are supported; custom block generation has been removed");
        if (size>config.dynamicTextureMaxSize) throw new Tools.ToolError("Requested grid exceeds dynamicTextureMaxSize");
        String hash=hash("minecraft-item-v2\n"+config.imageModel+"\n"+config.imageQuality+"\n"+size+"\n"+subject);
        for (Textures.Def texture:ctx.eng.state().textures.values())
            if (Objects.equals(texture.owner,ctx.playerUuid()) && hash.equals(texture.imagePromptHash)) return result(texture,true);
        if (ctx.imageRequests>=2) throw new Tools.ToolError("At most two image generations per turn. Reuse an existing texture or finish on a later turn");
        if (ctx.eng.state().textures.size()>=config.maxDynamicTextures) throw new Tools.ToolError("Server texture limit reached");
        ctx.imageRequests++;
        JsonObject spec=new JsonObject();
        spec.addProperty("id","img_"+UUID.randomUUID().toString().replace("-","").substring(0,24));
        spec.addProperty("description",subject); spec.addProperty("pixels",size); spec.addProperty("hash",hash);
        spec.addProperty("model",config.imageModel); spec.addProperty("quality",config.imageQuality);
        spec.addProperty("reserve",config.imageBudgetReserveUsd); spec.addProperty("budget",config.dailyBudgetUsd);
        return spec;
    }

    static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static JsonObject publish(Tools.Ctx ctx, JsonObject spec, Textures.Def texture) {
        if (ctx.player()==null || !ctx.eng.aiEnabled() || !AiConfig.get().imageGenerationEnabled)
            throw new Tools.ToolError("Owner left or image generation was disabled; generated image was not applied");
        if (ctx.eng.state().textures.size()>=AiConfig.get().maxDynamicTextures) throw new Tools.ToolError("Server texture limit reached while generating");
        texture.imageModel=spec.get("model").getAsString(); texture.imagePromptHash=spec.get("hash").getAsString();
        texture.owner=ctx.playerUuid(); texture.wishId=ctx.wish==null?null:ctx.wish.id;
        texture.created=System.currentTimeMillis();
        ctx.eng.persistTexture(texture);
        ctx.eng.state().textures.put(texture.id,texture);
        ctx.eng.ui().broadcastTexture(texture);
        return result(texture,false);
    }

    private static JsonObject result(Textures.Def texture, boolean reused) {
        JsonObject out=new JsonObject(); out.addProperty("ready",true); out.addProperty("reused",reused);
        out.addProperty("texture",texture.id); out.addProperty("width",texture.w); out.addProperty("height",texture.h);
        out.addProperty("model",texture.imageModel);
        out.addProperty("next","Use this exact texture id in owner.give(...,{texture:id,appearance:profile}) and cooldowns.register(...,{texture:id}). Choose the appropriate grip and swing. "
                + "The texture is saved and synchronized. Do not redraw it with textures.create or regenerate on startup. Implement abilities and cooldowns separately.");
        return out;
    }
}
