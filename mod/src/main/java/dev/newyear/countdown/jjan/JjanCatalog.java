package dev.newyear.countdown.jjan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 짠: 술·잔 카탈로그. 웹 원본(jjan/public/js/data.js, voxel.js)을 tools/jjan/export.mjs 로 내보낸
 * assets/newyearcountdown/jjan/catalog.json 을 읽는다(서버·클라이언트 공용, 모드 jar 안에 들어 있다).
 *
 * 제공량(ml)은 원본과 같이 잔의 액체 칸 수와 술의 초기 액면 높이(fill)로 계산한다:
 * cavityTop = cavityStart + (H - cavityStart) × fill, 제공량 = cavityStart ≤ y < floor(cavityTop) 층의 액체 칸 수 합.
 */
public final class JjanCatalog {
    public record Category(String id, String name) {}

    /** 잔. 단위는 원본 복셀(≈1cm). voxels = {x, y, z, rgb} 반복, cavity[y] = 그 층 액체 칸 {x, z} 반복. */
    public record Glass(int index, String id, String name, String material, boolean hidden, int height, int radius,
                        int cavityStart, int[][] cavity, double contactR, double contactLow, double contactMid, double rimR, int[] voxels,
                        double[] cavityR, double rimOuter, double rimInner) {
        /** y 층의 액체 칸 반지름 (범위를 벗어나면 가장 가까운 층). */
        public double cavityRadius(int y) {
            if (cavityR.length == 0) return 1;
            double r = cavityR[Math.max(cavityStart, Math.min(cavityR.length - 1, y))];
            return r > 0 ? r : 1;
        }

        public boolean transparent() {
            return material.equals("glass") || material.equals("thick") || material.equals("crystal");
        }

        /** 이 잔에 fill(초기 액면 높이) 만큼 따랐을 때의 제공량(ml). */
        public int capacity(double fill) {
            double top = cavityStart + (height - cavityStart) * fill;
            int n = 0;
            for (int y = cavityStart; y < Math.floor(top); y++) n += y < cavity.length ? cavity[y].length / 2 : 0;
            return n;
        }

        /** 액면 높이(복셀): 남은 비율 0..1 → 잔 안의 높이. */
        public double level(double drinkFill, double remaining) {
            double top = cavityStart + (height - cavityStart) * drinkFill;
            return cavityStart + (top - cavityStart) * remaining;
        }
    }

    /** 따르는 그릇(병·캔 등): 모양(vessels 번호)과 색. color·label·cap 이 -1 이면 그 모양의 기본 색. */
    public record Bottle(String kind, int vessel, int color, int label, int cap, int liquid, int stream) {}

    /** 셰이커·믹싱 글라스·블렌더에 넣는 재료 하나: 병과 붓는 줄기 색. dash = 몇 방울만(비터스). */
    public record Ingredient(Bottle bottle, boolean dash) {}

    /**
     * 따르는 순서의 한 단계 (원본 data.js 의 pour). prep = 따르기 전에 하는 일(shake, stir, blend, cork, can, flip, swirl, 없으면 ""),
     * to = 이 단계가 끝났을 때의 잔량(-1 이면 고르게 나눈다), layer = 바 스푼 위로 살살 띄워 층을 쌓는다,
     * dur·carb·tint·sinkFrom = -1 이면 기본값, hot = -1 기본 / 0 / 1, sink = 따른 뒤 바닥에 깔리며 번진다(그레나딘).
     */
    public record Step(Bottle bottle, String prep, double to, boolean layer, double dur, double carb, int hot, int tint,
                       boolean sink, int sinkFrom, List<Ingredient> build) {}

    /** 셰이커의 세 조각(vessels 번호)과 통 입구 높이(따르는 입구 기준). */
    public record Shaker(int tin, int lid, int cap, double tinTop) {}

