package dev.newyear.countdown.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.newyear.countdown.client.GeoBuffers;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 블록 변경 시뮬레이션 미리보기. 서버가 보낸 "계획된 블록 변경"을 반투명 유령 블록으로 그린다 (이 클라이언트에만 보이고 세계는 그대로).
 * 실제 블록 모델을 반투명으로 표시하고, 위치 조절 중 서버가 거절한 칸은 붉게 강조한다.
 * 관리자 미리보기 화면(BlockPreviewScreen)도 같은 데이터를 쓴다.
 */
public final class BlockPreview {
    private BlockPreview() {}

    private static final Identifier T_WHITE = Identifier.withDefaultNamespace("textures/block/white_concrete.png");
    private static final Map<String,BlockState> STATES=new LinkedHashMap<>();

    public static final class Cell {
        public final int x, y, z;
        public final String state;
        public final boolean replaces;
        public final int color;
        public final boolean removal;
        public final BlockState blockState;

        Cell(int x, int y, int z, String state, boolean replaces) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
            this.replaces = replaces;
            // The same palette is reused on every local movement tick; parse each state only once.
            if(STATES.size()>=4096)STATES.clear();
            this.blockState=STATES.computeIfAbsent(state,s->{
                var parsed=dev.newyear.countdown.ai.script.api.McUtil.parseBlockString(s);
                return parsed==null?Blocks.STONE.defaultBlockState():parsed;
            });
            Block b = blockState.getBlock();
            this.removal = b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR;
            int c = removal ? 0xE05050 : b.defaultMapColor().col;
            this.color = c == 0 ? 0xC8C8C8 : c;
        }

