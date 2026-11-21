package dev.newyear.countdown.ai.script;

import com.google.gson.JsonObject;
import dev.newyear.countdown.ai.WishEngine;
import dev.newyear.countdown.ai.script.api.*;
import dev.newyear.countdown.ai.script.lang.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Shared cooldown enforcement + actionbar HUD for items, keys, passive procs and any other ability. */
public final class RuntimeCooldowns {
    private RuntimeCooldowns() {}
    public static ApiObject api(ScriptInstance inst) {
        return new ApiObject("cooldowns")
            .fn("register", "(player,id,{label,texture?}) set HUD metadata without resetting a running cooldown. Optional; safe at top level on restore.", (in,s,a) -> {
                ServerPlayer p = RuntimeItems.player(in,inst,Args.get(a,0),true);
                var options = Args.opts(a,2);
                CooldownLedger.register(inst.rec,((PlayerRef)Args.get(a,0)).id.toString(),Args.str(in,a,1,"id"),
                        McUtil.clean(Args.optStr(options,"label",Args.str(in,a,1,"id")),40), texture(options));
                if (!inst.dry) send(p,inst.rec);
                return true;
            })
            .fn("tryUse", "(player,id,seconds,opts?{label,texture}) → boolean. ATOMIC: false while cooling down; true starts server cooldown and actionbar UI. Call after eligibility checks and BEFORE applying the ability. Works for all triggers, not only items/keys. Never reset cooldown at top level.", (in,s,a) -> {
                ServerPlayer p = RuntimeItems.player(in,inst,Args.get(a,0),true);
                var options = Args.opts(a,3);
                boolean used = CooldownLedger.tryUse(inst.rec,((PlayerRef)Args.get(a,0)).id.toString(),Args.str(in,a,1,"id"),Args.num(in,a,2,"seconds"),
                        options.map.containsKey("label") ? McUtil.clean(Args.optStr(options,"label",""),40) : null, texture(options));
                if (inst.dry) inst.effect("cooldown " + Args.str(in,a,1,"id"));
                else if (used) send(p,inst.rec);
                return used;
            })
            .fn("remaining", "(player,id) → remaining seconds (server time). Zero means ready.", (in,s,a) -> {
                RuntimeItems.player(in,inst,Args.get(a,0),false);
                return CooldownLedger.remaining(inst.rec,((PlayerRef)Args.get(a,0)).id.toString(),Args.str(in,a,1,"id")) / 20.0;
            })
            .fn("clear", "(player,id) remove this script's cooldown and HUD entry; use only for an intentional reset/removal", (in,s,a) -> {
                ServerPlayer p = RuntimeItems.player(in,inst,Args.get(a,0),true);
                CooldownLedger.clear(inst.rec,((PlayerRef)Args.get(a,0)).id.toString(),Args.str(in,a,1,"id"));
                if (!inst.dry) send(p,inst.rec);
                return true;
            });
    }
    private static String texture(Values.JsObject options) {
        String name = Args.optStr(options,"texture",null);
        return name == null || name.isBlank() ? null : Textures.normalize(name);
    }
    public static void send(ServerPlayer player, ScriptRecord record) {
        JsonObject packet = new JsonObject();
        packet.addProperty("op","cooldowns"); packet.addProperty("script",record.id);
        packet.add("entries",CooldownLedger.visible(record,player.getUUID().toString()));
        WishEngine.get().ui().send(player,packet);
    }
    public static void tick(ScriptRecord record, long tick) {
        for (String id : CooldownLedger.advance(record,tick % 10 == 0)) {
            ServerPlayer player = WishEngine.get().player(UUID.fromString(id));
            if (player != null) send(player,record);
        }
    }
}
