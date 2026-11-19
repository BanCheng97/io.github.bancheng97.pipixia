package com.halfcity.pipixia;

import java.util.ArrayList;
import java.util.List;

/**
 * 功能开关表（母类 → 子项）。
 *
 * 只留实测有命中的：自研广告拦截、开屏、视频无水印（签名接口）、图片原图、入口、日志、调试。
 * 删掉的：送神评（0 命中）、信息流净化（0 命中）、红点/更新/倍速（未验证）、
 * 弹窗/广告开关/业务层广告/广告模型（被自研广告拦截覆盖）、地址与类名导出（排查用完）。
 */
final class Feat {

    static final int BOOL = 0, TEXT = 1, NUM = 2;

    final String group;
    final String key;
    final String title;
    final int type;
    final String def;

    Feat(String group, String key, String title, int type, String def) {
        this.group = group;
        this.key = key;
        this.title = title;
        this.type = type;
        this.def = def;
    }

    static final Feat[] ALL = {
            /* ---------------- 去广告 ---------------- */
            new Feat("去广告", "hook_self_ads", "自研广告（m_ad，开屏 / 信息流 / 评论区）", BOOL, "true"),
            new Feat("去广告", "hook_splash", "开屏广告", BOOL, "true"),
            new Feat("去广告", "hook_ad_sdk", "第三方广告 SDK（穿山甲等）", BOOL, "true"),

            /* ---------------- 去水印 ---------------- */
            new Feat("去水印", "clean_video", "视频无水印（签名接口，播放与保存都干净）", BOOL, "true"),
            new Feat("去水印", "save_image", "图片保存原图", BOOL, "true"),

            /* ---------------- 入口 ---------------- */
            new Feat("入口", "entry_mine", "「我的 → 更多功能」里显示净化设置", BOOL, "true"),

            /* ---------------- 日志 ---------------- */
            new Feat("日志", "logs", "写日志文件（总开关）", BOOL, "false"),
            new Feat("日志", "status_file", "运行状态 pipixia_status.txt", BOOL, "true"),
            new Feat("日志", "crash_file", "崩溃记录 pipixia_crash.txt", BOOL, "true"),

            /* ---------------- 调试 ---------------- */
            new Feat("调试", "debug", "详细日志（Xposed 日志，不落盘）", BOOL, "true"),
            new Feat("调试", "safe", "安全模式（只注入，不干活）", BOOL, "false"),
    };

    static List<String> groups() {
        List<String> out = new ArrayList<String>();
        for (Feat f : ALL) if (!out.contains(f.group)) out.add(f.group);
        return out;
    }

    static List<Feat> of(String group) {
        List<Feat> out = new ArrayList<Feat>();
        for (Feat f : ALL) if (f.group.equals(group)) out.add(f);
        return out;
    }

    static Feat byKey(String key) {
        for (Feat f : ALL) if (f.key.equals(key)) return f;
        return null;
    }
}
