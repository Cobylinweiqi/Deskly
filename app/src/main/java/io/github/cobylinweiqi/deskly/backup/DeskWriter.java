package io.github.cobylinweiqi.deskly.backup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import io.github.cobylinweiqi.deskly.core.DbInject;
import io.github.cobylinweiqi.deskly.organize.DeskPlan;
import io.github.cobylinweiqi.deskly.organize.DesktopModel;
import io.github.cobylinweiqi.deskly.organize.LauncherProfile;
import io.github.cobylinweiqi.deskly.organize.PlanConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把「按方案整理后的整张桌面」写进库。
 *
 * <p>这是真正重建布局的那一层：新建文件夹、把所有成员挪进去、删掉旧的空文件夹、
 * 补齐屏表。与 {@link LayoutWriter}（只改单条 container）共用同一套"在副本上改、
 * checkpoint、整体灌库"的路径，区别只是改动的规模。
 *
 * <p><b>三条来自真机数据的硬约束</b>（不要凭 Launcher3 的印象改）：
 *
 * <ol>
 *   <li>文件夹成员的 {@code screen} 必须是 <b>-1</b>，不是 0、也不是父文件夹所在屏。
 *       真库 147 条成员无一例外。</li>
 *   <li>成员在文件夹里的位置由 <b>{@code cellX = 组内序号}、{@code cellY = 序号/4}</b>
 *       表达。真机上 16 个成员的文件夹正好是 cellX 0..15、cellY 0..3，说明它不是网格坐标
 *       而是"第几个"的编码，行号只是被顺手写成 4 个一行的结果。</li>
 *   <li><b>文件夹本体占几格不再写死。</b>由 {@link DesktopModel} 从库里探测、
 *       {@link PlanConfig} 允许手动覆盖，写回时一律取 {@code DeskPlan.folderSpan}。
 *       0.14.1 时代的「一律 2×2、一屏 6 个」只对小米大文件夹机型成立；
 *       HyperOS 也有把文件夹当 1 格图标排（一屏 24 个）的版本，硬套就会多分两屏。</li>
 * </ol>
 *
 * <p>上面三条里，成员的写法是<b>小米</b>的约定。Launcher3 系（realme UI / ColorOS）由
 * {@link LauncherProfile} 切到自己的写法：文件夹 itemType=2、成员用 {@code rank} 排序。
 * 判据来自库本身，不按机型名硬切。
 *
 * <p>写库之后的生效问题不在这一层，全部交给 {@link DbInject}——那里才是"看起来成功、
 * 其实没生效"的根源。
 */
public final class DeskWriter {

    public static final class Result {
        public boolean ok;
        /**
         * 真库文件是否已经被我们覆盖过（不管后续核对成不成立）。
         *
         * <p>界面靠它区分两种"失败"：<b>没写</b>（桌面原样，可以放心重试）与
         * <b>写了但核对没过</b>（桌面已经变了，不能再拿旧预览去写第二遍）。
         */
        public boolean wrote;
        public String msg = "";
        public String snapshotId = "";
        /** 新建 + 复用的文件夹数。 */
        public int folderCount;
        public int moved;
        public int removedFolders;
        /** 写完回读真库，确认已经落在新文件夹里的图标数；回读本身失败为 -1。 */
        public int verified = -1;
        /** 回读结论，直接拼进成功文案。 */
        public String verdict = "";
        /** 库里真实存在的文件夹行数（回读），用来发现「写了但桌面把行丢了」。 */
        public int folderRowsBack = -1;
        /**
         * 0.16.4：回读真库后，从桌面最终留下的文件夹坐标反推它把文件夹当几格
         * （1 / 2），-1 表示样本不足。用来给「自动」模式记一份实测值，打破
         * 「第一次猜错 → 下次读到自己写的密排坐标 → 永远判错」的固化。
         */
        public int spanReadBack = -1;
        /** 0.16.5：回读真库后按实际 span 数出来的文件夹重叠对数，-1 表示没读到。 */
        public int overlapBack = -1;
        /** 探测到的库结构诊断，直接进回执，真机排查时不必再猜。 */
        public String diagnostics = "";
    }

    private DeskWriter() {
    }