        public String id() {
            int i = state.indexOf('[');
            return i < 0 ? state : state.substring(0, i);
        }
    }

    public static final class Preview {
        public String id, title, dimension;
        public int total;
        public boolean truncated;
        public long expiresAt;
        public final List<Cell> cells = new ArrayList<>();
        final Set<Long> occupied = new HashSet<>();
        final Map<Long,Cell> byPosition = new LinkedHashMap<>();
        public int minX, minY, minZ, maxX, maxY, maxZ;
    }

    public static final Map<String, Preview> ACTIVE = new LinkedHashMap<>();

    public static Block block(String state) {
        int i = state.indexOf('[');
        String id = i < 0 ? state : state.substring(0, i);
        try {
            Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
            return b == null ? Blocks.STONE : b;
        } catch (RuntimeException e) {
            return Blocks.STONE;
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** 서버의 block_preview 연산을 읽는다. 화면 미리보기용으로 같은 형식을 바로 Preview 로 바꿔 쓸 수도 있다. */
    public static Preview parse(JsonObject o) {
        Preview p = new Preview();
        p.id = o.has("id") ? o.get("id").getAsString() : "preview";
        p.title = o.has("title") ? o.get("title").getAsString() : "";
        p.total = o.has("total") ? o.get("total").getAsInt() : 0;
        p.truncated = o.has("truncated") && o.get("truncated").getAsBoolean();
        p.dimension = o.has("dimension") ? o.get("dimension").getAsString() : "minecraft:overworld";
        int secs = o.has("seconds") ? o.get("seconds").getAsInt() : 30;
        p.expiresAt = secs <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + secs * 1000L;
        if (!o.has("origin") || !o.has("palette") || !o.has("b")) return p;
        JsonArray origin = o.getAsJsonArray("origin");
        int ox = origin.get(0).getAsInt(), oy = origin.get(1).getAsInt(), oz = origin.get(2).getAsInt();
        List<String> palette = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("palette")) palette.add(e.getAsString());
        JsonArray b = o.getAsJsonArray("b");
        p.minX = p.minY = p.minZ = Integer.MAX_VALUE;
        p.maxX = p.maxY = p.maxZ = Integer.MIN_VALUE;
        for (int i = 0; i + 4 < b.size(); i += 5) {
            int x = ox + b.get(i).getAsInt(), y = oy + b.get(i + 1).getAsInt(), z = oz + b.get(i + 2).getAsInt();
            int pi = b.get(i + 3).getAsInt();
            String st = pi >= 0 && pi < palette.size() ? palette.get(pi) : "minecraft:stone";
            Cell c = new Cell(x, y, z, st, b.get(i + 4).getAsInt() == 1);
            p.cells.add(c);
            p.occupied.add(key(x, y, z));
            p.byPosition.put(key(x,y,z),c);
            p.minX = Math.min(p.minX, x); p.minY = Math.min(p.minY, y); p.minZ = Math.min(p.minZ, z);
            p.maxX = Math.max(p.maxX, x); p.maxY = Math.max(p.maxY, y); p.maxZ = Math.max(p.maxZ, z);
        }
        return p;
    }

    public static void apply(JsonObject o) {
        Preview p = parse(o);
        ACTIVE.put(p.id, p);
        while (ACTIVE.size() > 4) ACTIVE.remove(ACTIVE.keySet().iterator().next());
    }

    public static void clear(String id) {
        BuildHologram.clear(id);
        if (id == null || id.isEmpty()) {ACTIVE.clear();STATES.clear();MODELS.clear();}
        else ACTIVE.remove(id);
    }

    public static Preview latest() {
        Preview last = null;
        for (Preview p : ACTIVE.values()) last = p;
        return last;
    }

    public static Preview fromCells(String id,String title,String dimension,List<Cell> cells){
        Preview p=new Preview();p.id=id;p.title=title;p.dimension=dimension;p.expiresAt=Long.MAX_VALUE;p.total=cells.size();
        p.minX=p.minY=p.minZ=Integer.MAX_VALUE;p.maxX=p.maxY=p.maxZ=Integer.MIN_VALUE;
        for(var c:cells){
            p.cells.add(c);p.occupied.add(key(c.x,c.y,c.z));p.byPosition.put(key(c.x,c.y,c.z),c);
            p.minX=Math.min(p.minX,c.x);p.minY=Math.min(p.minY,c.y);p.minZ=Math.min(p.minZ,c.z);
            p.maxX=Math.max(p.maxX,c.x);p.maxY=Math.max(p.maxY,c.y);p.maxZ=Math.max(p.maxZ,c.z);
        }
        return p;
    }

    // ---------------------------------------------------------------- 월드에 그리기

    public static void render(LevelRenderContext ctx) {
        if (ACTIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        ACTIVE.values().removeIf(p -> p.expiresAt < now);
        if (ACTIVE.isEmpty()) return;
        String dim = mc.level.dimension().identifier().toString();
        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        PoseStack m = ctx.poseStack();
        m.pushPose();
        m.translate(-cam.x, -cam.y, -cam.z);
        GeoBuffers buf = new GeoBuffers();
        VertexConsumer vc = buf.getBuffer(RenderTypes.entityTranslucent(T_WHITE));
        PoseStack.Pose e = m.last();
        float pulse = 0.75f + 0.25f * (float) Math.sin(now / 260.0);
        int drawn = 0;
        for (Preview p : ACTIVE.values()) {
            if (!p.dimension.equals(dim)) continue;
            int index=0;
            for (Cell c : p.cells) {
                int tint=BuildHologram.tint(p,index++);
                if (drawn++ >= 4096) break;
                if (mc.player.distanceToSqr(c.x + 0.5, c.y + 0.5, c.z + 0.5) > 96 * 96) continue;
                int alpha = c.removal ? 0x50 : Math.round(0x68 * pulse);
                boolean editing=BuildHologram.positioning(p);
                if(c.removal)cube(vc,e,p,c,.02f,((editing?0x24:alpha)<<24)|(editing?tint:0xFF5555),false);
                else if(!model(mc,buf,m,p,c,alpha,tint))cube(vc,e,p,c,.02f,(alpha<<24)|(tint==0xFF5555?tint:c.color),true);
                if(editing && tint==0xFF5555)cube(vc,e,p,c,-.015f,(0x60<<24)|0xFF5555,false);
                else if(c.removal && !editing)cube(vc,e,p,c,-.015f,(Math.round(0x90*pulse)<<24)|0xFF4040,false);
            }
            // 위치를 잡는 동안에는 설계 전체의 테두리를 둘러, 차지하는 범위와 배치 가능 여부를 한눈에 보이게 한다.
            if (BuildHologram.positioning(p) && !p.cells.isEmpty())
                frame(vc, e, p.minX, p.minY, p.minZ, p.maxX + 1, p.maxY + 1, p.maxZ + 1, (Math.round(0xD0 * pulse) << 24) | BuildHologram.frameColor());
        }
        m.popPose();
        buf.flush(ctx.submitNodeCollector());
    }

    private record Face(Direction cull,BakedQuad quad) {}
    private static final Map<BlockState,List<Face>> MODELS=new LinkedHashMap<>();
    private static Object modelSet;
    /** Use real baked geometry and textures, so stairs/slabs and their rotated orientation are visible. */
    private static boolean model(Minecraft mc,GeoBuffers buffers,PoseStack pose,Preview preview,Cell cell,int alpha,int tint){
        var set=mc.getModelManager().getBlockStateModelSet();
        if(modelSet!=set){modelSet=set;MODELS.clear();}
        List<Face> faces=MODELS.get(cell.blockState);
        if(faces==null){
            if(MODELS.size()>=4096)MODELS.clear();
            faces=new ArrayList<>();var parts=new ArrayList<BlockStateModelPart>();
            set.get(cell.blockState).collectParts(net.minecraft.util.RandomSource.create(42),parts);
            for(var part:parts){
                for(var quad:part.getQuads(null))faces.add(new Face(null,quad));
                for(var direction:Direction.values())for(var quad:part.getQuads(direction))faces.add(new Face(direction,quad));
            }
            MODELS.put(cell.blockState,faces);
        }
        if(faces.isEmpty())return false;
        pose.pushPose();pose.translate(cell.x,cell.y,cell.z);
        var instance=new QuadInstance();instance.setLightCoords(0xF000F0);instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        for(var face:faces){
            if(face.cull!=null){
                var d=face.cull;var neighbor=preview.byPosition.get(key(cell.x+d.getStepX(),cell.y+d.getStepY(),cell.z+d.getStepZ()));
                if(neighbor!=null && !neighbor.removal && neighbor.blockState.isSolidRender())continue;
            }
            var material=face.quad.materialInfo();int rgb=0xFFFFFF;
            if(material.isTinted()){
                var color=mc.getBlockColors().getTintSource(cell.blockState,material.tintIndex());
                if(color!=null)rgb=color.color(cell.blockState);
            }
            int red=((rgb>>16)&255)*((tint>>16)&255)/255,green=((rgb>>8)&255)*((tint>>8)&255)/255,blue=(rgb&255)*(tint&255)/255;
            instance.setColor((alpha<<24)|(red<<16)|(green<<8)|blue);
            buffers.getBuffer(RenderTypes.entityTranslucent(material.sprite().atlasLocation())).putBakedQuad(pose.last(),face.quad,instance);
        }
        pose.popPose();return true;
    }

    /** inset>0 이면 칸 안쪽으로 줄인 상자, cull 이면 이웃 미리보기 칸과 맞닿은 면은 그리지 않는다. */
    private static void cube(VertexConsumer vc, PoseStack.Pose e, Preview p, Cell c, float inset, int argb, boolean cull) {
        float x0 = c.x + inset, y0 = c.y + inset, z0 = c.z + inset, x1 = c.x + 1 - inset, y1 = c.y + 1 - inset, z1 = c.z + 1 - inset;
        if (!cull || !p.occupied.contains(key(c.x, c.y + 1, c.z))) quad(vc, e, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, argb, 0, 1, 0);
        if (!cull || !p.occupied.contains(key(c.x, c.y - 1, c.z))) quad(vc, e, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, argb, 0, -1, 0);
        if (!cull || !p.occupied.contains(key(c.x, c.y, c.z - 1))) quad(vc, e, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, argb, 0, 0, -1);
        if (!cull || !p.occupied.contains(key(c.x, c.y, c.z + 1))) quad(vc, e, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, argb, 0, 0, 1);
        if (!cull || !p.occupied.contains(key(c.x - 1, c.y, c.z))) quad(vc, e, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, argb, -1, 0, 0);
        if (!cull || !p.occupied.contains(key(c.x + 1, c.y, c.z))) quad(vc, e, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, argb, 1, 0, 0);
    }

    /** 상자의 모서리 12개를 가는 막대로 그린다. */
    private static void frame(VertexConsumer vc, PoseStack.Pose e, float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
        float t = 0.04f;
        x0 -= t; y0 -= t; z0 -= t; x1 += t; y1 += t; z1 += t;
        for (float y : new float[]{y0, y1 - t}) {
            box(vc, e, x0, y, z0, x1, y + t, z0 + t, argb);
            box(vc, e, x0, y, z1 - t, x1, y + t, z1, argb);
            box(vc, e, x0, y, z0 + t, x0 + t, y + t, z1 - t, argb);
            box(vc, e, x1 - t, y, z0 + t, x1, y + t, z1 - t, argb);
        }
        for (float x : new float[]{x0, x1 - t})
            for (float z : new float[]{z0, z1 - t})
                box(vc, e, x, y0 + t, z, x + t, y1 - t, z + t, argb);
    }

    private static void box(VertexConsumer vc, PoseStack.Pose e, float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
        quad(vc, e, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, argb, 0, 1, 0);
        quad(vc, e, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, argb, 0, -1, 0);
        quad(vc, e, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, argb, 0, 0, -1);
        quad(vc, e, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, argb, 0, 0, 1);
        quad(vc, e, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, argb, -1, 0, 0);
        quad(vc, e, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, argb, 1, 0, 0);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose e, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int argb, float nx, float ny, float nz) {
        v(vc, e, ax, ay, az, argb, nx, ny, nz);
        v(vc, e, bx, by, bz, argb, nx, ny, nz);
        v(vc, e, cx, cy, cz, argb, nx, ny, nz);
        v(vc, e, dx, dy, dz, argb, nx, ny, nz);
    }

    private static void v(VertexConsumer vc, PoseStack.Pose e, float x, float y, float z, int argb, float nx, float ny, float nz) {
        vc.addVertex(e, x, y, z).setColor(argb).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(e, nx, ny, nz);
    }
}