    /**
     * 따르는 그릇의 겉면 (원본 props.js 의 buildVessel 결과). 좌표는 복셀, 따르는 입구가 원점이고 몸통은 그 아래.
     * faces = {x, y, z, 방향(0..5 = +X -X +Y -Y +Z -Z), c} 반복. c ≥ 0 이면 구운 색(rgb),
     * c < 0 이면 n = -c - 1 에서 자리 n % 4 (0 몸통, 1 라벨, 2 뚜껑, 3 비치는 술), 밝기 n / 4 / 1000.
     */
    public record Vessel(String kind, boolean glassy, boolean metal, boolean spout, double angle, int bottom, int[] faces, int[] defaults,
                         double[] box) {
        /** 겉면이 차지하는 범위 {minX, minY, minZ, maxX, maxY, maxZ}. */
        static double[] boxOf(int[] f) {
            double[] b = {1e9, 1e9, 1e9, -1e9, -1e9, -1e9};
            for (int i = 0; i < f.length; i += 5) {
                for (int k = 0; k < 3; k++) {
                    b[k] = Math.min(b[k], f[i + k]);
                    b[k + 3] = Math.max(b[k + 3], f[i + k] + 1);
                }
            }
            return b;
        }

        public double minX() { return box[0]; }
        public double minY() { return box[1]; }
        public double minZ() { return box[2]; }
        public double maxX() { return box[3]; }
        public double maxY() { return box[4]; }
        public double maxZ() { return box[5]; }
    }

    /** 술에 떠다니는 것 (밥알, 마시멜로, 민트 …). 원본 props.js 의 FLOATERS. */
    public record Floater(int count, double[] scale, int[] colors, double depthMin, double depthMax, double speed) {}

    /** 술(또는 브랜드를 적용한 술)의 외형·도수. */
    public record Look(int color, double opacity, double fill, double abv, int foamColor, double foamHeight, double carb,
                       int ice, boolean hot, boolean flame, boolean layered, double[] gradientAt, int[] gradientColor,
                       Bottle bottle, String prep, List<String> garnish, String floaters, boolean cloudy, boolean iceSphere,
                       String bubble, int bubbleColor, String bomb, int bombShot, List<Step> pour, String after) {}

    public record Variant(int index, String id, String name, String desc, Look look) {}

    public record Drink(int index, String id, String category, String name, String desc, String glass, boolean shot,
                        Look look, List<Variant> variants) {
        public Variant variant(String id) {
            if (id == null || id.isEmpty()) return null;
            for (Variant v : variants) if (v.id.equals(id)) return v;
            return null;
        }

        public Look look(Variant v) {
            return v == null ? look : v.look;
        }
    }

    private final List<Category> categories;
    private final List<String> phrases;
    private final List<Glass> glasses;
    private final List<Drink> drinks;
    private final List<Vessel> vessels;
    private final Shaker shaker;
    private List<Anju> anju = List.of();
    private Map<String, Integer> pairing = Map.of();

    /** 안주 한 접시 (원작 anju.js). plate·pieces 는 상자 목록 [x0, y0, z0, x1, y1, z1, rgb]… 복셀 단위, 접시 가운데 바닥이 원점. */
    public record Anju(int index, String id, String name, boolean hot, float[] plate, List<float[]> pieces) {}

    public List<Anju> anju() { return anju; }

    /** 이 술에 곁들여 나오는 안주 (원작 pairAnju). 없으면 null. */
    public Anju anjuFor(Drink d) {
        Integer i = pairing.get(d.id());
        return i == null || i < 0 || i >= anju.size() ? null : anju.get(i);
    }

    private static float[] floats(JsonElement e) {
        var arr = e.getAsJsonArray();
        float[] out = new float[arr.size()];
        for (int i = 0; i < out.length; i++) out[i] = arr.get(i).getAsFloat();
        return out;
    }
    private final Map<String, Floater> floaters;
    private final Map<String, Glass> glassById = new HashMap<>();
    private final Map<String, Drink> drinkById = new HashMap<>();

    private JjanCatalog(List<Category> categories, List<String> phrases, List<Glass> glasses, List<Drink> drinks,
                        List<Vessel> vessels, Map<String, Floater> floaters, Shaker shaker) {
        this.shaker = shaker;
        this.vessels = vessels;
        this.floaters = floaters;
        this.categories = categories;
        this.phrases = phrases;
        this.glasses = glasses;
        this.drinks = drinks;
        for (Glass g : glasses) glassById.put(g.id, g);
        for (Drink d : drinks) drinkById.put(d.id, d);
    }