    public static Result apply(Context ctx, String pkg, DeskPlan plan) {
        Result r = new Result();
        if (plan == null || plan.buckets.isEmpty()) {
            r.msg = "没有整理出任何分组";
            return r;
        }

        // 1) 回滚点。它同时是后面工作副本的来源（快照内部已把 WAL 合并掉）
        Snapshot pre = BackupStore.create(ctx, pkg, Snapshot.LABEL_PRE_APPLY);
        if (pre == null) {
            r.msg = "改动前快照失败，已中止：" + BackupStore.lastError;
            return r;
        }
        r.snapshotId = pre.id;

        File base = BackupStore.root(ctx);
        File src = pre.dbFile(base);
        File work = new File(base, "work-desk-" + pre.id + ".db");
        try {
            copy(src, work);
        } catch (Throwable t) {
            r.msg = "无法准备工作副本：" + t;
            return r;
        }

        // 2) 只在副本上重建
        List<Integer> folderIds = new ArrayList<>();
        try {
            rebuild(work, plan, r, folderIds);
        } catch (Throwable t) {
            r.msg = "在副本上重建布局失败：" + t
                    + (r.diagnostics.isEmpty() ? "" : "\n诊断：" + r.diagnostics)
                    + "\n桌面未被改动，可用快照 " + pre.id + " 回滚";
            clean(work);
            return r;
        }
        if (r.moved != plan.total) {
            r.msg = "校验不一致：计划移动 " + plan.total + " 个，实际落入文件夹 "
                    + r.moved + " 个。桌面未被改动。";
            clean(work);
            return r;
        }

        // 3) 让副本自洽后整体灌进真库
        if (!checkpoint(work)) {
            r.msg = "副本合并 WAL 失败，已中止（真库未被改动）";
            clean(work);
            return r;
        }
        DbInject.Result inj = DbInject.write(pkg, pre.dbPath, work, 150);
        // wroteAt/wroteSize 是灌库那一刻就地取的值：非 0 就说明 cat 真的执行过。
        // 后面不管怎么判，界面都得知道"桌面已经被改过了"。
        r.wrote = inj.wroteAt > 0 && inj.wroteSize > 0;
        if (!inj.ok) {
            r.msg = "写回真库失败：" + inj.log + "\n回滚点：" + pre.id;
            clean(work);
            return r;
        }
        clean(work);

        if (!inj.targetIsLive) {
            // 写进去的是另一个文件，桌面读不到——这是真失败，不能算成功
            r.msg = "写入了 " + pre.dbPath + "，但桌面此刻打开的是 " + inj.livePath
                    + "，布局不会变。回滚点：" + pre.id;
            return r;
        }

        // 4) 到此收工，<b>不再对着真库重算摘要</b>。
        //
        //    这里原先有一道"写完之后再 sha256sum 真库，和副本比"的校验，0.11.0 在真机上
        //    就是被它坑的：灌库的最后一步会 input keyevent 3 把桌面拉回来，Launcher 启动
        //    时自己开库、合并 WAL、补齐自己的字段，几秒后摘要必然与当初写进去的不同。
        //    于是"桌面明明已经按方案整理好了"，界面却弹「整理失败」并劝用户拿快照回滚——
        //    用户照做就等于把刚整理好的桌面亲手撤销。
        //
        //    真正的写入核对在 DbInject 内部完成：cat 完立刻 stat + sha256sum，
        //    那个时刻桌面还没开始读库，比的就是该比的东西。结论通过 inj.brief()
        //    作为"附注"拼进成功文案（已写入但内容核对不一致 / 桌面进程比库旧），
        //    不再让它决定成败。

        // 5) 回读真库，数一遍"到底有多少图标落在了我们建的文件夹里"，顺便核对屏号与格位。
        StringBuilder moved = new StringBuilder();
        StringBuilder placed = new StringBuilder();
        r.verified = verifyBack(ctx, pkg, plan, folderIds, moved, placed, plan.profile, r);
        if (r.verified < 0) {
            r.verdict = "回读失败（" + DbCopy.lastError + "），以写入时的核对为准";
        } else if (r.verified == plan.total) {
            r.verdict = "回读 " + r.verified + "/" + plan.total + " 个图标已在新文件夹里";
        } else {
            r.verdict = "⚠ 回读只有 " + r.verified + "/" + plan.total
                    + " 个图标在新文件夹里——桌面可能把它又改了，先看一眼再决定要不要回滚";
        }
        if (moved.length() > 0) {
            r.verdict += "\n⚠ 屏号和计划不一致：" + moved;
        }
        if (r.verified >= 0) {
            // 位置能对上就是"每屏从左上角密排"的直接证据：格位是计划算的，
            // 库里也是这个格位，说明桌面没有再把它挪开。
            r.verdict += placed.length() == 0
                    ? "\n位置与计划一致（每屏从左上角开始排）"
                    : "\n⚠ 格位和计划不一致：" + placed;
        }

        if (r.folderRowsBack >= 0 && r.folderRowsBack < folderIds.size()) {
            r.verdict += "\n⚠ 回读只剩 " + r.folderRowsBack + "/" + folderIds.size()
                    + " 个文件夹行——桌面可能丢弃了这些行（itemType 或必需列不匹配）";
        }
        // 0.16.4：把「桌面真实怎么排文件夹」记进配置，供下次「自动」直接采用。
        // 只记录，不自动二次灌库：桌面刚重启、可能还在自己整理库，再写一遍只会把局面搅乱。
        // 用户看到不一致提示后，点一次「重新分析」再应用即可，应用前仍会自动拍快照。
        if (r.spanReadBack > 0) {
            PlanConfig cfg = PlanConfig.load(ctx);
            cfg.recordVerifiedSpan(r.spanReadBack);
            cfg.save(ctx);
            if (r.spanReadBack != plan.folderSpan) {
                r.verdict += "\n⚠ 桌面按 " + r.spanReadBack + "×" + r.spanReadBack
                        + " 格排文件夹，与本次写入的 " + plan.folderSpan + "×"
                        + plan.folderSpan + " 不一致；已记住，下次「自动」会用桌面真实值。"
                        + "请点「重新分析」后再应用一次。";
            } else {
                r.verdict += "\n文件夹占位 " + r.spanReadBack + "×" + r.spanReadBack
                        + " 与桌面一致，已记住供「自动」使用。";
            }
        }
        if (r.overlapBack > 0) {
            r.verdict += "\n⚠ 回读发现 " + r.overlapBack
                    + " 对文件夹位置重叠：桌面很可能按另一种尺寸渲染文件夹。"
                    + "请改用" + (plan.folderSpan == 1 ? "「大 2×2」" : "「小 1×1」")
                    + "，重新分析后再整理一次。";
        } else if (r.overlapBack == 0) {
            r.verdict += "\n回读确认：文件夹之间无重叠";
        }
        r.ok = true;
        r.msg = "已整理 " + r.moved + " 个图标 · " + r.folderCount + " 个文件夹"
                + (r.removedFolders > 0 ? " · 清掉 " + r.removedFolders + " 个旧文件夹" : "")
                + " · " + inj.brief() + "\n" + r.verdict
                + "\n诊断：" + r.diagnostics;
        return r;
    }

