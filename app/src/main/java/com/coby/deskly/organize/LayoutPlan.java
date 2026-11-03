package com.coby.deskly.organize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 分屏布局规划：把分组结果排到「第几屏、放几个文件夹」。
 *
 * <p>这里只算布局，不写桌面。之所以仍然要算，是因为「第一屏留空」「这一组放到第三屏」
 * 这类配置如果只在预览里改个值、看不到任何结果，它就等于没有——用户没法在动手前判断
 * 到底空出了多少、有没有排到别的屏去。
 *
 * <p>容量按真机实测：launcher4x6 是 4 列 × 6 行，而文件夹占 2×2 格，
 * 所以一屏只能放 <b>6</b> 个文件夹，不是 24。这个数字来自
 * {@code /data/user_de/0/com.miui.home/databases/launcher4x6.db} 里的实际坐标，
 * 不是推测——按 24 算会把"第 5 屏"的组错误地摊到第 2、3 屏。
 */
public final class LayoutPlan {

    /** 列数。改这里等于改「机型假设」，不是改代码风格。 */
    public static final int COLUMNS = 4;
    /** 行数。 */
    public static final int ROWS = 6;
    /** 一个文件夹占几列 / 几行（实测 2×2）。 */
    public static final int FOLDER_SPAN = 2;
    /** 每屏能放几个文件夹。 */
    public static final int FOLDERS_PER_SCREEN =
            (COLUMNS / FOLDER_SPAN) * (ROWS / FOLDER_SPAN);

    /** 一个文件夹占一个格，里面装的是同一组的应用。 */
    public static final class Folder {
        public final String group;
        public final int count;

        Folder(String group, int count) {
            this.group = group;
            this.count = count;
        }
    }

    /** 一屏上文件夹能站的 6 个候选位，按 Launcher 的 2 列 3 行铺开。索引即顺序。 */
    public static final int[] DEFAULT_SLOTS = {0, 1, 2, 3, 4, 5};

    public static final class Screen {
        public final int number;
        /** true 表示这是被刻意留空的第一屏。 */
        public final boolean reserved;
        /**
         * 这一屏实际还能用的候选位索引。
         *
         * <p>只记"还剩几个"是不够的：小组件占的是<b>特定的格子</b>。真机第 1 屏有个
         * 4×2 的日历压住 (0,0) 和 (2,0)，如果只把容量从 6 减到 4，文件夹仍会被放进
         * 前四个候选位——正好包含被压住的那两个，写进去就和日历叠在一起。
         */
        public final int[] slots;
        /** 可用位数，等于 {@code slots.length}。 */
        public final int capacity;
        public final List<Folder> folders = new ArrayList<>();

        Screen(int number, boolean reserved) {
            this(number, reserved, DEFAULT_SLOTS);
        }

        Screen(int number, boolean reserved, int[] slots) {
            this.number = number;
            this.reserved = reserved;
            // 候选位一律按「左上 → 右下」升序保存：索引 0 就是这一屏最靠左上的可用位，
            // 填屏时从索引 0 依次往后取。谁传进来一份乱序数组（比如按 HashSet 遍历出来的），
            // 都会让文件夹从屏中间开始摆，所以在这里排一次，比在每个调用点信任它便宜。
            int[] src = (slots == null || slots.length == 0) ? DEFAULT_SLOTS : slots;
            int[] sorted = src.clone();
            java.util.Arrays.sort(sorted);
            this.slots = sorted;
            this.capacity = sorted.length;
        }

        /** 第 {@code index} 个文件夹该站在哪个候选位。 */
        public int slotOf(int index) {
            return index >= 0 && index < slots.length ? slots[index] : index;
        }

        public int free() {
            return capacity - folders.size();
        }

        /** 钉屏钉过头，或者同一屏被塞了太多组。 */
        public boolean overflow() {
            return folders.size() > capacity;
        }
    }

    /** 按屏号升序，界面上直接照这个顺序画。 */
    public final List<Screen> screens = new ArrayList<>();
    public int folderCount;

    private LayoutPlan() {
    }

    public static LayoutPlan build(Map<String, List<AppEntry>> groups, boolean firstScreenBlank) {
        return build(groups, firstScreenBlank, null, null);
    }

    public static LayoutPlan build(Map<String, List<AppEntry>> groups, boolean firstScreenBlank,
                                   Map<String, Integer> pins) {
        return build(groups, firstScreenBlank, pins, null);
    }

    /**
     * @param pins 组名 → 指定屏号，可为 null。指定的组优先占位，其余按原顺序填进剩下的空格；
     *             存在钉屏时不会把两组塞进同一个格。第一屏留空时，钉在 1 屏的会被当成"自动"——
     *             否则勾选框和分组设置会互相打架，用户看到的屏号跟勾选状态矛盾。
     * @param slotsOf 屏号 → 这一屏还能用的候选位，可为 null（都按 6 个全用）。
     *                桌面上的小组件会整块占住格子：真机第 1 屏有个 4×2 的日历，
     *                压掉 (0,0) 和 (2,0) 两位。只减数量不减具体位置，写进去必然重叠。
     */
    public static LayoutPlan build(Map<String, List<AppEntry>> groups, boolean firstScreenBlank,
                                   Map<String, Integer> pins, Map<Integer, int[]> slotsOf) {
        LayoutPlan plan = new LayoutPlan();
        TreeMap<Integer, Screen> byNumber = new TreeMap<>();
        List<Folder> auto = new ArrayList<>();

        for (Map.Entry<String, List<AppEntry>> e : groups.entrySet()) {
            List<AppEntry> list = e.getValue();
            if (list == null || list.isEmpty()) {
                continue;
            }
            Folder f = new Folder(e.getKey(), list.size());
            Integer pin = pins == null ? null : pins.get(e.getKey());
            boolean usable = pin != null && pin > 0 && !(firstScreenBlank && pin == 1);
            if (usable) {
                screenAt(byNumber, pin, slotsOf).folders.add(f);
            } else {
                auto.add(f);
            }
            plan.folderCount++;
        }

        // 自动的组从起始屏往后填，遇到已有组的屏先补空位再换屏
        int start = firstScreenBlank ? 2 : 1;
        int cursor = start;
        for (Folder f : auto) {
            Screen s = nextWithRoom(byNumber, cursor, slotsOf);
            s.folders.add(f);
            cursor = s.number;
        }

        if (firstScreenBlank && !byNumber.containsKey(1)) {
            byNumber.put(1, new Screen(1, true));
        }
        spillOverflow(byNumber, slotsOf);
        plan.screens.addAll(byNumber.values());
        return plan;
    }

