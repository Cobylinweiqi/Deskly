package com.coby.deskly.organize;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 归位建议器：给一个"散落图标"算出它最该进哪个<b>已有</b>文件夹。
 *
 * <p>核心思路是<b>反推</b>而不是预设：不假设"银行文件夹应该装什么"，
 * 而是看这个文件夹里已经装了什么，得到它的"主题画像"，再拿候选应用的主题去比对。
 * 这样用户手工调过的分类习惯会被自动继承——他要是把"滴滴"放进了"生活服务"，
 * 那以后所有网约车类应用都会被建议进"生活服务"。
 *
 * <p>三路信号加权：
 * <ol>
 *   <li><b>主题覆盖</b>（权重 1.0）：候选应用的主题有几个能在该文件夹找到同类；</li>
 *   <li><b>主题密度</b>（权重 0.6）：命中的主题在这个文件夹里占多大比例，越"专"的文件夹越可信；</li>
 *   <li><b>同厂商</b>（权重 2.0）：包名第二段相同，比如建行生活 {@code com.ccb.*}
 *       与"中国建设银行" {@code com.ccb.*}。这是最强的信号，一条就够定案。</li>
 * </ol>
 */
public final class FolderMatcher {

    private static final double W_COVER = 1.0;
    private static final double W_DENSITY = 0.6;
    private static final double W_BRAND = 2.0;

    /** 低于这个分数就不建议了，宁可让用户自己选，也不给一条没把握的建议。 */
    private static final double MIN_SCORE = 0.55;
    /** 第一名与第二名差距小于此值时，标记为"不太确定"。 */
    private static final double SHAKY_GAP = 0.35;
    /** 已在文件夹里的图标，别处的分数要高出这么多才值得建议搬动。 */
    private static final double MIN_MOVE_GAIN = 0.8;

    public static final class Suggestion {
        /** 散落在桌面上，还没进任何文件夹。 */
        public static final int STATUS_LOOSE = 0;
        /** 已经在文件夹里，但存在明显更合适的去处。 */
        public static final int STATUS_MOVE = 1;
        /** 已经在文件夹里，位置合适（或不值得动）。 */
        public static final int STATUS_IN_PLACE = 2;
        /** 认不出类别，且没在文件夹里——只能让用户自己选。 */
        public static final int STATUS_UNKNOWN = 3;
        /** 热区（底部固定栏）图标：天然是刻意放的，不做建议。 */
        public static final int STATUS_HOTSEAT = 4;

        public final DesktopModel.Item item;
        /** 建议的目标文件夹；为 null 表示没有合适的。 */
        public final DesktopModel.Folder target;
        public final double score;
        public final String reason;
        /** 分数接近的其它候选，供用户改选。 */
        public final List<DesktopModel.Folder> alternatives = new ArrayList<>();
        /** 第一名与第二名的差距太小，建议打问号。 */
        public boolean shaky;
        /** 它现在所在的文件夹；null 表示散落或热区。 */
        public DesktopModel.Folder current;
        /** 见 STATUS_*。 */
        public int status = STATUS_LOOSE;
        /** 每个候选文件夹的得分，判定"该不该挪"时要拿当前所在的那个来比。 */
        final Map<Integer, Double> scores = new HashMap<>();

        Suggestion(DesktopModel.Item item, DesktopModel.Folder target, double score, String reason) {
            this.item = item;
            this.target = target;
            this.score = score;
            this.reason = reason;
        }

        public boolean hasTarget() {
            return target != null;
        }

        /** 需要用户动手的：散落待归位，或建议挪走。 */
        public boolean actionable() {
            return status == STATUS_LOOSE || status == STATUS_MOVE;
        }
    }

    private final Map<Integer, Map<String, Integer>> profiles = new HashMap<>();
    private final Map<Integer, Set<String>> brandSegments = new HashMap<>();
    private final Context ctx;

