package com.coby.deskly.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.coby.deskly.R;

/**
 * 「关于」卡片：版本、作者、反馈入口、免责声明、捐赠与版权。
 *
 * <p>文案本身留在布局里（静态、好改），这个类只干三件需要代码的事：
 * 把版本号从 {@code PackageManager} 读出来（升版本只改 build.gradle.kts 一处，
 * 界面上不会出现"装的是新的、写的是旧的"）、把 GitHub 与捐赠变成真能点的入口、
 * 长按复制仓库地址（手机上想贴到浏览器或聊天窗口时，比手抄一条 URL 现实）。
 *
 * <p><b>只显示版本名，不带构建号。</b>构建号是发版内部用的序号，用户反馈问题时说的
 * 永远是"0.12.x"；两个数字挤在一行，反而让真正要读的那个变得不显眼。
 *
 * <p>捐赠没有在 App 里塞二维码：这个仓库的收款码是要维护者自己替换的占位图，
 * 把它打进 APK 只会得到一张扫不出来的图。点「打开」跳到项目主页，README 里那一节
 * 永远是新的，换码也不用重新发版。
 */
public final class AboutSection {

    /** 仓库地址。README、release、issue 都在这一处。 */
    private static final String GITHUB = "https://github.com/Cobylinweiqi/Deskly";

    private final Activity act;

    public AboutSection(Activity act, View root) {
        this.act = act;

        TextView tvVersion = root.findViewById(R.id.tvAboutVersion);
        TextView tvHint = root.findViewById(R.id.tvAboutHint);
        String name = versionName();
        // 只显示版本名。构建号对用户没有意义（问题反馈时看的是版本名），
        // 放在同一行反而把「v0.12.3」这个真正要读的东西挤成了次要信息。
        tvVersion.setText("v" + name);
        tvHint.setText("v" + name + " · Coby");

        View github = root.findViewById(R.id.rowAboutGithub);
        github.setOnClickListener(v -> open(GITHUB, ""));
        github.setOnLongClickListener(v -> {
            copy(GITHUB);
            return true;
        });

        root.findViewById(R.id.btnAboutDonate).setOnClickListener(v ->
                open(GITHUB, "收款码在项目主页「请作者喝咖啡」一节"));
    }

    // ───────────────────────── 打开与复制 ─────────────────────────

    private void open(String url, String tip) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            if (!tip.isEmpty()) {
                toast(tip);
            }
        } catch (Throwable t) {
            // 没装浏览器 / 被系统策略挡住时，至少把地址交到手上
            copy(url);
            toast("打不开浏览器，地址已复制：" + url);
        }
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Deskly", text));
            }
        } catch (Throwable ignored) {
        }
    }

    // ───────────────────────── 版本 ─────────────────────────

    private PackageInfo info() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private String versionName() {
        PackageInfo pi = info();
        return pi == null || pi.versionName == null ? "" : pi.versionName;
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }
}
