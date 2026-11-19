package com.halfcity.pipixia;

import java.util.ArrayList;
import java.util.List;

/**
 * 功能开关表（母类 → 子项）。
 *
 * 只留真正跑得起来、且实测有意义的项：
 *  - 去水印（签名接口）、图片原图：实测有效；
 *  - 自研广告拦截（m_ad）：实测拦到 20+ 条；
 *  - 送神评、信息流净化、红点/更新/倍速：有明确挂钩点；
 *  - 调试三项：排查用。
 * 已经删掉的：playwm 改地址（皮皮虾不适用）、换源（会 404）、长按返回入口（不灵）、
 * 扩展总开关（有分项开关就够了）、隐藏浮层水印（这版没找到水印 View）。
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
            new Feat("去广告", "hook_self_ads", "自研广告（m_ad，开屏也走这里）", BOOL, "true"),
            new Feat("去广告", "hook_ad_sdk", "第三方广告 SDK（穿山甲/优量汇等）", BOOL, "true"),
            new Feat("去广告", "hook_splash", "开屏广告", BOOL, "true"),
            new Feat("去广告", "hook_popup", "弹窗 / 插屏 / 浮层", BOOL, "true"),
            new Feat("去广告", "hook_ad_switch", "广告开关（isShowAd 一类）", BOOL, "true"),

            /* ---------------- 去水印 ---------------- */
            new Feat("去水印", "clean_video", "视频无水印（签名接口拿干净地址）", BOOL, "true"),
            new Feat("去水印", "save_image", "图片保存原图", BOOL, "true"),

            /* ---------------- 自动 ---------------- */
            new Feat("自动", "auto_shen", "自动送神评（评论区按钮一出现就点）", BOOL, "true"),
            new Feat("自动", "auto_shen_interval", "两次点击最小间隔（毫秒）", NUM, "1500"),
            new Feat("自动", "auto_shen_max", "单次进程最多送几次", NUM, "30"),

            /* ---------------- 内容净化 ---------------- */
            new Feat("内容净化", "clean_feed", "信息流净化总开关", BOOL, "true"),
            new Feat("内容净化", "remove_live", "去直播卡", BOOL, "true"),
            new Feat("内容净化", "remove_promo", "去带货卡", BOOL, "true"),
            new Feat("内容净化", "remove_official", "去官方号", BOOL, "false"),
            new Feat("内容净化", "block_keywords", "屏蔽词（逗号分隔）", TEXT, ""),

            /* ---------------- 杂项 ---------------- */
            new Feat("杂项", "remove_red_dot", "去红点", BOOL, "true"),
            new Feat("杂项", "disable_update", "禁止自动更新", BOOL, "true"),
            new Feat("杂项", "play_speed", "正常速倍速（1.0 = 不改）", NUM, "1.0"),

            /* ---------------- 入口 ---------------- */
            new Feat("入口", "entry_mine", "「我的 → 更多功能」里显示净化设置", BOOL, "true"),

            /* ---------------- 调试 ---------------- */
            new Feat("调试", "debug", "详细日志", BOOL, "true"),
            new Feat("调试", "safe", "安全模式（只注入，不干活）", BOOL, "false"),
            new Feat("调试", "dump_urls", "记录视频地址到 pipixia_urls.txt", BOOL, "false"),
            new Feat("调试", "status_file", "写运行状态 pipixia_status.txt", BOOL, "true"),
            new Feat("调试", "crash_file", "写崩溃记录 pipixia_crash.txt", BOOL, "true"),
            new Feat("调试", "discover", "类名侦察 / 钩子自检 pipixia_hooks.txt", BOOL, "false"),
            new Feat("调试", "dump_classes", "导出扫到的类名", BOOL, "false"),
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