    public FolderMatcher(Context ctx, DesktopModel model) {
        this.ctx = ctx;
        for (DesktopModel.Folder f : model.folders) {
            Map<String, Integer> p = new HashMap<>();
            Set<String> segs = new LinkedHashSet<>();
            for (DesktopModel.Item m : f.members) {
                for (String t : Semantic.topicsOf(m.title, m.pkg)) {
                    p.merge(t, 1, Integer::sum);
                }
                String seg = secondSegment(m.pkg);
                if (!seg.isEmpty()) {
                    segs.add(seg);
                }
            }
            profiles.put(f.id, p);
            brandSegments.put(f.id, segs);
        }
    }

    /**
     * 分析桌面上<b>所有</b>图标，不只是散落的。
     *
     * <p>范围包括：文件夹内的成员、桌面散落的图标、热区图标。散落的给"去哪"，
     * 文件夹内的成员给"要不要挪"——只有当别处明显更合适（分差 ≥ {@link #MIN_MOVE_GAIN}）
     * 才建议挪，因为用户的文件夹是他长期手工调出来的，机器比分高一点就劝人搬家是冒犯。
     *
     * <p>会为每个图标解析主题，认不出时可能查一次 APK 结构，所以调用方应放在后台线程。
     */
    public List<Suggestion> suggestAll(DesktopModel model) {
        List<Suggestion> out = new ArrayList<>();

        for (DesktopModel.Folder f : model.folders) {
            for (DesktopModel.Item m : f.members) {
                if (!m.isApp()) {
                    continue;
                }
                Suggestion s = suggestOne(m, model.folders);
                s.current = f;
                s.status = statusInFolder(s, f);
                out.add(s);
            }
        }
        for (DesktopModel.Item it : model.looseOnDesktop) {
            if (!it.isApp()) {
                continue;
            }
            Suggestion s = suggestOne(it, model.folders);
            s.status = s.target == null ? Suggestion.STATUS_UNKNOWN : Suggestion.STATUS_LOOSE;
            out.add(s);
        }
        for (DesktopModel.Item it : model.hotseat) {
            if (!it.isApp()) {
                continue;
            }
            Suggestion s = suggestOne(it, model.folders);
            s.status = Suggestion.STATUS_HOTSEAT;
            out.add(s);
        }
        return out;
    }

    /**
     * 已在文件夹里的图标，判"该不该挪"。
     *
     * <p>算当前文件夹得分时有个细节：这个图标的主题本来就被算进了它自己所在文件夹的画像里，
     * 等于它给自己投了一票。这会让"原地不动"天然占优——方向是对的，宁可漏判也不要误劝。
     */
    private static int statusInFolder(Suggestion s, DesktopModel.Folder cur) {
        if (s.target == null || s.target.id == cur.id) {
            return Suggestion.STATUS_IN_PLACE;
        }
        Double curScore = s.scores.get(cur.id);
        double c = curScore == null ? 0 : curScore;
        return (s.score - c >= MIN_MOVE_GAIN) ? Suggestion.STATUS_MOVE : Suggestion.STATUS_IN_PLACE;
    }

