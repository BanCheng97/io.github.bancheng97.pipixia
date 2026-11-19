package com.halfcity.pipixia;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 配置。
 *
 * 路径：/sdcard/Android/data/com.sup.android.superb/files/pipixia_adfree.conf
 * 皮皮虾自己读这个目录不需要任何权限，模块写它也不用权限；文件不存在就生成默认值（全开）。
 *
 * 设计上「能改的都不写死在代码里」：版本一更新类名变了，改配置里的 force / skip 就能救，
 * 不用重新编译模块。
 */
public final class Config {

    /** 安全模式：只注入不装规则，用来二分「是模块的锅还是环境的锅」 */
    public static volatile boolean safe = false;
    public static volatile boolean debug = true;

    /** 皮皮虾自研广告（com.sup.android.superb.m_ad） */
    public static volatile boolean hookSelfAds = true;
    /** 穿山甲/优量汇/快手等广告 SDK 的 load/request/show 直接拦成“加载失败” */
    public static volatile boolean hookAdSdk = true;
    /** 拦的时候顺手回调 onNoAd/onError，App 才不会一直转圈 */
    public static volatile boolean hookAdNotify = true;

    /** 皮皮虾自己封装的广告加载方法（loadXxxAd/showXxxAd） */
    public static volatile boolean hookAdLocal = false;
    /** 开屏广告（类名/方法名带 splash） */
    public static volatile boolean hookSplash = true;
    /** 信息流广告：列表里带广告标记的条目直接摘掉 */
    public static volatile boolean hookFeed = false;
    /** 广告弹窗 / 浮层 / 插屏 */
    public static volatile boolean hookPopup = false;
    /** isShowAd / canShowAd / hasAd 这类开关全部返回 false */
    public static volatile boolean hookAdSwitch = false;
    /** 广告数据模型（xxxAdInfo / xxxAdConfig）构造时把字段刷成“无广告” */
    public static volatile boolean patchAdModel = false;
    /** 把扫到的目标包类名导出成 pipixia_classes.txt，版本更新后照着补规则 */
    public static volatile boolean dumpClasses = false;
    /** 自动送神评：评论右侧「送神评」按钮一出现就替你点 */
    public static volatile boolean autoShen = false;
    /** 两次点击的最小间隔 ms（防连点、防误触） */
    public static volatile int autoShenInterval = 1500;
    /** 单次进程最多点几次，到上限就停 */
    public static volatile int autoShenMax = 30;
    /** 长按返回键打开模块设置（桌面图标已隐藏） */
    /** 「我的」页右上角「净化设置」胶囊入口 */
    public static volatile boolean entryMine = true;
    /** 保存/播放无水印：把模型里的 playwm 地址改成 play */
    /** 无水印：走皮皮虾签名播放接口拿干净地址（真去水印） */
    public static volatile boolean cleanVideo = true;
    /** 日志总开关：关掉 = 一个文件都不写（功能照常，只是没记录） */
    public static volatile boolean logs = false;
    /** 写 pipixia_status.txt（关掉就完全不产生这个文件） */
    public static volatile boolean statusFile = true;
    /** 写 pipixia_crash.txt（崩溃记录；关掉只进 Xposed 日志） */
    public static volatile boolean crashFile = true;
    /** 类名侦察（写 pipixia_hooks.txt、上报版本适配信息） */
    public static volatile boolean discover = false;
    /** dex 枚举扫描（找开屏广告类） */
    public static volatile boolean dexScan = true;
    /** 地址取证：把见过的视频地址去重写到 pipixia_urls.txt（判断水印类型用） */
    public static volatile boolean dumpUrls = false;
    /** 换源：用抖音 play 接口按 video_id 重拼一条干净地址（片源水印才需要，默认关） */
    /** 隐藏客户端浮层水印（类名带 watermark 的 View） */
    public static volatile boolean hideWmView = true;
    /** 拦掉客户端画水印的方法（方法名带 watermark） */
    public static volatile boolean hookWmMethod = true;

