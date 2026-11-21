package dev.newyear.countdown.client.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.newyear.countdown.ModReg;
import dev.newyear.countdown.ai.script.Textures;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 스크립트가 선언한 능력 키와 HUD 패널. 서버가 스크립트별로 {controls, panels} 를 보내면 그대로 들고 있다가
 * 능력 키의 눌림/뗌을 서버에 알리고, 패널 요소(text / rect / bar / texture)를 화면 비율 좌표에 그린다.
 */
public final class RuntimeHud {
    private RuntimeHud() {}

    private static final int SLOTS = 4, MAX_SCRIPTS = 64;
    private static final Map<String, JsonObject> SCRIPTS = new LinkedHashMap<>();
    private static final KeyMapping[] KEYS = new KeyMapping[SLOTS];
    private static final boolean[] DOWN = new boolean[SLOTS];

    public static void register() {
        var category = KeyMapping.Category.register(ModReg.id("abilities"));
        for (int i = 0; i < SLOTS; i++) {
            KEYS[i] = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.newyearcountdown.ability_" + (i + 1),
                    InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));
        }
    }

    public static void clear() {
        SCRIPTS.clear();
        Arrays.fill(DOWN, false);
        CooldownHud.clear();
    }

    public static void apply(JsonObject packet) {
        String id = packet.get("script").getAsString();
        boolean empty = packet.getAsJsonArray("controls").isEmpty() && packet.getAsJsonArray("panels").isEmpty();
        if (empty) SCRIPTS.remove(id);
        else if (SCRIPTS.size() < MAX_SCRIPTS || SCRIPTS.containsKey(id)) SCRIPTS.put(id, packet.deepCopy());
    }

    public static void tick(Minecraft mc) {
        CooldownHud.tick(mc);
        if (mc.player == null) {
            clear();
            return;
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            boolean down = mc.gui.screen() == null && KEYS[slot] != null && KEYS[slot].isDown();
            if (down == DOWN[slot]) continue;
            DOWN[slot] = down;
            for (var entry : new ArrayList<>(SCRIPTS.entrySet())) {
                for (JsonElement control : entry.getValue().getAsJsonArray("controls")) {
                    if (control.getAsJsonObject().get("slot").getAsInt() != slot) continue;
                    JsonObject msg = new JsonObject();
                    msg.addProperty("script", entry.getKey());
                    msg.addProperty("slot", slot);
                    msg.addProperty("pressed", down);
                    AiClientState.action("ability_input", msg.toString());
                }
            }
        }
    }

    private static String keyName(int slot) {
        if (KEYS[slot] == null || KEYS[slot].isUnbound()) {
            return Component.translatable("gui.newyearcountdown.ability_unbound", slot + 1).getString();
        }
        return KEYS[slot].getTranslatedKeyMessage().getString();
    }

    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        // 능력 키 안내는 왼쪽 아래에서 위로 쌓는다 (쿨다운 카드가 있으면 그 위로)
        int keyY = g.guiHeight() - 65 - (CooldownHud.occupied() ? 70 : 0);
        for (JsonObject script : SCRIPTS.values()) {
            for (JsonElement control : script.getAsJsonArray("controls")) {
                JsonObject c = control.getAsJsonObject();
                String label = c.get("label").getAsString(), key = " [" + keyName(c.get("slot").getAsInt()) + "]";
                WishUi.flake(g, 10, keyY + 4, 2, WishUi.FROST);
                g.text(mc.font, label, 16, keyY, WishUi.TEXT, true);
                g.text(mc.font, key, 16 + mc.font.width(label), keyY, WishUi.GOLD, true);
                keyY -= 11;
            }
            for (JsonElement panel : script.getAsJsonArray("panels")) drawPanel(g, mc, panel.getAsJsonObject());
        }
    }

    /** 스크립트가 직접 배치한 패널: 색과 위치는 스크립트가 정한 그대로 그린다. */
    private static void drawPanel(GuiGraphicsExtractor g, Minecraft mc, JsonObject panel) {
        int ax = (int) (Json.dbl(panel, "x") * g.guiWidth()), ay = (int) (Json.dbl(panel, "y") * g.guiHeight());
        for (JsonElement element : panel.getAsJsonArray("elements")) {
            JsonObject e = element.getAsJsonObject();
            int x = ax + Json.num(e, "x"), y = ay + Json.num(e, "y");
            int w = Math.max(0, Json.num(e, "w", 80)), h = Math.max(0, Json.num(e, "h", 8));
            int color = Textures.argb(Json.str(e, "color", "#ffffff"));
            switch (Json.str(e, "type", "text")) {
                case "text" -> g.text(mc.font, Json.str(e, "text"), x, y, color, true);
                case "rect" -> g.fill(x, y, x + w, y + h, color);
                case "bar" -> {
                    g.fill(x, y, x + w, y + h, 0x90000000);
                    g.fill(x, y, x + (int) (w * Math.clamp(Json.dbl(e, "value"), 0, 1)), y + h, color);
                }
                case "texture" -> {
                    var texture = DynamicTextures.get(Json.str(e, "texture"));
                    if (texture != null) g.blit(texture.location, x, y, x + w, y + h, 0f, 1f, 0f, 1f);
                }
                default -> { }
            }
        }
    }
}