    // ───────────────────────── 重建 ─────────────────────────

    /**
     * 只改库文件，不碰真库、不拍快照、不做 root 相关操作。
     *
     * <p>包级可见是为了让跨 ROM 测试能在 JVM 上直接跑到真正写库的那段 SQL——
     * {@link #apply} 依赖 {@code DbCopy} / {@code DbInject} 走 su，本地没法跑。
     */
    static void rebuild(File work, DeskPlan plan, Result r, List<Integer> folderIds)
            throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                work.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            // 方案里带着读库时识别出的方言；老调用方（或手搓的 plan）没带就现场再探一次。
            LauncherProfile prof = plan.profile != null
                    ? plan.profile : LauncherProfile.detect(db, null);
            if (!prof.hasLayoutTable()) {
                throw new IllegalStateException("库里没有桌面布局表（favorites）");
            }
            Set<String> cols = columns(db, prof.table);
            r.diagnostics = prof.diagnostics();
            // 0.16.2：与排屏共用读库时探测到的占位。旧写法 prof.isMiui() ? 2 : 1
            // 和 LayoutPlan 写死的 2×2 从来没对齐过：HyperOS 桌面按 1 格排，
            // 13 个文件夹却被算成 3 屏，写回坐标也稀疏。
            int folderSpan = plan.folderSpan > 0 ? plan.folderSpan : (prof.isMiui() ? 2 : 1);
            // 0.16.5：写库前最后一道闸。方案里只要还有文件夹互相压住、或压住小组件，
            // 宁可整次中止也不灌进真库——快照还在，桌面一定没被改动，可以放心重来。
            assertNoOverlap(plan, folderSpan);
            // 以桌面自己写出来的行做模板：厂商私有列（NOT NULL 或加载时必读的字段）
            // 会原样带过来，避免「插入成功、桌面却把文件夹行静默丢掉」。
            // 小米的模板为空，写库仍是 0.14.1 的手工构造。
            ContentValues seedTemplate = prof.isMiui()
                    ? new ContentValues() : folderSeed(db, prof);

            // 旧文件夹的 id 集合：只用来判断"哪些要从库里删掉"。
            // 名字匹配不在这里做——库里的 title 可能是 com.miui.home:string/xxx 这种
            // 资源引用，只有 DesktopModel 读过之后才知道它叫「工具」。
            Set<Integer> oldIds = new LinkedHashSet<>();
            try (Cursor c = db.rawQuery(
                    "SELECT _id FROM " + prof.table + " WHERE itemType IN ("
                            + prof.folderTypesIn()
                            + ") AND container=-100", null)) {
                while (c.moveToNext()) {
                    oldIds.add(c.getInt(0));
                }
            }

            // 有非应用成员的旧文件夹不能删：那些成员（组件之类）不进新文件夹，删了会成孤儿
            Set<Integer> protectedFolders = new HashSet<>();
            try (Cursor c = db.rawQuery(
                    "SELECT DISTINCT container FROM " + prof.table + " WHERE container>0"
                            + " AND itemType NOT IN (" + prof.appTypesIn() + ")", null)) {
                while (c.moveToNext()) {
                    protectedFolders.add(c.getInt(0));
                }
            }