    private Suggestion suggestOne(DesktopModel.Item it, List<DesktopModel.Folder> folders) {
        List<String> topics = Semantic.topicsOf(it.title, it.pkg);
        String extra = "";
        if (topics.isEmpty() && !it.pkg.isEmpty() && Semantic.isXposedModule(ctx, it.pkg)) {
            // 模块类应用没有任何可写的关键词，靠 APK 里的 META-INF/xposed/ 认出来
            topics = Collections.singletonList(Semantic.DEV);
            extra = "（识别为 Xposed 模块）";
        }
        if (topics.isEmpty()) {
            return new Suggestion(it, null, 0, "认不出它的类别" + extra);
        }

        String brand = secondSegment(it.pkg);
        List<Scored> scored = new ArrayList<>();
        for (DesktopModel.Folder f : folders) {
            Map<String, Integer> p = profiles.get(f.id);
            if (p == null || p.isEmpty()) {
                continue;
            }
            int total = 0;
            for (int v : p.values()) {
                total += v;
            }
            int hits = 0;
            int densityHits = 0;
            for (String t : topics) {
                Integer c = p.get(t);
                if (c != null && c > 0) {
                    hits++;
                    densityHits += c;
                }
            }
            if (hits == 0) {
                continue;
            }
            double cover = (double) hits / topics.size();
            double density = total > 0 ? (double) densityHits / total : 0;
            boolean sameBrand = !brand.isEmpty()
                    && brandSegments.get(f.id) != null
                    && brandSegments.get(f.id).contains(brand);
            double score = W_COVER * cover + W_DENSITY * Math.min(1.0, density)
                    + (sameBrand ? W_BRAND : 0);

            String why = reasonOf(topics, p, sameBrand, f, extra);
            scored.add(new Scored(f, score, why));
        }

        if (scored.isEmpty()) {
            return new Suggestion(it, null, 0, "现有 " + folders.size() + " 个文件夹里没有同类（主题："
                    + Semantic.describe(topics) + "）");
        }
        Collections.sort(scored, (a, b) -> Double.compare(b.score, a.score));

        Scored best = scored.get(0);
        Suggestion s = new Suggestion(it, best.folder, best.score, best.why);
        // 留一份全量得分：判"该不该从当前文件夹挪走"时要拿它所在的那个来比
        for (Scored sc : scored) {
            s.scores.put(sc.folder.id, sc.score);
        }
        if (best.score < MIN_SCORE) {
            // 有候选但都不够格：保留最强的那个当"勉强建议"，同时标明不确定
            s.shaky = true;
            return s;
        }
        for (int i = 1; i < scored.size() && s.alternatives.size() < 3; i++) {
            s.alternatives.add(scored.get(i).folder);
        }
        if (scored.size() > 1 && best.score - scored.get(1).score < SHAKY_GAP) {
            s.shaky = true;
        }
        return s;
    }

    private String reasonOf(List<String> topics, Map<String, Integer> profile,
                            boolean sameBrand, DesktopModel.Folder f, String extra) {
        StringBuilder sb = new StringBuilder();
        if (sameBrand) {
            sb.append("同厂商应用已在「").append(f.title).append("」");
        }
        for (String t : topics) {
            Integer c = profile.get(t);
            if (c != null && c > 0) {
                if (sb.length() > 0) {
                    sb.append("，");
                }
                sb.append("同属「").append(t).append("」（该文件夹已有 ").append(c).append(" 个）");
            }
        }
        if (sb.length() == 0) {
            sb.append("暂无明确依据");
        }
        return sb.append(extra).toString();
    }

    /**
     * 不代表厂商的通用包名段。
     *
     * <p>拿 {@code com.android.*} 当"同厂商"证据，会让短信和设置、联系人互相认亲戚——
     * 结论虽然往往正确，但理由站不住脚，用户一看就会怀疑这建议是瞎蒙的。
     * 宁可少一条证据，也不要一条假的。
     */
    private static final Set<String> GENERIC_SEGMENTS = new java.util.HashSet<>(
            java.util.Arrays.asList("android", "google", "java", "kotlin", "apache",
                    "github", "example", "sample", "app", "apps", "mobile", "client"));

    /** 包名第二段，{@code com.ccb.longjiLife → ccb}。用来识别同厂商。 */
    static String secondSegment(String pkg) {
        if (pkg == null) {
            return "";
        }
        String[] p = pkg.toLowerCase(Locale.US).split("\\.");
        if (p.length < 2) {
            return "";
        }
        return GENERIC_SEGMENTS.contains(p[1]) ? "" : p[1];
    }

    private static final class Scored {
        final DesktopModel.Folder folder;
        final double score;
        final String why;

        Scored(DesktopModel.Folder folder, double score, String why) {
            this.folder = folder;
            this.score = score;
            this.why = why;
        }
    }
}
