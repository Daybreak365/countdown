package dev.newyear.countdown.jjan;

import java.util.ArrayList;
import java.util.List;

/**
 * 술 만드는 과정의 시간표. 웹 원작(glass.js 의 startPourSeq / startPour)과 같은 순서·같은 길이다:
 * 그릇마다 [나타남 0.3초] → [준비: 얼음·재료 넣기·뚜껑·섞기(셰이크/스터/블렌드) 또는 코르크·캔 따기·막걸리 뒤집기·소주 회오리]
 * → [들어 올려 0.42초] → [따르기] → [되돌려 놓기 0.6초]. 여러 그릇을 쓰는 술은 차례로 이어진다.
 * 서버는 이 길이만큼 "준비 중"으로 두고, 클라이언트는 같은 시간표로 그릇·재료·물줄기·잔량을 그린다. Minecraft 와 무관한 순수 계산.
 */
public final class JjanPour {
    /** 그릇이 나타나는 시간(초). */
    public static final double APPEAR = 0.3;
    /** 다 따른 뒤의 마무리 연출 길이(초): 샷잔 빠뜨리기, 회오리, 그레나딘 가라앉기, 잔 안 젓기. 그동안에도 마실 수 있다. */
    public static final double DROP_TIME = 3.2, SINK_TIME = 2.7, STIR_TIME = 1.95, GARNISH_POP = 0.32;

    public record Phase(String name, double t0, double dur, int ing) {}

    public static final class Step {
        public final JjanCatalog.Step def;
        public final boolean first, last, mixing;
        /** 따르기 전에 하는 일: shake, stir, blend, cork, can, flip, swirl 또는 빈 문자열. */
        public final String kind;
        public final List<Phase> phases;
        /** 이 그릇의 시작 시각(전체 기준)과, 그 안에서의 시각들: 준비 끝(들어 올리기 시작), 따르기 시작·끝, 끝. */
        public final double start, t0, pour0, pour1, end, act;
        public final double from, to, carb;
        public final boolean hot;
        /** 잔 속 술 색 덮기: 따르기 전 색(-1 = 원래 색), 따르는 동안 tintFrom → tintTarget, 끝난 뒤 색(-1 = 원래 색). */
        public final int tintBefore, tintFrom, tintTarget, tintAfter;
        public final boolean tinted;

        Step(JjanCatalog.Step def, boolean first, boolean last, String kind, List<Phase> phases, double start, double act,
             double from, double to, double carb, boolean hot, int tintBefore, int tintFrom, int tintTarget, int tintAfter, boolean tinted) {
            this.def = def;
            this.first = first;
            this.last = last;
            this.kind = kind;
            this.mixing = kind.equals("shake") || kind.equals("stir") || kind.equals("blend");
            this.phases = phases;
            this.start = start;
            this.act = act;
            double p = 0;
            for (Phase ph : phases) p = Math.max(p, ph.t0 + ph.dur);
            this.t0 = APPEAR + p;
            double span = Math.max(0.05, to - from);
            double dur = def.dur() > 0 ? def.dur() : Math.max(0.55, 1.45 * span) * (def.layer() ? 1.7 : 1);
            this.pour0 = t0 + 0.42;
            this.pour1 = pour0 + dur;
            this.end = pour1 + 0.6;
            this.from = from;
            this.to = to;
            this.carb = carb;
            this.hot = hot;
            this.tintBefore = tintBefore;
            this.tintFrom = tintFrom;
            this.tintTarget = tintTarget;
            this.tintAfter = tintAfter;
            this.tinted = tinted;
        }

        public Phase phase(String name) {
            for (Phase p : phases) if (p.name.equals(name)) return p;
            return null;
        }

        /** 준비 단계 시각 tp(나타난 뒤부터)에 진행 중인 단계. 없으면 null. */
        public Phase current(double tp) {
            Phase cur = null;
            for (Phase p : phases) if (tp >= p.t0) cur = p;
            return cur;
        }

