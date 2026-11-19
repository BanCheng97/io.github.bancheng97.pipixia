package com.halfcity.pipixia;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 规则引擎 v1.2。
 *
 * v1.1 白屏的教训：启动期就 hook ClassLoader.loadClass 是自杀。
 * App 启动时要连加载上万个类，每一次都进一遍 Java 层 hook，主线程直接被拖死；
 * 更糟的是 Xposed 挂方法钩子和 ART 定义类共用同一套锁，很容易死锁——现象就是
 * 日志停在第 4 行、界面一片白、进程还不退出。
 *
 * v1.2 改成三段式，把风险摊开：
 *
 *   ① 启动期（handleLoadPackage）只做最精准的事：按已知 SDK 公开 API 类名
 *      TTAdNative / TTSplashAd / SplashAD ... 一个个 findClassIfExists 去挂。
 *      公开 API 类名不做混淆，跨版本稳定，而且完全不碰 ClassLoader。
 *   ② UI 起来之后（Instrumentation.callActivityOnResume 触发）才装 ClassLoader 钩子，
 *      这时启动期的类加载已经结束，后台线程慢慢扫，不影响主线程。
 *   ③ 扫描有硬上限（默认 180 秒），到点自动 unhook ClassLoader，之后模块只剩
 *      已经挂好的方法钩子，几乎零开销。
 */
public final class Engine {

    /** 已知广告 SDK 的公开入口类。混淆只混淆实现，不混淆这些 API 类名。 */
    private static final String[] SDK_CLASSES = {
            // 穿山甲
            "com.bytedance.sdk.openadsdk.TTAdSdk",
            "com.bytedance.sdk.openadsdk.TTAdManager",
            "com.bytedance.sdk.openadsdk.TTAdNative",
            "com.bytedance.sdk.openadsdk.TTSplashAd",
            "com.bytedance.sdk.openadsdk.TTFeedAd",
            "com.bytedance.sdk.openadsdk.TTDrawFeedAd",
            "com.bytedance.sdk.openadsdk.TTNativeExpressAd",
            "com.bytedance.sdk.openadsdk.TTRewardVideoAd",
            "com.bytedance.sdk.openadsdk.TTFullScreenVideoAd",
            "com.bytedance.sdk.openadsdk.TTInterstitialAd",
            // 优量汇
            "com.qq.e.ads.splash.SplashAD",
            "com.qq.e.ads.nativ.NativeUnifiedAD",
            "com.qq.e.ads.nativ.NativeExpressAD",
            "com.qq.e.ads.rewardvideo.RewardVideoAD",
            "com.qq.e.ads.interstitial.InterstitialAD",
            "com.qq.e.ads.interstitial2.UnifiedInterstitialAD",
            "com.qq.e.comm.managers.GDTADManager",
            // 快手联盟
            "com.kwad.sdk.api.KsSplashScreenAd",
            "com.kwad.sdk.api.KsRewardVideoAd",
            "com.kwad.sdk.api.KsFullScreenVideoAd",
            "com.kwad.sdk.api.KsDrawAd",
            "com.kwad.sdk.api.KsNativeAd",
    };

    private static final Set<String> QUEUED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ConcurrentLinkedQueue<Class<?>> PENDING =
            new ConcurrentLinkedQueue<Class<?>>();
    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> REPORT = Collections.synchronizedSet(new LinkedHashSet<String>());
    private static final Set<String> CLASSES = Collections.synchronizedSet(new LinkedHashSet<String>());

    private static volatile boolean safeMode;
    private static volatile Context appCtx;
    private static volatile boolean uiReady;
    private static volatile boolean configLoaded;
    private static volatile boolean classHookInstalled;
    private static volatile boolean dumped;
    private static volatile long scanStart;
    private static volatile String lastStatusText;

    private static Method loadClassMethod;
    private static XC_MethodHook loadClassCb;

    private Engine() {}

    /* ---------------- ① 启动期：只挂 SDK 入口 + UI 信号 ---------------- */