    /**
     * 钉屏钉过头（同一屏塞了超过它容量的组）时，把放不下的挪到后面的屏上。
     *
     * <p>不处理的话，超出的那些组会拿到这一屏根本不存在的候选位：{@link Screen#slotOf}
     * 对越界索引直接返回原索引，于是第 7 个文件夹被写成 {@code cellY=6}——4×6 的桌面
     * 没有第 7 行。桌面读进去只会把它随手挪到别处，用户看到的就是"我钉的屏它不认"。
     */
    private static void spillOverflow(TreeMap<Integer, Screen> byNumber,
                                      Map<Integer, int[]> slotsOf) {
        int guard = 0;
        while (guard++ < 64) {
            boolean changed = false;
            for (Integer key : new ArrayList<>(byNumber.keySet())) {
                Screen s = byNumber.get(key);
                if (s == null || s.reserved || s.folders.size() <= s.capacity) {
                    continue;
                }
                // 从末尾取，插到下一屏的最前面，相对顺序不会乱
                Folder extra = s.folders.remove(s.folders.size() - 1);
                Screen t = nextWithRoom(byNumber, s.number + 1, slotsOf);
                t.folders.add(0, extra);
                changed = true;
                break;
            }
            if (!changed) {
                return;
            }
        }
    }

    private static int[] slotsFor(Map<Integer, int[]> slotsOf, int screen) {
        if (slotsOf == null) {
            return DEFAULT_SLOTS;
        }
        int[] s = slotsOf.get(screen);
        return s == null ? DEFAULT_SLOTS : s;
    }

    private static Screen screenAt(TreeMap<Integer, Screen> map, int number,
                                  Map<Integer, int[]> slotsOf) {
        Screen s = map.get(number);
        if (s == null) {
            s = new Screen(number, false, slotsFor(slotsOf, number));
            map.put(number, s);
        }
        return s;
    }

    private static Screen nextWithRoom(TreeMap<Integer, Screen> map, int from,
                                       Map<Integer, int[]> slotsOf) {
        int n = from;
        while (true) {
            Screen s = map.get(n);
            if (s == null) {
                s = new Screen(n, false, slotsFor(slotsOf, n));
                map.put(n, s);
                return s;
            }
            if (!s.reserved && s.folders.size() < s.capacity) {
                return s;
            }
            n++;
        }
    }

    /** 某组落在第几屏，0 表示没排上（空组）。 */
    public int numberOf(String group) {
        if (group == null) {
            return 0;
        }
        for (Screen s : screens) {
            for (Folder f : s.folders) {
                if (group.equals(f.group)) {
                    return s.number;
                }
            }
        }
        return 0;
    }

    /** 最大屏号，用来给"调整到第几屏"的选项列表封顶。 */
    public int maxNumber() {
        return screens.isEmpty() ? 0 : screens.get(screens.size() - 1).number;
    }

    /** 一行摘要，如「共 2 屏 · 9 个文件夹 · 每屏 6 个」。 */
    public String summary() {
        if (folderCount == 0) {
            return "没有可排布的分组";
        }
        String gap = gapNote();
        return "共 " + screens.size() + " 屏 · " + folderCount + " 个文件夹 · 每屏 "
                + FOLDERS_PER_SCREEN + " 个（" + COLUMNS + "×" + ROWS + " 网格，文件夹占 "
                + FOLDER_SPAN + "×" + FOLDER_SPAN + "）"
                + (gap.isEmpty() ? "" : " · " + gap);
    }

    /** 「第 4、5 屏是空的」；没有空屏返回空串。钉到远处的屏时就会出现空屏。 */
    public String gapNote() {
        if (screens.isEmpty()) {
            return "";
        }
        java.util.Set<Integer> has = new java.util.HashSet<>();
        for (Screen s : screens) {
            has.add(s.number);
        }
        StringBuilder sb = new StringBuilder();
        int max = screens.get(screens.size() - 1).number;
        for (int i = 1; i <= max; i++) {
            if (has.contains(i)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(i);
        }
        return sb.length() == 0 ? "" : "第 " + sb + " 屏是空的";
    }

    /** 某一屏的内容描述。 */
    public String describe(Screen s) {
        if (s.reserved) {
            return "留空 · 一个图标都不放";
        }
        if (s.folders.isEmpty()) {
            return "空屏";
        }
        StringBuilder sb = new StringBuilder();
        for (Folder f : s.folders) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(f.group).append(' ').append(f.count);
        }
        return sb.toString();
    }
}
