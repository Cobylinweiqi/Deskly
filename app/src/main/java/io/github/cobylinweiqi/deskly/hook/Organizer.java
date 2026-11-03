package io.github.cobylinweiqi.deskly.hook;

import io.github.cobylinweiqi.deskly.Boot;
import io.github.cobylinweiqi.deskly.core.L;
import io.github.cobylinweiqi.deskly.probe.RuntimeProbe;

import io.github.libxposed.api.XposedModule;

/**
 * 桌面写入侧。这一层是"手"，负责把分类结果真正落到桌面上。
 *
 * <p><strong>现状必须说清楚</strong>：写入依赖目标 ROM 的内部类与方法签名（建文件夹没有公开 API），
 * 而这些签名每个版本都可能变。因此在拿到探针报告之前，这里只做能力判定，
 * 不安装任何 speculative 的 hook——宁可什么都不做，也不能让 launcher 崩在用户的桌面上。
 *
 * <p>状态机的意义：让"为什么没生效"变成一个可回答的问题，而不是黑箱。
 */
public final class Organizer {

    public enum State {
        /** 还没进过桌面进程。 */
        NOT_ATTACHED,
        /** 进来了，但没认出任何结构类。 */
        NO_STRUCTURE,
        /** 认出结构了，但自动整理开关关着。 */
        DISABLED,
        /** 认出结构、开关也开了，但还没针对该签名写适配器。 */
        NEEDS_ADAPTER,
        /** 适配完成且在跑。 */
        ACTIVE,
    }

    private static final Organizer INSTANCE = new Organizer();

    private volatile State state = State.NOT_ATTACHED;
    private volatile String detail = "";
    private volatile String pkg = "";

    private Organizer() {
    }

    public static Organizer get() {
        return INSTANCE;
    }

    public void attach(XposedModule module, String pkg, ClassLoader cl, RuntimeProbe.Outcome outcome) {
        this.pkg = pkg;

        if (outcome == null || !outcome.structureReady()) {
            state = State.NO_STRUCTURE;
            detail = "未识别到桌面结构类，写入层不装配";
            L.w(Boot.TAG, detail);
            return;
        }

        state = State.NEEDS_ADAPTER;

        if (!HookConfig.autoOrganize(module)) {
            state = State.DISABLED;
            detail = "自动整理未开启（已识别 " + outcome.found.size() + " 个结构类）";
            L.i(Boot.TAG, detail);
            return;
        }

        detail = "已识别结构，等待按 " + pkg + " 实际签名装配写入器";
        L.w(Boot.TAG, detail);
        L.i(Boot.TAG, "识别到的类: " + outcome.found);

        // 装配点：等探针报告确认 FolderInfo / Workspace 的真实签名后，在此挂接：
        //   1) 读取现有桌面项 → 2) 按分类结果分组 → 3) 调 launcher 自己的建夹/移动方法
        // 不使用"直接改 launcher.db"的方式：schema 随 ROM 变，改坏即桌面重置。
    }

    public State state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public String targetPackage() {
        return pkg;
    }
}