    public static void installEarlyHooks(ClassLoader cl) {
        hookSdkClasses(cl);
        hookUiSignals();
        installCrashLogger();
        TargetHooks.install(cl);
        SelfAds.install(cl);
        ExtraHooks.install(cl);
        AutoActions.install();
        MineEntry.install(cl);
    }

    /**
     * 崩溃取证：把 App 的未捕获异常打到模块日志里。
     * 有了这个，闪退不用猜——日志里会直接出现 `崩溃 java.lang.XXX` 和完整栈。
     */
    private static void installCrashLogger() {
        try {
            Method m = null;
            for (Method cand : Thread.class.getDeclaredMethods()) {
                if (cand.getName().equals("dispatchUncaughtException")
                        && cand.getParameterTypes().length == 1) {
                    m = cand;
                    break;
                }
            }
            if (m == null) return;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object t = param.args == null || param.args.length == 0 ? null : param.args[0];
                        if (t instanceof Throwable) {
                            Throwable th = (Throwable) t;
                            Log.i("崩溃 " + th);
                            CrashLog.record("崩溃", th + "\n" + stackOf(th));
                            StackTraceElement[] st = th.getStackTrace();
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < st.length && i < 25; i++) {
                                sb.append("\n    at ").append(st[i]);
                            }
                            Log.i("崩溃栈:" + sb);
                            Throwable cause = th.getCause();
                            if (cause != null) Log.i("崩溃原因 " + cause);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            Log.d("崩溃记录器已挂");
        } catch (Throwable t) {
            Log.d("崩溃记录器失败 " + t);
        }
    }