        /** 이 그릇 안의 시각 t(초)에서 잔에 찬 양. */
        public double fill(double t) {
            if (t <= pour0) return from;
            if (t >= pour1) return to;
            return from + (to - from) * smooth((t - pour0) / (pour1 - pour0));
        }
    }

    public final List<Step> steps;
    /** 전체 길이(초). */
    public final double total;

    private JjanPour(List<Step> steps) {
        this.steps = steps;
        Step last = steps.get(steps.size() - 1);
        this.total = last.start + last.end;
    }

    public static double smooth(double t) {
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        return t * t * (3 - 2 * t);
    }

    public static double prepTime(String kind) {
        return switch (kind) {
            case "shake" -> 1.15;
            case "stir" -> 1.3;
            case "blend" -> 1.4;
            case "cork" -> 1.0;
            case "can" -> 0.6;
            case "flip" -> 1.35;
            case "swirl" -> 1.2;
            default -> 0;
        };
    }

    /**
     * look 의 술을 f0 만큼 차 있는 잔에 가득 따르는 시간표. fresh = 새로 주문한 잔(병을 새로 딴다),
     * 아니면 리필(이미 딴 병·캔은 다시 따지 않는다. 칵테일은 다시 섞는다).
     */
    public static JjanPour of(JjanCatalog cat, JjanCatalog.Look look, boolean fresh, double f0) {
        List<JjanCatalog.Step> defs = look.pour();
        List<Step> out = new ArrayList<>();
        double at = 0, from = f0;
        int tint = -1;
        for (int i = 0; i < defs.size(); i++) {
            JjanCatalog.Step st = defs.get(i);
            boolean last = i == defs.size() - 1;
            double to = last ? 1 : f0 + (1 - f0) * (st.to() >= 0 ? st.to() : (i + 1) / (double) defs.size());
            String kind = st.prep();
            if (!fresh && (kind.equals("cork") || kind.equals("can") || kind.equals("swirl"))) kind = "";
            double act = prepTime(kind);
            boolean mixing = kind.equals("shake") || kind.equals("stir") || kind.equals("blend");
            List<Phase> phases = new ArrayList<>();
            double acc = 0;
            if (mixing) {
                if (kind.equals("stir")) { phases.add(new Phase("ice", acc, 0.75, -1)); acc += 0.75; }
                for (int k = 0; k < st.build().size(); k++) {
                    double d = st.build().get(k).dash() ? 0.8 : 1.1;
                    phases.add(new Phase("ing", acc, d, k));
                    acc += d;
                }
                if (!kind.equals("stir")) {
                    phases.add(new Phase("ice", acc, 0.75, -1));
                    acc += 0.75;
                    phases.add(new Phase("cap", acc, 0.45, -1));
                    acc += 0.45;
                }
                phases.add(new Phase("act", acc, act, -1));
                acc += act;
                phases.add(new Phase("fin", acc, kind.equals("stir") ? 0.4 : 0.45, -1));
            } else if (!kind.isEmpty()) {
                phases.add(new Phase("act", 0, act, -1));
            }
            double carb = st.carb() >= 0 ? st.carb() : last ? look.carb() : 0;
            JjanCatalog.Vessel v = cat.vessels().get(st.bottle().vessel());
            boolean hot = st.hot() >= 0 ? st.hot() == 1 : look.hot() && (v.spout() || v.kind().equals("carafe"));
            // 층을 쌓는 술은 섞지 않고, 나머지는 따르는 동안 중간 색으로 물들인다
            boolean useTint = !look.layered();
            int stream = st.bottle().stream();
            int tintFrom = tint >= 0 ? tint : from > 0.02 ? look.color() : stream;
            int tintTo = useTint ? (st.tint() >= 0 ? st.tint() : last ? -1 : stream) : -1;
            boolean tinted = useTint && (tintTo >= 0 || tint >= 0);
            int before = tint;
            int target = tintTo >= 0 ? tintTo : look.color();
            if (tinted) tint = target;
            if (last && tintTo < 0) tint = -1;
            out.add(new Step(st, i == 0, last, kind, List.copyOf(phases), at, act, from, to, carb, hot, before, tintFrom, target, tint, tinted));
            Step made = out.get(out.size() - 1);
            at += made.end;
            from = to;
        }
        return new JjanPour(List.copyOf(out));
    }