            db.beginTransaction();
            try {
                Set<Integer> kept = new HashSet<>();
                for (DeskPlan.Bucket b : plan.buckets) {
                    Integer id = plan.reusable.get(b.group);
                    if (id != null && !oldIds.contains(id)) {
                        id = null;
                    }
                    if (id != null && kept.contains(id)) {
                        id = null;
                    }
                    boolean reused = id != null;
                    if (reused) {
                        // 复用：只改它自己在桌面上的位置
                        ContentValues v = new ContentValues();
                        v.put("container", DesktopModel.CONTAINER_DESKTOP);
                        v.put("screen", prof.toDbScreen(b.screen));
                        v.put("cellX", b.cellX);
                        v.put("cellY", b.cellY);
                        v.put("spanX", folderSpan);
                        v.put("spanY", folderSpan);
                        // 0.16.6：复用旧文件夹也必须改 itemType。原来只改坐标，
                        // 旧 id 是 21（2×2 四宫格），用户选 1×1 后桌面照样画 2×2，
                        // 于是「生成出来的还是 2×2」并和邻居叠住。
                        v.put("itemType", prof.folderTypeFor(folderSpan));
                        putIf(v, cols, "extendContainer", -1);
                        putIf(v, cols, "isOnlySupportExtend", 0);
                        db.update(prof.table, v, "_id=?", new String[]{String.valueOf(id)});
                        kept.add(id);
                    } else {
                        ContentValues v = new ContentValues(seedTemplate);
                        // 模板可能来自一个应用图标行：把只属于快捷方式的字段清掉，
                        // 否则文件夹会带着别人的图标 / intent 被桌面当成快捷方式。
                        for (String drop : APP_ONLY_COLUMNS) {
                            v.remove(drop);
                        }
                        v.put("title", b.group);
                        v.put("intent", (String) null);
                        v.put("container", DesktopModel.CONTAINER_DESKTOP);
                        v.put("screen", prof.toDbScreen(b.screen));
                        v.put("cellX", b.cellX);
                        v.put("cellY", b.cellY);
                        v.put("spanX", folderSpan);
                        v.put("spanY", folderSpan);
                        v.put("itemType", prof.folderTypeFor(folderSpan));
                        putIf(v, cols, "appWidgetId", -1);
                        putIf(v, cols, "itemFlags", 0);
                        putIf(v, cols, "profileId", 0);
                        putIf(v, cols, "extendContainer", -1);
                        putIf(v, cols, "isOnlySupportExtend", 0);
                        putIf(v, cols, "originWidgetId", -1);
                        putIf(v, cols, "rank", 0);
                        putIf(v, cols, "options", 0);
                        putIf(v, cols, "modified", 0);
                        putIf(v, cols, "restored", 0);
                        // 小米库没有这类列，且要保证恒等变换，所以只给非小米分支补
                        if (!prof.isMiui()) {
                            fillRequired(db, prof.table, v);
                        }
                        long newId = db.insert(prof.table, null, v);
                        if (newId <= 0) {
                            throw new IllegalStateException("新建文件夹「" + b.group
                                    + "」失败（表 " + prof.table + " · itemType "
                                    + prof.folderTypeFor(folderSpan) + " · 已填 " + v.size() + " 列）");
                        }
                        kept.add((int) newId);
                        id = (int) newId;
                    }
                    // 记下来，写完之后回读真库要按这些 container 数成员
                    folderIds.add(id);

                    // 组内成员：小米是 cellX=序号、cellY=序号/4（真机编码方式，见类注释）；
                    // Launcher3 系用 rank 排序，cellY 恒 0，两种都按序号递增，不依赖 ROM 枚举。
                    int idx = 0;
                    for (DesktopModel.Item it : b.members) {
                        ContentValues v = new ContentValues();
                        v.put("container", id);
                        v.put("screen", -1);
                        v.put("cellX", idx);
                        v.put("cellY", prof.miuiFolderCells
                                ? idx / DesktopModel.FOLDER_COLUMNS : 0);
                        putIf(v, cols, "extendContainer", -1);
                        putIf(v, cols, "isOnlySupportExtend", 0);
                        if (!prof.miuiFolderCells) {
                            putIf(v, cols, "rank", idx);
                        }
                        int n = db.update(prof.table, v, "_id=?",
                                new String[]{String.valueOf(it.id)});
                        if (n != 1) {
                            throw new IllegalStateException(
                                    "「" + it.title + "」写入文件夹失败（影响 " + n + " 行）");
                        }
                        r.moved++;
                        idx++;
                    }

                    // 复用的旧文件夹里可能还留着非应用成员——真机的「工具」文件夹里就有一个
                    // itemType=1 的「查找」入口。它们的序号必须接在新成员后面，
                    // 否则会和某个应用抢同一个格子，Launcher 上表现为图标重叠或丢失。
                    if (reused) {
                        for (int leftover : leftovers(db, id, b, prof)) {
                            ContentValues v = new ContentValues();
                            v.put("cellX", idx);
                            v.put("cellY", prof.miuiFolderCells
                                    ? idx / DesktopModel.FOLDER_COLUMNS : 0);
                            if (!prof.miuiFolderCells) {
                                putIf(v, cols, "rank", idx);
                            }
                            db.update(prof.table, v, "_id=?",
                                    new String[]{String.valueOf(leftover)});
                            idx++;
                        }
                    }
                    r.folderCount++;
                }

                // 锁定屏内容与保留的小组件：只改 screen，其他一律不动。
                // 必须在删旧文件夹之前做——锁定屏里的文件夹本体也在这张表里。
                for (Map.Entry<Integer, Integer> e : plan.frozenScreens.entrySet()) {
                    ContentValues v = new ContentValues();
                    v.put("screen", prof.toDbScreen(e.getValue()));
                    db.update(prof.table, v, "_id=?",
                            new String[]{String.valueOf(e.getKey())});
                }

                // 选择"随屏清空"的小组件：从这里删掉
                for (int id : plan.dropIds) {
                    db.delete(prof.table, "_id=?", new String[]{String.valueOf(id)});
                }

                // 没被复用的旧文件夹删掉；被保护的不碰；锁定屏里的永远不删
                int removed = 0;
                for (int id : oldIds) {
                    if (kept.contains(id) || protectedFolders.contains(id)
                            || plan.keptFolderIds.contains(id)
                            || plan.strayFolderIds.contains(id)) {
                        continue;
                    }
                    removed += db.delete(prof.table, "_id=?", new String[]{String.valueOf(id)});
                }
                r.removedFolders = removed;

                syncScreens(db, plan.screenCount, prof);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            // 逐条回读：UPDATE 返回 1 不等于真的落到了该在的地方
            try (Cursor c = db.rawQuery(
                    "SELECT count(*) FROM " + prof.table
                            + " WHERE container>0 AND itemType IN ("
                            + prof.appTypesIn() + ")", null)) {
                if (c.moveToFirst()) {
                    int total = c.getInt(0);
                    if (total != plan.total) {
                        throw new IllegalStateException(
                                "回读成员数 " + total + " ≠ 计划 " + plan.total);
                    }
                }
            }
        } finally {
            try {
                db.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 0.16.5：写库前的硬校验。预览、最终排位都保证不重叠，但「钉屏钉过头」「旧文件夹
     * 残留」「某条老路径绕过 DeskPlan」都可能再把它破坏掉。这里按矩形相交再数一遍，
     * 有任何一处重叠就抛异常——{@link #apply} 会把它转成「桌面未被改动，可放心重试」。
     */
    private static void assertNoOverlap(DeskPlan plan, int span) {
        List<DeskPlan.Rect> rects = new ArrayList<>();
        for (DeskPlan.Bucket b : plan.buckets) {
            rects.add(new DeskPlan.Rect(b.screen, b.cellX, b.cellY, span, span));
        }
        int n = DeskPlan.countOverlaps(rects);
        for (List<DeskPlan.Rect> obs : plan.obstacles.values()) {
            for (DeskPlan.Rect o : obs) {
                for (DeskPlan.Rect r : rects) {
                    if (r.intersects(o)) {
                        n++;
                    }
                }
            }
        }
        if (n > 0) {
            throw new IllegalStateException("方案里有 " + n
                    + " 处文件夹位置重叠，已中止写入（桌面未被改动）");
        }
    }

    /** 模板来自应用图标行时必须清掉的字段。 */
    private static final String[] APP_ONLY_COLUMNS = {
            "intent", "icon", "iconPackage", "iconResource", "iconType", "uri",
            "appWidgetProvider", "appWidgetId", "component", "shortcutId",
            "packageName", "className",
    };

    /**
     * 取一条桌面自己写出来的行，作为新建文件夹行的模板。
     *
     * <p>只用于 Launcher3 系：这些桌面常在 favorites 上加私有列，且加载 FolderInfo 时
     * 会假设它们非 NULL。手工拼 8 个字段能 insert 成功，却会在桌面启动时被整行丢掉——
     * 表现为「屏数变了、图标能换屏，但一个文件夹都没有」。直接复制现成的行，
     * 所有私有列都带着厂商自己的默认值，最省事也最不容易猜错。
     */
    private static ContentValues folderSeed(SQLiteDatabase db, LauncherProfile prof) {
        ContentValues seed = new ContentValues();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT * FROM " + prof.table + " WHERE itemType IN ("
                    + prof.folderTypesIn() + ") AND container<0 LIMIT 1", null);
            if (!c.moveToFirst()) {
                c.close();
                c = db.rawQuery("SELECT * FROM " + prof.table
                        + " WHERE container=-100 AND intent IS NOT NULL LIMIT 1", null);
                if (!c.moveToFirst()) {
                    return seed;
                }
            }
            for (int i = 0; i < c.getColumnCount(); i++) {
                String name = c.getColumnName(i);
                // _id 让 SQLite 自增，不能照抄模板的
                if ("_id".equals(name)) {
                    continue;
                }
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_INTEGER:
                        seed.put(name, c.getLong(i));
                        break;
                    case Cursor.FIELD_TYPE_FLOAT:
                        seed.put(name, c.getDouble(i));
                        break;
                    case Cursor.FIELD_TYPE_STRING:
                        seed.put(name, c.getString(i));
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        seed.put(name, c.getBlob(i));
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return seed;
    }

    /** 补齐 NOT NULL 且没有 DEFAULT 的列；缺一列 insert 就会返回 -1。 */
    private static void fillRequired(SQLiteDatabase db, String table, ContentValues v) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                String name = c.getString(1);
                if ("_id".equals(name) || v.containsKey(name)) {
                    continue;
                }
                boolean notNull = c.getInt(3) != 0;
                boolean hasDefault = !c.isNull(4);
                if (!notNull || hasDefault) {
                    continue;
                }
                String type = c.getString(2) == null
                        ? "" : c.getString(2).toUpperCase(Locale.US);
                if (type.contains("INT") || type.contains("BOOL") || type.contains("REAL")
                        || type.contains("NUMERIC") || type.contains("FLOA")
                        || type.contains("DOUB")) {
                    v.put(name, 0);
                } else if (type.contains("BLOB")) {
                    v.put(name, new byte[0]);
                } else {
                    v.put(name, "");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 某个文件夹里不属于本次整理的非应用成员（小米的「查找」这类系统入口）。 */
    private static List<Integer> leftovers(SQLiteDatabase db, int folderId, DeskPlan.Bucket b,
                                           LauncherProfile prof) {
        Set<Integer> mine = new HashSet<>();
        for (DesktopModel.Item it : b.members) {
            mine.add(it.id);
        }
        List<Integer> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT _id FROM " + prof.table
                        + " WHERE container=? AND itemType NOT IN ("
                        + prof.appTypesIn() + ") ORDER BY cellX",
                new String[]{String.valueOf(folderId)})) {
            while (c.moveToNext()) {
                int id = c.getInt(0);
                if (!mine.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /**
     * 补齐屏表，并清掉彻底空掉的屏。
     *
     * <p>屏表不补，写进去的第 7 屏在桌面里就不存在；空屏不清，整理后从 6 屏缩到 3 屏
     * 会剩下 3 个滑得过去却什么都没有的空白页。
     *
     * <p>小米用 {@code screens(screenOrder)}，Launcher3 系常见
     * {@code workspaceScreens(screenRank)} 或 0 起屏号，表名、列名、编号都由方言给出。
     */
    /** 包级可见：跨 ROM 的屏表补齐可以在 JVM 上直接跑，不必拉真机 / root。 */
    static void syncScreens(SQLiteDatabase db, int want, LauncherProfile prof) {
        String table = prof.screensTable;
        if (table == null || table.isEmpty() || !tableExists(db, table)) {
            return;
        }
        Set<String> screenCols = columns(db, table);
        String rank = prof.screensRankColumn;
        int nextRank = maxRank(db, table, rank) + 1;

        // 与写回布局行用同一份映射：toDbScreen 给的就是屏表里真实存在 / 即将插入的 id，
        // 这里绝不再按 screenBase 另算一遍——那是真我「只显示 1 屏」的直接原因。
        Set<Integer> need = new LinkedHashSet<>();
        for (int i = 1; i <= want; i++) {
            int dbId = prof.toDbScreen(i);
            need.add(dbId);
            try (Cursor c = db.rawQuery("SELECT 1 FROM " + table + " WHERE _id=?",
                    new String[]{String.valueOf(dbId)})) {
                if (c.moveToFirst()) {
                    continue;
                }
            }
            // 0.16.3：厂商屏表常有 NOT NULL 且无默认值的私有列。手工拼字段要么插入失败，
            // 要么插出一行桌面加载时直接丢掉的残缺屏，所以拿桌面自己写的一行当模板。
            insertScreenRow(db, table, screenCols, dbId, rank, nextRank++);
        }

        // 不在 need 里、且整屏一条桌面行都没有的屏才删；第一屏永远保留（可能是刻意留空）。
        int first = prof.toDbScreen(1);
        List<Integer> empty = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT s._id FROM " + table + " s WHERE NOT EXISTS (SELECT 1 FROM "
                        + prof.table + " f WHERE f.screen=s._id)", null)) {
            while (c.moveToNext()) {
                int id = c.getInt(0);
                if (id != first && !need.contains(id)) {
                    empty.add(id);
                }
            }
        }
        for (int id : empty) {
            db.delete(table, "_id=?", new String[]{String.valueOf(id)});
        }
    }

    /** 用屏表现有的一行做模板补一行；只替换 _id 与排序列，其余私有列原样带过来。 */
    private static void insertScreenRow(SQLiteDatabase db, String table, Set<String> cols,
                                        int id, String rank, int rankValue) {
        ContentValues v = new ContentValues();
        try (Cursor c = db.query(table, null, null, null, null, null, "_id ASC", "1")) {
            if (c.moveToFirst()) {
                for (String col : c.getColumnNames()) {
                    if ("_id".equals(col) || !cols.contains(col)) {
                        continue;
                    }
                    int i = c.getColumnIndex(col);
                    if (i < 0 || c.isNull(i)) {
                        continue;
                    }
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER:
                            v.put(col, c.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            v.put(col, c.getDouble(i));
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            v.put(col, c.getBlob(i));
                            break;
                        default:
                            v.put(col, c.getString(i));
                            break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        v.put("_id", id);
        if (rank != null && !rank.isEmpty() && cols.contains(rank)) {
            v.put(rank, rankValue);
        }
        db.insert(table, null, v);
    }

    private static int maxRank(SQLiteDatabase db, String table, String rank) {
        if (rank == null || rank.isEmpty()) {
            return -1;
        }
        try (Cursor c = db.rawQuery("SELECT max(" + rank + ") FROM " + table, null)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getInt(0) : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean tableExists(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{name})) {
            return c.moveToFirst();
        }
    }

    private static Set<String> columns(SQLiteDatabase db, String table) {
        Set<String> out = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                out.add(c.getString(1));
            }
        }
        return out;
    }

    /** 不同 ROM 的 favorites 列不完全一样，写之前先确认这一列存在，免得整条 SQL 崩掉。 */
    private static void putIf(ContentValues v, Set<String> cols, String key, int value) {
        if (cols.contains(key)) {
            v.put(key, value);
        }
    }

    private static boolean checkpoint(File work) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(work.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            try (Cursor c = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
                c.moveToFirst();
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void copy(File from, File to) throws Exception {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 写完回读真库，数一遍落在我们新建 / 复用的那些文件夹里的成员数。
     *
     * <p>为什么先等 900ms：灌库最后一步会把桌面拉回前台，它得先起来才会打开库，
     * 立刻 pull 有可能抓到它启动前的那个瞬间。
     *
     * <p>为什么数 container 而不是比字节：桌面启动后会自己改库（补字段、合并 WAL、
     * 还原它自己的东西），字节必然不同；但"成员在哪个 container"就是布局本身，
     * 这个对不上才算真的没成功。
     */
    private static int verifyBack(Context ctx, String pkg, DeskPlan plan,
                                  List<Integer> folderIds, StringBuilder moved,
                                  StringBuilder placed, LauncherProfile profile, Result r) {
        if (folderIds.isEmpty()) {
            return -1;
        }
        try {
            Thread.sleep(900);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        File back = DbCopy.pull(ctx, pkg);
        if (back == null) {
            return -1;
        }
        StringBuilder in = new StringBuilder();
        for (int id : folderIds) {
            if (in.length() > 0) {
                in.append(',');
            }
            in.append(id);
        }
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(back.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            try {
                LauncherProfile prof = profile != null
                        ? profile : LauncherProfile.detect(db, null);
                int members = -1;
                try (Cursor c = db.rawQuery("SELECT count(*) FROM " + prof.table
                        + " WHERE itemType IN (" + prof.appTypesIn()
                        + ") AND container IN (" + in + ")", null)) {
                    if (c.moveToFirst()) {
                        members = c.getInt(0);
                    }
                }
                // 光数成员不够：成员 container 指向一个「桌面不认的文件夹行」时，
                // 上面那个数照样是满的，用户看到的却是一个文件夹都没有。
                int folderRows = 0;
                try (Cursor c = db.rawQuery("SELECT count(*) FROM " + prof.table
                        + " WHERE _id IN (" + in + ") AND itemType IN ("
                        + prof.folderTypesIn() + ") AND container=-100", null)) {
                    if (c.moveToFirst()) {
                        folderRows = c.getInt(0);
                    }
                }
                r.folderRowsBack = folderRows;
                // 0.16.5：桌面重启后可能按另一种尺寸重新摆文件夹，回读再数一遍重叠。
                // 库坐标只是「我们写进去的」，但桌面若自己挪过，这里能直接看出来。
                List<DeskPlan.Rect> backRects = new ArrayList<>();
                try (Cursor c = db.rawQuery("SELECT screen,cellX,cellY,spanX,spanY FROM "
                        + prof.table + " WHERE _id IN (" + in + ") AND itemType IN ("
                        + prof.folderTypesIn() + ") AND container=-100", null)) {
                    while (c.moveToNext()) {
                        int sx = c.isNull(3) || c.getInt(3) < 1 ? plan.folderSpan : c.getInt(3);
                        int sy = c.isNull(4) || c.getInt(4) < 1 ? plan.folderSpan : c.getInt(4);
                        backRects.add(new DeskPlan.Rect(prof.fromDbScreen(c.getInt(0)),
                                c.getInt(1), c.getInt(2), sx, sy));
                    }
                }
                r.overlapBack = DeskPlan.countOverlaps(backRects);
                // 顺带核对"文件夹是不是还在我写的那个屏上、那个格位上"。
                // 用户报的"钉到第 6 屏却出现在第 2 屏"、"不是从左上角开始排"，
                // 靠这一条就能把责任分清：是桌面把位置改了，还是我们压根没写对。
                int shown = 0;
                int posShown = 0;
                /** 屏号/行 → 该行上文件夹最终的 cellX，用来反推桌面的真实占位。 */
                Map<String, List<Integer>> rowXs = new LinkedHashMap<>();
                for (int i = 0; i < folderIds.size() && i < plan.buckets.size(); i++) {
                    DeskPlan.Bucket b = plan.buckets.get(i);
                    int expected = prof.toDbScreen(b.screen);
                    int as = -999;
                    int ax = -999;
                    int ay = -999;
                    try (Cursor c = db.rawQuery(
                            "SELECT screen,cellX,cellY FROM " + prof.table
                                    + " WHERE _id=?",
                            new String[]{String.valueOf(folderIds.get(i))})) {
                        if (c.moveToFirst()) {
                            as = c.getInt(0);
                            ax = c.getInt(1);
                            ay = c.getInt(2);
                        }
                    }
                    if (as != -999 && ax != -999) {
                        String key = as + "/" + ay;
                        List<Integer> xs = rowXs.get(key);
                        if (xs == null) {
                            xs = new ArrayList<>();
                            rowXs.put(key, xs);
                        }
                        xs.add(ax);
                    }
                    if (as != expected && shown++ < 3) {
                        if (moved.length() > 0) {
                            moved.append('、');
                        }
                        moved.append(b.group).append(" 计划第").append(b.screen)
                                .append("屏→库里第").append(prof.fromDbScreen(as)).append("屏");
                    }
                    if ((as != expected || ax != b.cellX || ay != b.cellY)
                            && posShown++ < 3) {
                        if (placed.length() > 0) {
                            placed.append('、');
                        }
                        placed.append(b.group).append(" 计划(").append(b.screen).append("屏 ")
                                .append(b.cellX).append(',').append(b.cellY).append(")→库里(")
                                .append(prof.fromDbScreen(as)).append("屏 ").append(ax).append(',')
                                .append(ay).append(')');
                    }
                }
                // 0.16.4：从桌面最终留下的坐标反推它到底把文件夹当几格。
                // 我们写进去的步长就是 plan.folderSpan；若桌面重启后把相邻文件夹挪成
                // 相隔 2 格，说明它实际按 2×2 渲染，下次「自动」应改用 2。
                // 局限：桌面只是把方块叠在一起却不改库坐标时，这里看不出来，
                // 那就得靠界面上的「小 1×1 / 大 2×2」手动开关。
                int minGap = Integer.MAX_VALUE;
                for (List<Integer> xs : rowXs.values()) {
                    if (xs.size() < 2) {
                        continue;
                    }
                    java.util.Collections.sort(xs);
                    for (int i = 1; i < xs.size(); i++) {
                        int gap = xs.get(i) - xs.get(i - 1);
                        if (gap > 0 && gap < minGap) {
                            minGap = gap;
                        }
                    }
                }
                if (minGap == 1) {
                    r.spanReadBack = 1;
                } else if (minGap >= 2 && minGap != Integer.MAX_VALUE) {
                    r.spanReadBack = 2;
                } else {
                    r.spanReadBack = -1;
                }
                r.diagnostics += "\n写回后文件夹重叠对数 "
                        + (r.overlapBack < 0 ? "（读不到）" : r.overlapBack);
                r.diagnostics += "\n写回后文件夹相邻间距 "
                        + (r.spanReadBack > 0 ? r.spanReadBack + " 格" : "（样本不足）");
                // 0.16.3：真我「方案 2 屏、桌面 1 屏」的判定证据。桌面重启后会自己开库、
                // 甚至把 screen 值改回去，光看写入前的计划看不出来，回执必须带上真库现状。
                r.diagnostics += "\n写回后 screen 分布 " + screenHistogram(db, prof.table);
                if (!prof.screensTable.isEmpty()) {
                    r.diagnostics += "\n写回后屏表行 " + screenTableRows(db, prof);
                }
                return members;
            } finally {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return -1;
        } finally {
            DbCopy.delete(back);
        }
    }

    /** 真库里桌面行（排除热区与文件夹成员）每个 screen 值各有多少行。 */
    private static String screenHistogram(SQLiteDatabase db, String table) {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = db.rawQuery("SELECT screen,count(*) FROM " + table
                + " WHERE container<=0 AND container<>-101 GROUP BY screen ORDER BY screen", null)) {
            while (c.moveToNext()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(c.getInt(0)).append('=').append(c.getInt(1));
            }
        } catch (Throwable ignored) {
            return "（读不到）";
        }
        return sb.length() == 0 ? "（无桌面行）" : sb.toString();
    }

    /** 真库屏表现有行，形如 {@code 0/0 1/1}（_id/排序列）。 */
    private static String screenTableRows(SQLiteDatabase db, LauncherProfile prof) {
        String t = prof.screensTable;
        String rank = prof.screensRankColumn;
        String sql = "SELECT _id" + (rank.isEmpty() ? "" : "," + rank) + " FROM " + t
                + " ORDER BY " + (rank.isEmpty() ? "_id" : rank) + " ASC, _id ASC";
        StringBuilder sb = new StringBuilder();
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(c.getInt(0));
                if (!rank.isEmpty()) {
                    sb.append('/').append(c.getInt(1));
                }
            }
        } catch (Throwable ignored) {
            return "（读不到）";
        }
        return sb.length() == 0 ? "（空）" : sb.toString();
    }

    private static void clean(File work) {
        //noinspection ResultOfMethodCallIgnored
        work.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(work.getAbsolutePath() + "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(work.getAbsolutePath() + "-shm").delete();
    }
}