    private static String stackOf(Throwable th) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] st = th.getStackTrace();
        for (int i = 0; i < st.length && i < 30; i++) {
            sb.append("    at ").append(st[i]).append('\n');
        }
        Throwable c = th.getCause();
        if (c != null) {
            sb.append("  caused by ").append(c).append('\n');
            StackTraceElement[] cs = c.getStackTrace();
            for (int i = 0; i < cs.length && i < 15; i++) {
                sb.append("    at ").append(cs[i]).append('\n');
            }
        }
        return sb.toString();
    }

    /** 配置读完之后、且不在安全模式，规则才允许动手 */
    static boolean isEnabled() {
        return configLoaded && !safeMode;
    }

    private static void hookSdkClasses(ClassLoader cl) {
        int hit = 0;
        for (String name : SDK_CLASSES) {
            try {
                Class<?> c = XposedHelpers.findClassIfExists(name, cl);
                if (c == null) continue;
                AdHooks.processSdkClass(c);
                hit++;
            } catch (Throwable ignored) {}
        }
        Log.d("SDK 入口类命中 " + hit + "/" + SDK_CLASSES.length);
    }

    /** 这两个都是框架类，挂它们不碰 App 的类加载，安全。 */
    private static void hookUiSignals() {
        try {
            XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onUiReady();
                }
            });
        } catch (Throwable t) {
            Log.d("挂 Instrumentation 失败 " + t);
        }
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onUiReady();
                }
            });
        } catch (Throwable t) {
            Log.d("挂 Activity 失败 " + t);
        }
    }

    /** UI 起来了才允许装 ClassLoader 钩子（幂等，随便调） */
    private static void onUiReady() {
        if (uiReady) return;
        uiReady = true;
        if (!Config.scanAfterUi) {
            Log.i("UI 就绪，按配置跳过类扫描（只保留 SDK 精确拦截）");
            return;
        }
        installClassLoaderHook();
        Log.i("UI 就绪，已开始后台扫描类");
    }

    /* ---------------- ② UI 之后：懒扫描 ---------------- */

    static void installClassLoaderHook() {
        if (classHookInstalled) return;
        classHookInstalled = true;
        try {
            Class.forName("java.util.concurrent.ConcurrentLinkedQueue");
            Class.forName("java.util.concurrent.ConcurrentHashMap");

            loadClassCb = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (!(r instanceof Class)) return;
                    try {
                        Class<?> c = (Class<?>) r;
                        String n = c.getName();
                        if (!isWanted(n)) return;
                        if (!QUEUED.add(n)) return;
                        PENDING.offer(c);
                    } catch (Throwable ignored) {}
                }
            };
            Method[] ms = ClassLoader.class.getDeclaredMethods();
            for (Method m : ms) {
                if (!"loadClass".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                loadClassMethod = m;
                XposedBridge.hookMethod(m, loadClassCb);
                break;
            }
            scanStart = System.currentTimeMillis();
            Log.d("ClassLoader 扫描钩子已装（限时 " + Config.scanSeconds + "s）");
        } catch (Throwable t) {
            Log.e("装 ClassLoader 钩子失败", t);
        }
    }

    private static void stopScan() {
        if (loadClassMethod == null || loadClassCb == null) return;
        try {
            XposedBridge.unhookMethod(loadClassMethod, loadClassCb);
            Log.i("扫描窗口结束，已摘掉 ClassLoader 钩子（共扫 " + SEEN.size() + " 个类）");
        } catch (Throwable ignored) {}
        loadClassMethod = null;
        loadClassCb = null;
    }

    private static boolean isWanted(String n) {
        if (n == null || n.length() < 4) return false;
        if (AdHooks.isAdSdkClass(n)) return true;
        String[] ps = Config.targetPrefixes;
        for (String p : ps) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    /* ---------------- ③ 后台线程 ---------------- */

    public static void bootstrap(final Context ctx) {
        appCtx = ctx;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PipixiaCtx.set(ctx);
                    Config.load(ctx);
                    safeMode = Config.safe;
                    configLoaded = true;
                    Log.i("配置: " + Config.dump());
                    Log.i("配置来源: " + Config.source);
                    if (MainHook.isMainProcess()) {
                        CrashLog.record("启动 v" + MainHook.VERSION, "配置 " + Config.dump()
                                + "\n来源 " + Config.source);
                    }
                    if (safeMode) Log.i("安全模式：只注入，不装任何规则");
                    loop();
                } catch (Throwable t) {
                    Log.e("后台初始化失败", t);
                }
            }
        }, "pxx-hook");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void loop() {
        int idle = 0;
        while (true) {
            try {
                if (classHookInstalled && Config.scanSeconds > 0
                        && System.currentTimeMillis() - scanStart > Config.scanSeconds * 1000L) {
                    stopScan();
                }
                Class<?> c = PENDING.poll();
                if (c != null) {
                    idle = 0;
                    if (!safeMode) processClass(c);
                    continue;
                }
                Thread.sleep(50);
                idle++;
                if (idle == 200) {          // 约 10 秒一次
                    idle = 0;
                    writeReport();
                    writeStatus();
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void writeReport() {
        Context ctx = appCtx;
        if (ctx == null || REPORT.isEmpty()) return;
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "pipixia_report.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("# 皮皮虾净化 · 作者：半城\n");
            sb.append("# 已命中的规则（类#方法#参数）。版本更新后失效，把这份发出来就能补规则\n");
            synchronized (REPORT) {
                for (String s : REPORT) sb.append(s).append('\n');
            }
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try { w.write(sb.toString()); } finally { w.close(); }
            writeClasses(dir);
        } catch (Throwable ignored) {}
    }

    /**
     * 状态文件：回答「到底有没有效果」。
     * 只在**主进程**、且内容真的变了的时候才写——不然 4 个进程每 10 秒互相覆盖一次，
     * 文件时间戳一直在跳，看着像每次运行都在刷。
     */
    private static void writeStatus() {
        if (!Config.logs || !Config.statusFile) return;
        if (!MainHook.isMainProcess()) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# 皮皮虾净化 v").append(MainHook.VERSION).append(" 运行状态\n");
            sb.append("进程 pid=").append(android.os.Process.myPid())
              .append("  已注入=").append(configLoaded && !safeMode).append('\n');
            sb.append("配置 ").append(Config.dump()).append('\n');
            sb.append("计数 ").append(Stats.dump()).append('\n');
            sb.append("钩子自检见 pipixia_hooks.txt\n");
            if (Config.dumpClasses) {
                sb.append("扫描类数 ").append(SEEN.size()).append('\n');
            }
            synchronized (REPORT) {
                int i = 0;
                for (String s : REPORT) {
                    if (i++ == 0) sb.append("\n最近命中的规则：\n");
                    if (i > 40) break;
                    sb.append("  ").append(s).append('\n');
                }
            }
            String text = sb.toString();
            if (text.equals(lastStatusText)) return;
            lastStatusText = text;
            CrashLog.write(CrashLog.STATUS_FILE, text);
        } catch (Throwable ignored) {}
    }

    /** 把扫到的目标包类名导出来，版本更新后手工补 force/skip 用 */
    private static void writeClasses(File dir) {
        if (!Config.logs || !Config.dumpClasses || CLASSES.isEmpty() || dumped) return;
        dumped = true;
        try {
            File f = new File(dir, "pipixia_classes.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("# 皮皮虾净化 · 扫到的类（作者：半城）\n");
            synchronized (CLASSES) {
                for (String s : CLASSES) sb.append(s).append('\n');
            }
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try { w.write(sb.toString()); } finally { w.close(); }
            Log.i("类名已导出 " + f.getAbsolutePath() + " 共 " + CLASSES.size() + " 个");
        } catch (Throwable t) {
            Log.d("导出类名失败 " + t);
        }
    }

    /* ---------------- 扫描与挂钩 ---------------- */

    private static void processClass(Class<?> c) {
        String name;
        try { name = c.getName(); } catch (Throwable t) { return; }
        if (!SEEN.add(name)) return;
        try {
            if (AdHooks.isAdSdkClass(name)) {
                if (Config.hookAdSdk) AdHooks.processSdkClass(c);
                return;
            }
            if (isTargetClass(name)) processTargetClass(c);
        } catch (Throwable t) {
            Log.d("处理类失败 " + name + " : " + t);
        }
    }

    static boolean isTargetClass(String name) {
        String[] ps = Config.targetPrefixes;
        for (String p : ps) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    private static final String AD_WORDS[] = {"ad", "ads", "adv", "advert", "advertise", "gdt", "pangle", "csj"};

    private static void processTargetClass(Class<?> c) {
        String cname;
        try { cname = c.getName(); } catch (Throwable t) { return; }
        List<String> ct = tokens(simpleName(cname));
        if (Config.dumpClasses) CLASSES.add(cname);

        if (Config.patchAdModel && (contains(ct, "adinfo") || contains(ct, "admodel")
                || contains(ct, "adconfig") || contains(ct, "adslot") || contains(ct, "addata"))) {
            patchAdModelClass(c, cname);
        }

        if (c.isInterface()) return;
        Method[] ms;
        try { ms = c.getDeclaredMethods(); } catch (Throwable t) { return; }
        for (Method m : ms) {
            try { handleMethod(c, ct, m); } catch (Throwable ignored) {}
        }
    }

    private static void handleMethod(Class<?> c, List<String> ct, Method m) {
        int mod = m.getModifiers();
        if (Modifier.isAbstract(mod) || Modifier.isNative(mod) || m.isSynthetic()) return;

        final String cname = c.getName();
        final String mname = m.getName();
        if (Config.skip(cname, mname)) return;

        final String key = sig(c, m);
        if (isHooked(key)) return;

        final List<String> tk = tokens(mname);
        final Class<?> rt = m.getReturnType();
        final int argc = m.getParameterTypes().length;
        final boolean isBool = (rt == boolean.class || rt == Boolean.class);

        /* 0) 配置强制规则 */
        String forced = Config.forceValue(cname, mname);
        if (forced != null) {
            if ("null".equalsIgnoreCase(forced)) { hookValue(key, m, null); return; }
            if ("true".equalsIgnoreCase(forced)) { hookValue(key, m, Boolean.TRUE); return; }
            if ("false".equalsIgnoreCase(forced)) { hookValue(key, m, Boolean.FALSE); return; }
            if (rt == String.class) { hookValue(key, m, forced); return; }
            try {
                if (rt == int.class || rt == Integer.class) hookValue(key, m, Integer.valueOf(Integer.parseInt(forced)));
                else if (rt == long.class || rt == Long.class) hookValue(key, m, Long.valueOf(Long.parseLong(forced)));
                else if (isBool) hookValue(key, m, Boolean.valueOf(!"0".equals(forced)));
            } catch (Throwable ignored) {}
            return;
        }

        /* 0.5) 客户端画水印的方法，直接掐 */
        if (Config.hookWmMethod) {
            boolean wm = contains(tk, "watermark") || contains(tk, "水印")
                    || (contains(tk, "water") && contains(tk, "mark"))
                    || (contains(ct, "watermark") && (contains(tk, "draw") || contains(tk, "show")
                        || contains(tk, "add") || contains(tk, "render")));
            if (wm) {
                if (rt == void.class) { hookVoid(key, m); return; }
                hookValue(key, m, AdHooks.defaultOf(rt));
                return;
            }
        }

        /* 1) 皮皮虾自己的广告加载方法 */
        if (Config.hookAdLocal && AdHooks.looksLikeAdLoad(m)) {
            AdHooks.hookBlock(m);
            return;
        }

        /* 2) 开屏 */
        if (Config.hookSplash) {
            boolean splash = contains(tk, "splash") || contains(tk, "launch") || contains(tk, "boot")
                    || contains(tk, "welcome");
            boolean act = startsWith(tk, "load") || startsWith(tk, "show") || startsWith(tk, "request")
                    || startsWith(tk, "start") || startsWith(tk, "check") || startsWith(tk, "prepare");
            if (splash && act && AdHooks.hasCallbackParam(m)) {
                AdHooks.hookBlock(m);
                return;
            }
            boolean hasAd = contains(tk, "ad") || contains(tk, "adv") || contains(tk, "advert");
            if (splash && hasAd && startsWith(tk, "is") && isBool) {
                hookValue(key, m, Boolean.FALSE);
                return;
            }
        }

        /* 3) 弹窗 / 浮层 / 插屏 / 跳广告页 */
        if (Config.hookPopup) {
            boolean adName = contains(tk, "ad") || contains(tk, "adv") || contains(tk, "advert")
                    || contains(tk, "interstitial") || contains(tk, "insert");
            boolean showAct = startsWith(tk, "show") || startsWith(tk, "open")
                    || startsWith(tk, "jump") || startsWith(tk, "goto") || startsWith(tk, "enter");
            boolean adClass = contains(ct, "ad") || contains(ct, "advert") || contains(ct, "promote");
            if ((adName && showAct) || (adClass && (adName || contains(tk, "popup") || contains(tk, "dialog")))) {
                if (rt == void.class) { hookVoid(key, m); return; }
                if (isBool) { hookValue(key, m, Boolean.FALSE); return; }
            }
        }

        /* 4) 广告开关 */
        if (Config.hookAdSwitch && argc == 0 && isBool) {
            boolean adName = false;
            for (String w : AD_WORDS) if (contains(tk, w)) { adName = true; break; }
            boolean getter = startsWith(tk, "is") || startsWith(tk, "can") || startsWith(tk, "need")
                    || startsWith(tk, "has") || startsWith(tk, "should") || startsWith(tk, "enable");
            if (adName && getter) {
                boolean positive = contains(tk, "free") || contains(tk, "close")
                        || contains(tk, "noad") || contains(tk, "hide") || contains(tk, "disable");
                hookValue(key, m, Boolean.valueOf(!positive));
                return;
            }
        }

        /* 5) 广告视图 getter */
        if (Config.hookAdLocal && rt != null && rt.getName().equals("android.view.View")) {
            boolean adName = contains(tk, "ad") || contains(tk, "adv") || contains(tk, "advert")
                    || contains(tk, "express");
            if (startsWith(tk, "get") || startsWith(tk, "create")) {
                if (adName) { hookValue(key, m, null); return; }
            }
        }

        /* 6) 信息流广告条目 */
        if (Config.hookFeed && rt == List.class) {
            boolean feedish = contains(ct, "feed") || contains(ct, "aweme") || contains(ct, "item")
                    || contains(ct, "card") || contains(ct, "timeline") || contains(ct, "recommend")
                    || contains(ct, "discover") || contains(ct, "flow") || contains(ct, "list")
                    || contains(ct, "home") || contains(ct, "main") || contains(ct, "detail")
                    || contains(ct, "comment") || contains(ct, "search") || contains(ct, "rank");
            boolean adClass = contains(ct, "ad") || contains(ct, "advert");
            if (feedish && !adClass) {
                hookList(key, m);
                return;
            }
        }
    }

    /* ---------------- hook 工厂 ---------------- */

    private static void hookValue(final String key, Method m, final Object value) {
        if (!markHooked(key)) return;
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try { param.setResult(value); } catch (Throwable ignored) {}
                }
            });
            Log.d("改值 " + key + " => " + value);
        } catch (Throwable t) {
            Log.d("hook 失败 " + key + " : " + t);
        }
    }

    private static void hookVoid(final String key, Method m) {
        if (!markHooked(key)) return;
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            });
            Log.d("拦显示 " + key);
        } catch (Throwable t) {
            Log.d("hook 失败 " + key + " : " + t);
        }
    }

    @SuppressWarnings("rawtypes")
    private static void hookList(final String key, Method m) {
        if (!markHooked(key)) return;
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object r = param.getResult();
                        if (!(r instanceof List)) return;
                        List<?> l = (List<?>) r;
                        int n = FeedFilter.stripAds((List) l);
                        if (n > 0) {
                            Stats.adsRemoved.addAndGet(n);
                            Log.d("摘广告 " + n + " 条 " + key);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            Log.d("过滤列表 " + key);
        } catch (Throwable t) {
            Log.d("hook 失败 " + key + " : " + t);
        }
    }

    private static void patchAdModelClass(Class<?> c, String cname) {
        if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) return;
        final String key = cname + "#<init>ad";
        if (!markHooked(key)) return;
        try {
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try { FeedFilter.patchAdModel(param.thisObject); } catch (Throwable ignored) {}
                }
            });
            Log.d("刷模型 " + key);
        } catch (Throwable t) {
            Log.d("hook 构造失败 " + key + " : " + t);
        }
    }

    /* ---------------- 小工具 ---------------- */

    static String sig(Class<?> c, Method m) {
        return c.getName() + "#" + m.getName() + "#" + Arrays.toString(m.getParameterTypes());
    }

    static boolean markHooked(String key) {
        if (!HOOKED.add(key)) return false;
        Stats.hooksInstalled.incrementAndGet();
        REPORT.add(key);
        return true;
    }

    static boolean isHooked(String key) { return HOOKED.contains(key); }

    static String simpleName(String cn) {
        int i = cn.lastIndexOf('.');
        return i < 0 ? cn : cn.substring(i + 1);
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<String>(6);
        if (s == null) return out;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '_' || ch == '$' || ch == '-' || ch == ' ' || ch == '.') {
                if (cur.length() > 0) { out.add(cur.toString().toLowerCase(Locale.ROOT)); cur.setLength(0); }
                continue;
            }
            if (Character.isUpperCase(ch) && cur.length() > 0) {
                out.add(cur.toString().toLowerCase(Locale.ROOT));
                cur.setLength(0);
            }
            cur.append(ch);
        }
        if (cur.length() > 0) out.add(cur.toString().toLowerCase(Locale.ROOT));
        return out;
    }

    static boolean contains(List<String> tk, String w) {
        for (int i = 0, n = tk.size(); i < n; i++) if (w.equals(tk.get(i))) return true;
        return false;
    }

    static boolean startsWith(List<String> tk, String w) {
        return tk.size() > 0 && w.equals(tk.get(0));
    }
}