    /** 서버가 "준비 중"으로 두는 틱 수. */
    public int ticks() {
        return (int) Math.ceil(total * 20 - 1e-6);
    }

    /** 전체 시각 t(초)에 진행 중인 그릇. 끝난 뒤에는 마지막 그릇. */
    public Step at(double t) {
        Step cur = steps.get(0);
        for (Step s : steps) if (t >= s.start) cur = s;
        return cur;
    }

    /** 전체 시각 t(초)에서 잔에 찬 양 (0..1). */
    public double fill(double t) {
        Step s = at(t);
        return s.fill(t - s.start);
    }

    // ------------------------------------------------------------------ 다 따른 뒤

    /** 회오리(소맥), 살살 젓기(오십세주), 와인 스월링: {세기, 길이(초)}. 없으면 null. */
    public static double[] tornado(JjanCatalog.Look look) {
        if (look.bomb().equals("tornado")) return new double[]{1, 2.8};
        if (look.bomb().equals("stir")) return new double[]{0.55, 2.2};
        if (look.after().equals("swirl")) return new double[]{0.45, 2.0};
        return null;
    }

    /** 다 따른 뒤 t 초에 술이 도는 세기 (액면이 가운데로 패인다). 잔 안을 젓는 술(올드 패션드, 모히토)도 포함. */
    public static double swirl(JjanCatalog.Look look, double t) {
        if (t < 0) return 0;
        double s = 0;
        double[] tor = tornado(look);
        if (tor != null && t < tor[1]) {
            double fall = Math.min(1, tor[1] * 0.36);
            s = tor[0] * smooth(t / 0.5) * (1 - smooth((t - (tor[1] - fall)) / fall));
        }
        if (look.after().equals("stir") && t < STIR_TIME) s = Math.max(s, 0.3 * stirIn(t));
        return s;
    }

    /** 다 따른 뒤 t 초까지 술이 돈 양(swirl 의 적분, 초). 얼음과 건더기가 이만큼 함께 돈다. */
    public static double swirlTurn(JjanCatalog.Look look, double t) {
        if (t <= 0 || (tornado(look) == null && !look.after().equals("stir"))) return 0;
        double end = Math.min(t, 3.0), sum = 0, h = 0.02;
        for (double x = h / 2; x < end; x += h) sum += swirl(look, x) * h;
        return sum;
    }

    /** 잔 안을 젓는 바 스푼이 들어와 있는 정도 0..1. */
    public static double stirIn(double t) {
        return smooth(t / 0.25) * (1 - smooth((t - 1.6) / 0.3));
    }

    /** 폭탄주의 샷잔이 높이 drop(복셀) 위에서 떨어져 술에 닿기까지 걸리는 시간(초). 원작 중력 260. */
    public static double dropFall(double drop) {
        return Math.sqrt(2 * Math.max(0, drop) / 260.0);
    }

    /** 다 따른 뒤 샷잔을 빠뜨리는 술인지 (고진감래는 처음부터 잔 속에 샷잔이 들어 있다). */
    public static boolean drops(String drinkId, JjanCatalog.Look look) {
        return look.bomb().equals("drop") && look.bombShot() >= 0 && !drinkId.equals("gojingamrae");
    }

    /** 불붙은 술을 빨대로 마시는 시간(초): 꽂고 0.4, 빨아들이기 1.05, 뽑기. */
    public static final double STRAW_SECONDS = 2.1;

    public static int strawTicks() {
        return (int) Math.ceil(STRAW_SECONDS * 20);
    }

    /** 빨대로 마실 때 t 초에 남은 비율 (시작 양 대비). */
    public static double strawLeft(double t) {
        if (t <= 0.4) return 1;
        if (t >= 1.45) return 0;
        return 1 - smooth((t - 0.4) / 1.05);
    }
}