    /* ---- 3.0 扩展 ---- */
    /** 扩展功能总开关（出问题可以一键关掉，只留去广告+去水印） */
    /** 去红点 */
    public static volatile boolean removeRedDot = false;
    /** 禁更新 */
    public static volatile boolean disableUpdate = false;
    /** 正常速倍速，1.0 = 不改 */
    public static volatile float playSpeed = 1.0f;
    /** 信息流净化总开关 */
    public static volatile boolean cleanFeed = false;
    public static volatile boolean removeLive = true;
    public static volatile boolean removePromo = true;
    public static volatile boolean removeOfficial = false;
    /** 屏蔽词，逗号/竖线分隔 */
    static volatile String[] blockKeywords = new String[0];
    /** 图片保存原图 */
    public static volatile boolean saveImage = true;

    /** UI 起来之后再开始扫类（启动期绝不 hook ClassLoader） */
    public static volatile boolean scanAfterUi = true;
    /** 扫描窗口秒数，到点自动 unhook ClassLoader；0 = 不限时 */
    public static volatile int scanSeconds = 180;

    static final Set<String> skipMethods = new HashSet<String>();
    static final Map<String, String> forces = new HashMap<String, String>();
    static final Set<String> extraPrefixes = new HashSet<String>();

    static final String[] BASE_PREFIXES = {"com.sup.android.superb"};

    static volatile String[] targetPrefixes = BASE_PREFIXES;
    static final int CONF_VERSION = 4;

    static volatile String source = "默认值";
    static volatile File file;

    private Config() {}

    /** 代码里的默认值（迁移旧配置时用） */
    private static void resetDefaults() {
        safe = false; debug = true;
        hookAdSdk = true; hookAdNotify = true; hookAdLocal = false; hookSelfAds = true;
        hookSplash = true; hookFeed = false; hookPopup = false; hookAdSwitch = false;
        patchAdModel = false; dumpClasses = false;
        autoShen = false; autoShenInterval = 1500; autoShenMax = 30;
        entryMine = true; cleanVideo = true; dexScan = true;
        logs = false; statusFile = true; crashFile = true; discover = false;
        dumpUrls = false; hideWmView = true; hookWmMethod = true;
        scanAfterUi = true; scanSeconds = 180;
        removeRedDot = false; disableUpdate = false; playSpeed = 1.0f;
        cleanFeed = false; removeLive = false; removePromo = false; removeOfficial = false;
        saveImage = true; blockKeywords = new String[0];
        skipMethods.clear(); forces.clear(); extraPrefixes.clear();
    }

