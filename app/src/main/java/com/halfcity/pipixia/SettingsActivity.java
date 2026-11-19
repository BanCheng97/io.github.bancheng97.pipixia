package com.halfcity.pipixia;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置界面（4.1 美化版）。
 *
 * 全部代码拼 UI，不放 XML：这个 APK 是 aapt 直接打的，少一层资源编译就少一类坑。
 * 视觉上：深色底 + 圆角卡片 + 橙红强调色，分组用色条，顶部有总览和全开/全关。
 */
public class SettingsActivity extends Activity {

    // 配色
    private static final int BG = 0xFF0F1115;
    private static final int CARD = 0xFF1A1D24;
    private static final int CARD_EDGE = 0xFF262A33;
    private static final int TEXT = 0xFFEDEFF4;
    private static final int SUB = 0xFF8A93A5;
    private static final int ACCENT = 0xFFFF6B4A;
    private static final int ACCENT_DIM = 0x33FF6B4A;
    private static final int ON = 0xFF37D67A;
    private static final int OFF = 0xFF555B68;

    private final List<Switch> switches = new ArrayList<Switch>();
    private final java.util.Map<String, TextView> counts = new java.util.HashMap<String, TextView>();
    private TextView summary;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, dp(40));
        scroll.addView(root);
        setContentView(scroll);

        header();
        adaptSection();
        summaryCard();
        for (String group : Feat.groups()) {
            groupHeader(group);
            for (Feat f : Feat.of(group)) root.addView(row(f));
        }
        footer();
        updateSummary();
    }

    /* ---------------- 顶部 ---------------- */

    private void header() {
        TextView title = new TextView(this);
        title.setText("皮皮虾净化");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("v" + MainHook.VERSION + " · 作者：半城");
        sub.setTextColor(ACCENT);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, dp(4), 0, 0);
        root.addView(sub);

        TextView tip = new TextView(this);
        tip.setText("改完把皮皮虾「强行停止」再打开就生效");
        tip.setTextColor(SUB);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setPadding(0, dp(10), 0, dp(6));
        root.addView(tip);

        View line = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        lp.topMargin = dp(12);
        line.setLayoutParams(lp);
        line.setBackgroundColor(ACCENT_DIM);
        root.addView(line);
    }

    private void summaryCard() {
        LinearLayout card = card();
        root.addView(card);
        summary = new TextView(this);
        summary.setTextColor(TEXT);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        card.addView(summary);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(12), 0, 0);
        bar.addView(pill("全部开启", new View.OnClickListener() {
            @Override
            public void onClick(View v) { setAll(true); }
        }));
        bar.addView(pill("全部关闭", new View.OnClickListener() {
            @Override
            public void onClick(View v) { setAll(false); }
        }));
        bar.addView(pill("恢复默认", new View.OnClickListener() {
            @Override
            public void onClick(View v) { resetDefaults(); }
        }));
        bar.addView(pill("日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showLogs(); }
        }));
        card.addView(bar);
    }

    private TextView pill(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(ACCENT);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT_DIM);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x66FF6B4A);
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(l);
        return tv;
    }

    /* ---------------- 版本适配验证 ---------------- */

    private static final String[][] ADAPT_ITEMS = {
            {"adapt_pkg", "皮皮虾版本"},
            {"adapt_dexCount", "dex 类总数"},
            {"adapt_videoModel", "播放模型 VideoModel#getUrlList"},
            {"adapt_download", "下载入口 VideoDownloadHelper"},
            {"adapt_selfads", "自研广告 m_ad 适配"},
            {"adapt_splash", "开屏广告类"},
            {"adapt_stats", "上次运行计数"},
            {"adapt_time", "最近一次自检时间"},
    };

    private void adaptSection() {
        LinearLayout card = card();
        root.addView(card);

        TextView title = new TextView(this);
        title.setText("版本适配验证");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(title);

        TextView tip = new TextView(this);
        tip.setText("下面这些是模块在皮皮虾进程里自检出来的结果，打开一次皮皮虾后会自动刷新。");
        tip.setTextColor(SUB);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tip.setPadding(0, dp(4), 0, dp(8));
        card.addView(tip);

        boolean any = false;
        for (String[] item : ADAPT_ITEMS) {
            String v = PrefsStore.get(this, item[0], "");
            if (v == null || v.length() == 0) v = "—";
            if (!"—".equals(v)) any = true;
            card.addView(kv(item[1], v));
        }

        TextView verdict = new TextView(this);
        if (!any) {
            verdict.setText("还没采集到：打开一次皮皮虾，再回来看。");
            verdict.setTextColor(SUB);
        } else {
            boolean ok = "已找到".equals(PrefsStore.get(this, "adapt_videoModel", ""))
                    || "已找到".equals(PrefsStore.get(this, "adapt_download", ""));
            verdict.setText(ok ? "结论：关键挂钩点已适配当前版本"
                    : "结论：关键挂钩点没命中，版本变了，需要更新挂钩点");
            verdict.setTextColor(ok ? ON : ACCENT);
        }
        verdict.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        verdict.setPadding(0, dp(8), 0, 0);
        card.addView(verdict);
    }

    private View kv(String key, String value) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(3), 0, dp(3));

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextColor(SUB);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        k.setLayoutParams(lp);
        line.addView(k);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setGravity(Gravity.END);
        line.addView(v);
        return line;
    }

    /* ---------------- 分组 ---------------- */

    private void groupHeader(final String name) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(22), 0, dp(8));

        View dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(4), dp(16));
        dot.setLayoutParams(dlp);
        GradientDrawable dbg = new GradientDrawable();
        dbg.setColor(ACCENT);
        dbg.setCornerRadius(dp(2));
        dot.setBackground(dbg);
        box.addView(dot);

        TextView tv = new TextView(this);
        tv.setText("  " + name);
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(lp);
        box.addView(tv);

        final TextView all = new TextView(this);
        all.setText("全开");
        all.setTextColor(ACCENT);
        all.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        all.setPadding(dp(8), 0, dp(4), 0);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean allOn = true;
                for (Feat f : Feat.of(name)) {
                    if (f.type == Feat.BOOL
                            && !"true".equalsIgnoreCase(PrefsStore.get(SettingsActivity.this, f.key, f.def))) {
                        allOn = false;
                        break;
                    }
                }
                for (Feat f : Feat.of(name)) {
                    if (f.type == Feat.BOOL) {
                        PrefsStore.put(SettingsActivity.this, f.key, String.valueOf(!allOn));
                    }
                }
                renderGroups();
                toast(name + (allOn ? " 已全部关闭" : " 已全部开启"));
            }
        });
        box.addView(all);
        root.addView(box);
    }

    /** 改完全部/分组之后，把界面上的开关状态同步回来（不重建 Activity） */
    private void updateCounts() {
        for (String g : Feat.groups()) {
            int on = 0, total = 0;
            for (Feat f : Feat.of(g)) {
                if (f.type != Feat.BOOL) continue;
                total++;
                if ("true".equalsIgnoreCase(PrefsStore.get(this, f.key, f.def))) on++;
            }
            TextView tv = counts.get(g);
            if (tv != null) tv.setText(on + "/" + total);
        }
    }

    private void renderGroups() {
        updateCounts();
        for (Switch sw : switches) {
            Object tag = sw.getTag();
            if (!(tag instanceof Feat)) continue;
            Feat f = (Feat) tag;
            String v = PrefsStore.get(this, f.key, f.def);
            sw.setOnCheckedChangeListener(null);
            sw.setChecked("true".equalsIgnoreCase(v));
            sw.setOnCheckedChangeListener(listener(f));
        }
        updateSummary();
        updateCounts();
    }

    /* ---------------- 行 ---------------- */

    private View row(final Feat f) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(tlp);

        TextView title = new TextView(this);
        title.setText(f.title);
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        texts.addView(title);

        TextView key = new TextView(this);
        key.setText(f.key);
        key.setTextColor(SUB);
        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        key.setPadding(0, dp(3), 0, 0);
        texts.addView(key);
        card.addView(texts);

        if (f.type == Feat.BOOL) {
            Switch sw = new Switch(this);
            sw.setTag(f);
            sw.setChecked("true".equalsIgnoreCase(PrefsStore.get(this, f.key, f.def)));
            tintSwitch(sw);
            sw.setOnCheckedChangeListener(listener(f));
            switches.add(sw);
            card.addView(sw);
        } else {
            final EditText et = new EditText(this);
            et.setText(PrefsStore.get(this, f.key, f.def));
            et.setSingleLine(true);
            et.setTextColor(TEXT);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            et.setGravity(Gravity.END);
            et.setMinWidth(dp(96));
            et.setBackgroundColor(Color.TRANSPARENT);
            if (f.type == Feat.NUM) {
                et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            }
            et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (!hasFocus) {
                        PrefsStore.put(SettingsActivity.this, f.key, et.getText().toString());
                        toast("已保存 " + f.key);
                    }
                }
            });
            card.addView(et);
        }
        return card;
    }

    private CompoundButton.OnCheckedChangeListener listener(final Feat f) {
        return new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                PrefsStore.put(SettingsActivity.this, f.key, String.valueOf(checked));
                updateSummary();
            }
        };
    }

    private void tintSwitch(Switch sw) {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                int[][] states = new int[][]{
                        new int[]{android.R.attr.state_checked}, new int[]{}
                };
                sw.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, OFF}));
                sw.setTrackTintList(new ColorStateList(states, new int[]{0x66FF6B4A, 0x33FFFFFF}));
            } catch (Throwable ignored) {}
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), CARD_EDGE);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    /* ---------------- 底部 ---------------- */

    private void footer() {
        LinearLayout card = card();
        TextView tv = new TextView(this);
        tv.setText("日志放在模块自己的数据目录里（/data/data/com.halfcity.pipixia/files/logs/），\n"
                + "卸载模块就一起没了，不往下载目录丢文件。\n"
                + "配置文件  Android/data/com.sup.android.superb/files/pipixia_adfree.conf\n\n"
                + "设置界面 > 配置文件 > 代码默认值，三层优先级。");
        tv.setTextColor(SUB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        card.addView(tv);
        root.addView(card);
    }

    /** 日志：查看 / 清空 / 导出（导出才写共享存储） */
    private void showLogs() {
        final String[] names = {"pipixia_status.txt", "pipixia_crash.txt", "pipixia_hooks.txt",
                "pipixia_urls.txt", "pipixia_classes.txt"};
        final StringBuilder all = new StringBuilder();
        for (String n : names) {
            String t = null;
            try {
                android.os.Bundle b = getContentResolver().call(
                        android.net.Uri.parse("content://io.github.bancheng97.pipixia.prefs"), "logread", n, null);
                t = b == null ? null : b.getString("text");
            } catch (Throwable ignored) {}
            all.append("==== ").append(n).append(" ====\n");
            all.append(t == null || t.length() == 0 ? "(空)\n" : t);
            all.append('\n');
        }
        final String text = all.toString();
        new android.app.AlertDialog.Builder(this)
                .setTitle("日志（存在模块数据目录）")
                .setMessage(text.length() > 6000 ? text.substring(0, 6000) + "\n…（截断）" : text)
                .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) { clearLogs(); }
                })
                .setNeutralButton("导出", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) { exportLogs(); }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void clearLogs() {
        try {
            getContentResolver().call(android.net.Uri.parse("content://io.github.bancheng97.pipixia.prefs"), "logclear", null, null);
            toast("日志已清空");
        } catch (Throwable t) { toast("清空失败"); }
    }

    private void exportLogs() {
        String[] names = {"pipixia_status.txt", "pipixia_crash.txt", "pipixia_hooks.txt",
                "pipixia_urls.txt", "pipixia_classes.txt"};
        int ok = 0;
        for (String n : names) {
            try {
                android.os.Bundle b = getContentResolver().call(
                        android.net.Uri.parse("content://io.github.bancheng97.pipixia.prefs"), "logexport", n, null);
                if (b != null && b.getBoolean("ok")) ok++;
            } catch (Throwable ignored) {}
        }
        toast(ok > 0 ? "已导出 " + ok + " 个到 Download/DSH-Go" : "没有可导出的日志");
    }

    private void updateSummary() {
        int on = 0, total = 0;
        for (Feat f : Feat.ALL) {
            if (f.type != Feat.BOOL) continue;
            total++;
            if ("true".equalsIgnoreCase(PrefsStore.get(this, f.key, f.def))) on++;
        }
        summary.setText("已开启 " + on + " / " + total + " 项");
    }

    private void setAll(boolean on) {
        for (Feat f : Feat.ALL) {
            // 「全部开启」不能顺手把安全模式打开，否则等于把模块关了
            if ("safe".equals(f.key)) continue;
            if (f.type == Feat.BOOL) PrefsStore.put(this, f.key, String.valueOf(on));
        }
        renderGroups();
        toast(on ? "全部开启" : "全部关闭");
    }

    private void resetDefaults() {
        for (Feat f : Feat.ALL) PrefsStore.put(this, f.key, f.def);
        renderGroups();
        toast("已恢复默认");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