    private static volatile JjanCatalog current;

    /** 지금 쓰는 카탈로그 (처음 부를 때 읽는다). */
    public static JjanCatalog get() {
        JjanCatalog c = current;
        if (c == null) {
            synchronized (JjanCatalog.class) {
                if (current == null) current = load();
                c = current;
            }
        }
        return c;
    }

    /** 다시 읽어 검증한다. 잘못됐으면 예외를 던지고 이전 카탈로그를 그대로 쓴다. */
    public static JjanCatalog reload() {
        JjanCatalog next = load();
        current = next;
        return next;
    }

    public static JjanCatalog load() {
        try (InputStream in = JjanCatalog.class.getResourceAsStream("/assets/newyearcountdown/jjan/catalog.json")) {
            if (in == null) throw new IllegalStateException("jjan/catalog.json 이 모드에 없습니다");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return parse(root);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("jjan 카탈로그를 읽지 못했습니다: " + e.getMessage(), e);
        }
    }

    static JjanCatalog parse(JsonObject root) {
        List<Category> cats = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("categories")) {
            JsonObject o = e.getAsJsonObject();
            cats.add(new Category(str(o, "id"), str(o, "name")));
        }
        List<String> phrases = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("phrases")) phrases.add(e.getAsString());

        List<Glass> glasses = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("glasses")) {
            JsonObject o = e.getAsJsonObject();
            JsonArray cav = o.getAsJsonArray("cavity");
            int[][] cavity = new int[cav.size()][];
            for (int y = 0; y < cav.size(); y++) cavity[y] = ints(cav.get(y).getAsJsonArray());
            double[] rim = doubles(o.getAsJsonArray("rimRing"));
            check(rim.length >= 2, "잔 " + str(o, "id") + ": rimRing");
            Glass g = new Glass(glasses.size(), str(o, "id"), str(o, "name"), str(o, "material"), o.get("hidden").getAsBoolean(),
                    o.get("H").getAsInt(), o.get("R").getAsInt(), o.get("cavityStart").getAsInt(), cavity,
                    o.get("contactR").getAsDouble(), o.get("contactLow").getAsDouble(), o.get("contactMid").getAsDouble(),
                    o.get("rimR").getAsDouble(), ints(o.getAsJsonArray("voxels")), doubles(o.getAsJsonArray("cavityR")), rim[0], rim[1]);
            check(g.height > 0 && g.height <= 64 && g.voxels.length % 4 == 0, "잔 " + g.id + ": 크기");
            check(g.contactR > 0 && g.contactMid >= g.contactLow, "잔 " + g.id + ": 부딪힘 치수");
            check(List.of("glass", "thick", "crystal", "metal", "ceramic", "wood").contains(g.material), "잔 " + g.id + ": 재질 " + g.material);
            glasses.add(g);
        }
        Map<String, Glass> gid = new HashMap<>();
        for (Glass g : glasses) check(gid.put(g.id, g) == null, "잔 id 중복: " + g.id);

        List<Vessel> vessels = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("vessels")) {
            JsonObject o = e.getAsJsonObject();
            Vessel v = new Vessel(str(o, "kind"), o.get("glassy").getAsBoolean(), o.get("metal").getAsBoolean(), o.get("spout").getAsBoolean(),
                    o.get("angle").getAsDouble(), o.get("bottom").getAsInt(), ints(o.getAsJsonArray("faces")), ints(o.getAsJsonArray("defaults")),
                    Vessel.boxOf(ints(o.getAsJsonArray("faces"))));
            check(v.faces.length % 5 == 0 && v.faces.length > 0 && v.defaults.length == 4, "그릇 " + v.kind + ": 면");
            vessels.add(v);
        }
        JsonObject sh = root.getAsJsonObject("shaker");
        check(sh != null, "셰이커 조각이 없습니다 (카탈로그를 다시 내보내세요)");
        Shaker shaker = new Shaker(sh.get("tin").getAsInt(), sh.get("lid").getAsInt(), sh.get("cap").getAsInt(), sh.get("tinTop").getAsDouble());
        for (int part : new int[]{shaker.tin, shaker.lid, shaker.cap}) check(part >= 0 && part < vessels.size(), "셰이커 조각 " + part);
        Map<String, Floater> floaters = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("floaters").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            double[] depth = doubles(o.getAsJsonArray("depth")), scale = doubles(o.getAsJsonArray("scale"));
            int[] colors = ints(o.getAsJsonArray("colors"));
            check(depth.length == 2 && scale.length == 3 && colors.length > 0, "떠다니는 것 " + e.getKey());
            floaters.put(e.getKey(), new Floater(o.get("n").getAsInt(), scale, colors, depth[0], depth[1], o.get("speed").getAsDouble()));
        }

        List<Drink> drinks = new ArrayList<>();
        Map<String, Boolean> catIds = new LinkedHashMap<>();
        for (Category c : cats) catIds.put(c.id, true);
        for (JsonElement e : root.getAsJsonArray("drinks")) {
            JsonObject o = e.getAsJsonObject();
            String id = str(o, "id");
            check(catIds.containsKey(str(o, "cat")), "술 " + id + ": 분류 " + str(o, "cat"));
            check(gid.containsKey(str(o, "glass")) && !gid.get(str(o, "glass")).hidden, "술 " + id + ": 추천 잔 " + str(o, "glass"));
            List<Variant> vs = new ArrayList<>();
            for (JsonElement ve : o.getAsJsonArray("variants")) {
                JsonObject v = ve.getAsJsonObject();
                vs.add(new Variant(vs.size(), str(v, "id"), str(v, "name"), str(v, "desc"), look(v, id + "/" + str(v, "id"), vessels.size(), floaters)));
            }
            drinks.add(new Drink(drinks.size(), id, str(o, "cat"), str(o, "name"), str(o, "desc"), str(o, "glass"),
                    o.get("shot").getAsBoolean(), look(o, id, vessels.size(), floaters), Collections.unmodifiableList(vs)));
        }
        Map<String, Boolean> did = new HashMap<>();
        for (Drink d : drinks) check(did.put(d.id, true) == null, "술 id 중복: " + d.id);
        check(!drinks.isEmpty() && !cats.isEmpty(), "술 목록이 비었습니다");
        JjanCatalog made = new JjanCatalog(List.copyOf(cats), List.copyOf(phrases), List.copyOf(glasses), List.copyOf(drinks),
                List.copyOf(vessels), Map.copyOf(floaters), shaker);
        if (root.has("anju")) {
            List<Anju> list = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("anju")) {
                JsonObject o = e.getAsJsonObject();
                List<float[]> pieces = new ArrayList<>();
                for (JsonElement pc : o.getAsJsonArray("pieces")) pieces.add(floats(pc));
                list.add(new Anju(list.size(), str(o, "id"), str(o, "name"), o.get("hot").getAsBoolean(), floats(o.get("plate")), List.copyOf(pieces)));
            }
            Map<String, Integer> pair = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("pairing").entrySet()) pair.put(e.getKey(), e.getValue().getAsInt());
            made.anju = List.copyOf(list);
            made.pairing = Map.copyOf(pair);
        }
        return made;
    }

    private static Look look(JsonObject o, String where, int vesselCount, Map<String, Floater> floaters) {
        double fill = o.get("fill").getAsDouble(), abv = o.get("abv").getAsDouble();
        check(fill > 0 && fill <= 1, where + ": fill " + fill);
        check(abv >= 0 && abv <= 100, where + ": abv " + abv);
        int foamColor = 0;
        double foamH = 0;
        if (o.has("foam") && o.get("foam").isJsonObject()) {
            foamColor = o.getAsJsonObject("foam").get("color").getAsInt();
            foamH = o.getAsJsonObject("foam").get("h").getAsDouble();
        }
        double[] at = null;
        int[] col = null;
        if (o.has("gradient") && o.get("gradient").isJsonArray()) {
            JsonArray g = o.getAsJsonArray("gradient");
            at = new double[g.size()];
            col = new int[g.size()];
            for (int i = 0; i < g.size(); i++) {
                at[i] = g.get(i).getAsJsonArray().get(0).getAsDouble();
                col[i] = g.get(i).getAsJsonArray().get(1).getAsInt();
            }
        }
        Bottle bottle = bottle(o.getAsJsonObject("bottle"), where, vesselCount);
        List<Step> pour = new ArrayList<>();
        for (JsonElement pe : o.getAsJsonArray("pour")) {
            JsonObject p = pe.getAsJsonObject();
            List<Ingredient> build = new ArrayList<>();
            for (JsonElement ie : p.getAsJsonArray("build")) {
                JsonObject ing = ie.getAsJsonObject();
                build.add(new Ingredient(bottle(ing, where, vesselCount), ing.get("dash").getAsBoolean()));
            }
            Step st = new Step(bottle(p, where, vesselCount), str(p, "prep"), p.get("to").getAsDouble(), p.get("layer").getAsBoolean(),
                    p.get("dur").getAsDouble(), p.get("carb").getAsDouble(), p.get("hot").getAsInt(), p.get("tint").getAsInt(),
                    p.get("sink").getAsBoolean(), p.get("sinkFrom").getAsInt(), List.copyOf(build));
            check(List.of("", "shake", "stir", "blend", "cork", "can", "flip", "swirl").contains(st.prep), where + ": 준비 동작 " + st.prep);
            check(st.to < 0 || st.to <= 1, where + ": 따르는 양 " + st.to);
            pour.add(st);
        }
        check(!pour.isEmpty() && pour.size() <= 8, where + ": 따르는 순서");
        List<String> garnish = new ArrayList<>();
        for (JsonElement g : o.getAsJsonArray("garnish")) garnish.add(g.getAsString());
        String fl = str(o, "floaters");
        check(fl.isEmpty() || floaters.containsKey(fl), where + ": 떠다니는 것 " + fl);
        return new Look(o.get("color").getAsInt(), o.get("op").getAsDouble(), fill, abv, foamColor, foamH, o.get("carb").getAsDouble(),
                o.get("ice").getAsInt(), o.get("hot").getAsBoolean(), o.get("flame").getAsBoolean(), o.get("layered").getAsBoolean(),
                at, col, bottle, str(o, "prep"), List.copyOf(garnish), fl, o.get("cloudy").getAsBoolean(),
                "sphere".equals(str(o, "iceShape")), str(o, "bubble"), o.get("bubbleColor").getAsInt(), str(o, "bomb"), o.get("bombShot").getAsInt(),
                List.copyOf(pour), str(o, "after"));
    }

    private static Bottle bottle(JsonObject b, String where, int vesselCount) {
        Bottle bottle = new Bottle(str(b, "kind"), b.get("vessel").getAsInt(), b.get("color").getAsInt(), b.get("label").getAsInt(),
                b.get("cap").getAsInt(), b.get("liquid").getAsInt(), b.get("stream").getAsInt());
        check(bottle.vessel >= 0 && bottle.vessel < vesselCount, where + ": 그릇 " + bottle.vessel);
        return bottle;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        check(e != null && !e.isJsonNull(), "필드 없음: " + k);
        return e.getAsString();
    }

    private static double[] doubles(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    private static int[] ints(JsonArray a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
        return out;
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new IllegalArgumentException("jjan 카탈로그 오류: " + msg);
    }

    // ------------------------------------------------------------------ 조회

    public List<Category> categories() { return categories; }
    public List<String> phrases() { return phrases; }
    public List<Glass> glasses() { return glasses; }
    public List<Drink> drinks() { return drinks; }
    public Glass glass(String id) { return glassById.get(id); }
    public List<Vessel> vessels() { return vessels; }
    public Shaker shaker() { return shaker; }
    /** 떠다니는 것 정의. 없으면 null. */
    public Floater floater(String id) { return id == null || id.isEmpty() ? null : floaters.get(id); }
    public Drink drink(String id) { return drinkById.get(id); }

    /** 메뉴에 보이는 잔 (내부 연출용 dropshot 제외). */
    public List<Glass> publicGlasses() {
        List<Glass> out = new ArrayList<>();
        for (Glass g : glasses) if (!g.hidden) out.add(g);
        return out;
    }

    public List<Drink> drinksIn(String category) {
        List<Drink> out = new ArrayList<>();
        for (Drink d : drinks) if (d.category.equals(category)) out.add(d);
        return out;
    }
}