    public static synchronized void load(Context ctx) {
        if (ctx == null) return;
        File f = null;
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir != null) f = new File(dir, "pipixia_adfree.conf");
        } catch (Throwable ignored) {}
        if (f == null) return;
        file = f;
        if (!f.exists()) {
            writeDefault(f);
            source = "新建默认配置 " + f.getAbsolutePath();
            return;
        }
        source = f.getAbsolutePath();

        int confVersion = 0;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                if (line.startsWith("#") || line.startsWith("//")) continue;
                int eq = line.indexOf('=');
                String k = (eq < 0 ? line : line.substring(0, eq)).trim().toLowerCase();
                String v = eq < 0 ? "true" : line.substring(eq + 1).trim();
                if ("conf_version".equals(k)) {
                    try { confVersion = Integer.parseInt(v.trim()); } catch (Throwable ignored) {}
                }
                else apply(k, v);
            }
        } catch (Throwable t) {
            Log.e("读配置失败", t);
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
        if (confVersion < CONF_VERSION) {
            // 老配置（1.x 写的）里有 save_nowm=true、scan_after_ui=true 这些现在会拖性能的项，
            // 整份作废重写成新默认，只保留 extra_prefix / force / skip 这几条自己加的规则
            Set<String> extraKeep = new HashSet<String>(extraPrefixes);
            Map<String, String> forceKeep = new HashMap<String, String>(forces);
            Set<String> skipKeep = new HashSet<String>(skipMethods);
            resetDefaults();
            extraPrefixes.addAll(extraKeep);
            forces.putAll(forceKeep);
            skipMethods.addAll(skipKeep);
            writeDefault(f);
            source = "老配置 v" + confVersion + " 已升级 v" + CONF_VERSION + " 并重写";
        }
        // 设置界面（ContentProvider）里的开关优先级最高
        try {
            java.util.Map<String, String> remote = RemotePrefs.read(ctx);
            for (java.util.Map.Entry<String, String> e : remote.entrySet()) {
                apply(e.getKey(), e.getValue());
            }
            if (!remote.isEmpty()) source = source + " + 设置界面(" + remote.size() + " 项)";
        } catch (Throwable ignored) {}
        cleanupFiles(ctx);
        rebuildPrefixes();
    }

    /** 开关关掉 → 对应文件删掉（不然用户会觉得「关了还在」） */
    private static void cleanupFiles(Context ctx) {
        try {
            if (!logs) {
                CrashLog.delete(CrashLog.STATUS_FILE);
                CrashLog.delete(CrashLog.CRASH_FILE);
                CrashLog.delete("pipixia_hooks.txt");
                CrashLog.delete("pipixia_urls.txt");
                CrashLog.delete("pipixia_classes.txt");
                return;
            }
            if (!statusFile) CrashLog.delete(CrashLog.STATUS_FILE);
            if (!crashFile) CrashLog.delete(CrashLog.CRASH_FILE);
            if (!discover) CrashLog.delete("pipixia_hooks.txt");
            if (!dumpUrls) CrashLog.delete("pipixia_urls.txt");
            String req = RemotePrefs.read(ctx).get("cleanup");
            if (req != null && req.length() > 0) {
                CrashLog.delete(CrashLog.STATUS_FILE);
                CrashLog.delete(CrashLog.CRASH_FILE);
                CrashLog.delete("pipixia_hooks.txt");
                CrashLog.delete("pipixia_urls.txt");
                CrashLog.delete("pipixia_classes.txt");
                Adapt.push(ctx, "cleanup", "");
            }
        } catch (Throwable ignored) {}
    }

    private static void writeDefault(File f) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            String body =
                    "# 皮皮虾净化 · 作者：半城\n"
                  + "# 改完杀进程重开皮皮虾即可，不用重装模块\n"
                  + "# 打不开 / 闪退：先把 safe 改成 true\n"
                  + "conf_version=4\n"
                  + "safe=false\n"
                  + "debug=true\n"
                  + "hook_ad_sdk=true        # 穿山甲/优量汇/快手 SDK 拦截\n"
                  + "hook_ad_notify=true     # 顺手回调 onNoAd/onError\n"
                  + "hook_ad_local=true      # 皮皮虾自己的加载方法\n"
                  + "hook_splash=true        # 开屏\n"
                  + "hook_feed=true          # 信息流广告条目\n"
                  + "hook_popup=true         # 弹窗/浮层/插屏\n"
                  + "hook_ad_switch=true     # isShowAd 之类开关\n"
                  + "patch_ad_model=true     # 广告模型字段\n"
                  + "dump_classes=false      # 导出扫到的类名 pipixia_classes.txt（调规则用）\n"
                  + "auto_shen=true           # 自动送神评（评论右侧按钮一出现就点）\n"
                  + "auto_shen_interval=1500  # 两次点击最小间隔 ms\n"
                  + "auto_shen_max=30         # 单次进程最多点几下\n"                  + "entry_mine=true          # 「我的」页右上角净化设置胶囊\n"                  + "clean_video=true         # 真去水印：走 i.snssdk.com 签名接口拿干净地址\n"
                  + "dex_scan=true            # dex 枚举找开屏广告类\n"
                  + "logs=false               # 日志总开关：false = 一个文件都不写\n"
                  + "status_file=true         # 写 pipixia_status.txt（logs=true 时才生效）\n"
                  + "crash_file=true          # 写 pipixia_crash.txt（崩溃记录）\n"
                  + "discover=false           # 类名侦察 / 钩子自检 pipixia_hooks.txt\n"
                  + "dump_urls=false          # 把见过的视频地址写到 pipixia_urls.txt（排查用）\n"                  + "remove_red_dot=true      # 去红点\n"
                  + "disable_update=true      # 禁自动更新\n"
                  + "save_image=true          # 图片保存原图\n"
                  + "clean_feed=true          # 信息流净化\n"
                  + "remove_live=true         # 去直播卡\n"
                  + "remove_promo=true        # 去带货卡\n"
                  + "remove_official=false    # 去官方号（默认关）\n"
                  + "block_keywords=          # 屏蔽词，逗号分隔\n"
                  + "play_speed=1.0           # 正常速倍速，1.0=不改\n"                  + "hide_wm_view=true        # 隐藏浮层水印 View\n"
                  + "hook_wm_method=true      # 拦客户端画水印的方法\n"
                  + "scan_after_ui=true      # UI 起来后才扫类（关掉=只保留 SDK 精确拦截）\n"
                  + "scan_seconds=180        # 扫描窗口，到点自动摘钩子，0=不限\n"
                  + "# 额外扫描前缀（可多行），比如广告类被塞到别的包\n"
                  + "# extra_prefix=com.sup.ad\n"
                  + "# 跳过某方法，防止误伤：skip=类名#方法名\n"
                  + "# force=类名#方法名=false   强制返回值 true/false/数字/字符串/null\n";
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try {
                w.write(body);
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {}
    }

    private static void apply(String k, String v) {
        if ("safe".equals(k)) safe = bool(v, safe);
        else if ("debug".equals(k)) debug = bool(v, debug);
        else if ("hook_ad_sdk".equals(k)) hookAdSdk = bool(v, hookAdSdk);
        else if ("hook_self_ads".equals(k)) hookSelfAds = bool(v, hookSelfAds);
        else if ("hook_ad_notify".equals(k)) hookAdNotify = bool(v, hookAdNotify);
        else if ("hook_ad_local".equals(k)) hookAdLocal = bool(v, hookAdLocal);
        else if ("hook_splash".equals(k)) hookSplash = bool(v, hookSplash);
        else if ("hook_feed".equals(k)) hookFeed = bool(v, hookFeed);
        else if ("hook_popup".equals(k)) hookPopup = bool(v, hookPopup);
        else if ("hook_ad_switch".equals(k)) hookAdSwitch = bool(v, hookAdSwitch);
        else if ("patch_ad_model".equals(k)) patchAdModel = bool(v, patchAdModel);
        else if ("dump_classes".equals(k)) dumpClasses = bool(v, dumpClasses);
        else if ("auto_shen".equals(k)) autoShen = bool(v, autoShen);
        else if ("auto_shen_interval".equals(k)) { try { autoShenInterval = Integer.parseInt(v.trim()); } catch (Throwable ignored) {} }
        else if ("auto_shen_max".equals(k)) { try { autoShenMax = Integer.parseInt(v.trim()); } catch (Throwable ignored) {} }
        else if ("entry_mine".equals(k)) entryMine = bool(v, entryMine);
        else if ("dump_urls".equals(k)) dumpUrls = bool(v, dumpUrls);
        else if ("clean_video".equals(k)) cleanVideo = bool(v, cleanVideo);
        else if ("remove_red_dot".equals(k)) removeRedDot = bool(v, removeRedDot);
        else if ("disable_update".equals(k)) disableUpdate = bool(v, disableUpdate);
        else if ("play_speed".equals(k)) { try { playSpeed = Float.parseFloat(v.trim()); } catch (Throwable ignored) {} }
        else if ("clean_feed".equals(k)) cleanFeed = bool(v, cleanFeed);
        else if ("remove_live".equals(k)) removeLive = bool(v, removeLive);
        else if ("remove_promo".equals(k)) removePromo = bool(v, removePromo);
        else if ("remove_official".equals(k)) removeOfficial = bool(v, removeOfficial);
        else if ("save_image".equals(k)) saveImage = bool(v, saveImage);
        else if ("block_keywords".equals(k)) {
            String[] parts = v.split("[,，|、\\s]+");
            List<String> keep = new ArrayList<String>();
            for (String p : parts) if (p.length() > 0) keep.add(p);
            blockKeywords = keep.toArray(new String[0]);
        }
        else if ("dex_scan".equals(k)) dexScan = bool(v, dexScan);
        else if ("logs".equals(k)) logs = bool(v, logs);
        else if ("status_file".equals(k)) statusFile = bool(v, statusFile);
        else if ("crash_file".equals(k)) crashFile = bool(v, crashFile);
        else if ("discover".equals(k)) discover = bool(v, discover);
        else if ("hide_wm_view".equals(k)) hideWmView = bool(v, hideWmView);
        else if ("hook_wm_method".equals(k)) hookWmMethod = bool(v, hookWmMethod);
        else if ("scan_after_ui".equals(k)) scanAfterUi = bool(v, scanAfterUi);
        else if ("scan_seconds".equals(k)) { try { scanSeconds = Integer.parseInt(v.trim()); } catch (Throwable ignored) {} }
        else if ("extra_prefix".equals(k)) { if (v.length() > 0) extraPrefixes.add(v); }
        else if ("skip".equals(k)) { if (v.length() > 0) skipMethods.add(v); }
        else if ("force".equals(k)) {
            int eq = v.lastIndexOf('=');
            if (eq > 0) forces.put(v.substring(0, eq).trim(), v.substring(eq + 1).trim());
        }
    }

    private static void rebuildPrefixes() {
        String[] all = new String[BASE_PREFIXES.length + extraPrefixes.size()];
        System.arraycopy(BASE_PREFIXES, 0, all, 0, BASE_PREFIXES.length);
        int i = BASE_PREFIXES.length;
        for (String p : extraPrefixes) all[i++] = p;
        targetPrefixes = all;
    }

    private static boolean bool(String v, boolean def) {
        if (v == null) return def;
        v = v.trim().toLowerCase();
        // 行内注释：# 之后不要
        int hash = v.indexOf('#');
        if (hash >= 0) v = v.substring(0, hash).trim();
        if ("1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v)) return true;
        if ("0".equals(v) || "false".equals(v) || "no".equals(v) || "off".equals(v)) return false;
        return def;
    }

    static boolean skip(String cls, String mth) {
        return skipMethods.contains(cls + "#" + mth);
    }

    static String forceValue(String cls, String mth) {
        String v = forces.get(cls + "#" + mth);
        if (v == null) v = forces.get("*#" + mth);
        return v;
    }

    static String dump() {
        return "safe=" + safe + " debug=" + debug + " adSdk=" + hookAdSdk
                + " notify=" + hookAdNotify + " adLocal=" + hookAdLocal
                + " splash=" + hookSplash + " popup=" + hookPopup
                + " switch=" + hookAdSwitch + " model=" + patchAdModel
                + " shen=" + autoShen + " clean=" + cleanVideo + " feed=" + cleanFeed
                + " reddot=" + removeRedDot + " update=" + disableUpdate
                + " mine=" + entryMine + " logs=" + logs + " status=" + statusFile + " crash=" + crashFile + " disc=" + discover + " dexScan=" + dexScan
                + " scanAfterUi=" + scanAfterUi + " scanSec=" + scanSeconds
                + " extra=" + extraPrefixes.size() + " force=" + forces.size();
    }
}
