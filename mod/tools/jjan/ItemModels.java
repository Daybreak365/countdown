import dev.newyear.countdown.jjan.JjanTables;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 짠 테이블 6종의 에셋 생성: 아이템 모델(JjanTables 의 조각을 16x16x16 안에 줄여 담은 것), 아이템 정의, 블록 상태, 입자용 블록 모델.
 * 조각 정의를 바꾸면 다시 돌린다 (mod 폴더에서, 먼저 컴파일):
 *
 *     ./gradlew compileJava
 *     java -cp build/classes/java/main tools/jjan/ItemModels.java
 */
public class ItemModels {
    /** 부술 때 튀는 조각(입자)에 쓸 바닐라 텍스처: 탁자 색과 가장 가까운 판자. */
    static final Map<String, String> PARTICLE = Map.of("bar", "dark_oak_planks", "square", "spruce_planks", "round", "oak_planks",
            "long", "spruce_planks", "biground", "dark_oak_planks", "ushape", "spruce_planks");

    public static void main(String[] args) throws Exception {
        Path assets = Path.of("src/main/resources/assets/newyearcountdown");
        for (JjanTables.Def def : JjanTables.ALL) {
            String name = "jjan_table_" + def.id;
            String particle = PARTICLE.get(def.id);
            write(assets.resolve("models/item/" + name + ".json"), itemModel(def));
            write(assets.resolve("items/" + name + ".json"),
                    "{\n \"model\": {\n  \"type\": \"minecraft:model\",\n  \"model\": \"newyearcountdown:item/" + name + "\"\n }\n}\n");
            write(assets.resolve("models/block/" + name + ".json"), "{ \"textures\": { \"particle\": \"minecraft:block/" + particle + "\" } }\n");
            String state = "{ \"variants\": { \"\": { \"model\": \"newyearcountdown:block/" + name + "\" } } }\n";
            write(assets.resolve("blockstates/" + name + ".json"), state);
            write(assets.resolve("blockstates/" + name + "_part.json"), state);
            System.out.println(name + ": " + def.parts.size() + " parts, " + def.capacity + " seats");
        }
    }

    private static void write(Path p, String s) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }

    private static String itemModel(JjanTables.Def def) throws Exception {
        // 돌린 조각(의자)은 중심만 돌려 놓고 축에 맞춘 상자로 그린다 (JSON 모델은 임의 각도 회전을 못 쓴다)
        List<float[]> boxes = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        float minX = 1e9f, maxX = -1e9f, minZ = 1e9f, maxZ = -1e9f, maxY = 0;
        for (JjanTables.Part p : def.parts) {
            float cx = (p.x0() + p.x1()) / 2, cz = (p.z0() + p.z1()) / 2, hx = (p.x1() - p.x0()) / 2, hz = (p.z1() - p.z0()) / 2;
            if (p.yaw() != 0f) {
                double c = Math.cos(p.yaw()), s = Math.sin(p.yaw()), dx = cx - p.pivotX(), dz = cz - p.pivotZ();
                cx = (float) (p.pivotX() + dx * c + dz * s);
                cz = (float) (p.pivotZ() - dx * s + dz * c);
                double quarter = Math.abs(Math.sin(p.yaw()));
                if (quarter > 0.9) { float t = hx; hx = hz; hz = t; }
                else if (quarter > 0.1) hx = hz = Math.min(hx, hz) * 1.15f;
            }
            float[] b = {cx - hx, p.y0(), cz - hz, cx + hx, p.y1(), cz + hz};
            boxes.add(b);
            colors.add(p.color());
            minX = Math.min(minX, b[0]); maxX = Math.max(maxX, b[3]);
            minZ = Math.min(minZ, b[2]); maxZ = Math.max(maxZ, b[5]);
            maxY = Math.max(maxY, b[4]);
        }
        float span = Math.max(Math.max(maxX - minX, maxZ - minZ), maxY);
        float k = 16f / span, ox = (minX + maxX) / 2, oz = (minZ + maxZ) / 2;
        // 조각 색을 16x16 팔레트 텍스처의 칸 하나씩에 담고, 면마다 그 칸을 가리킨다 (아이템 모델은 조각별 색을 곱할 수 없다)
        Map<Integer, Integer> slots = new LinkedHashMap<>();
        StringBuilder el = new StringBuilder();
        for (int i = 0; i < boxes.size(); i++) {
            float[] b = boxes.get(i);
            int slot = slots.computeIfAbsent(colors.get(i) & 0xFCFCFC, c -> slots.size());
            if (slot >= 256) throw new IllegalStateException(def.id + ": 색이 256 가지를 넘는다");
            float[] f = {8 + (b[0] - ox) * k, b[1] * k, 8 + (b[2] - oz) * k, 8 + (b[3] - ox) * k, b[4] * k, 8 + (b[5] - oz) * k};
            for (int a = 0; a < 3; a++) if (f[a + 3] - f[a] < 0.12f) f[a + 3] = f[a] + 0.12f;   // 너무 얇으면 안 보인다
            if (el.length() > 0) el.append(",\n");
            el.append(String.format(Locale.ROOT, "  {\"from\":[%.3f,%.3f,%.3f],\"to\":[%.3f,%.3f,%.3f],\"faces\":{", f[0], f[1], f[2], f[3], f[4], f[5]));
            String uv = String.format(Locale.ROOT, "[%.2f,%.2f,%.2f,%.2f]", slot % 16 + 0.25f, slot / 16 + 0.25f, slot % 16 + 0.75f, slot / 16 + 0.75f);
            String[] faces = {"north", "east", "south", "west", "up", "down"};
            for (int a = 0; a < 6; a++) el.append(a > 0 ? "," : "").append('"').append(faces[a]).append("\":{\"texture\":\"#t\",\"uv\":").append(uv).append("}");
            el.append("}}");
        }
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 256; i++) img.setRGB(i % 16, i / 16, 0xFF000000);
        for (Map.Entry<Integer, Integer> e : slots.entrySet()) img.setRGB(e.getValue() % 16, e.getValue() / 16, 0xFF000000 | e.getKey());
        Path png = Path.of("src/main/resources/assets/newyearcountdown/textures/item/jjan_table_" + def.id + ".png");
        Files.createDirectories(png.getParent());
        javax.imageio.ImageIO.write(img, "png", png.toFile());
        return "{\n \"parent\": \"minecraft:block/block\",\n \"textures\": {\n  \"t\": \"newyearcountdown:item/jjan_table_" + def.id + "\",\n"
                + "  \"particle\": \"minecraft:block/" + PARTICLE.get(def.id) + "\"\n },\n"
                + " \"elements\": [\n" + el + "\n ]\n}\n";
    }
}
