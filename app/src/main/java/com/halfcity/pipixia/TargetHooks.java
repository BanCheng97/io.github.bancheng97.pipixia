package com.halfcity.pipixia;

import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 真名挂钩。这些类名在皮皮虾里**没有混淆**（com.sup.android.* 是自家业务代码），
 * 所以不用瞎猜，直接按名字挂，命中率 100%，而且换版本也不太变。
 *
 * 覆盖四件事：
 *   1. 无水印下载：VideoDownloadHelper.doDownload(...) 一进来就把 VideoModel 的地址换成干净链；
 *   2. 无水印播放：VideoModel.getUrlList() 出口回写；
 *   3. 信息流广告：AdFeedCell.getAdInfo() → null、BannerModel.getBannerData() → null；
 *   4. 开屏广告：dex 扫 com.sup.android.superb.m_ad.initializer 下带 CopyOnWriteArraySet 字段的类，
 *      把它的 b() 打成 false（和皮皮虾助手的做法一致）。
 */
final class TargetHooks {

    private static final StringBuilder REPORT = new StringBuilder();

    static volatile boolean videoModelFound;
    static volatile boolean downloadFound;
    static volatile int splashFound;
    private static ClassLoader loader;

    private TargetHooks() {}

    private static void note(String s) {
        REPORT.append(s).append('\n');
        Log.i("安装 " + s);
    }

    static void install(ClassLoader cl) {
        loader = cl;
        REPORT.setLength(0);
        autoDiscover(cl);
        hookDownload(cl);
        hookDownloadViaDex(cl);
        hookVideoModel(cl);
        hookAdBeans(cl);
        hookSplash(cl);
        hookWatermarkViews(cl);
        if (!Config.logs || !Config.discover) {
            Log.d("类名侦察已按配置关闭");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 150 && !Engine.isEnabled(); i++) Thread.sleep(100);
                    String d = Discover.run(loader);
                    CrashLog.write("pipixia_hooks.txt", REPORT.toString() + "\n==== 侦察 ====\n" + d);
                    Log.i("侦察完成 " + d.length() + " 字");
                    Adapt.report(PipixiaCtx.get());
                } catch (Throwable t) {
                    Log.d("侦察失败 " + t);
                }
            }
        }, "pxx-discover").start();
    }

    /** dex 兜底发现：类名换了也能把候选项列出来，方便下一版钉死挂钩点 */
    private static void autoDiscover(ClassLoader cl) {
        try {
            List<String> all = DexClasses.all(cl);
            if (all.isEmpty()) {
                note("dex 枚举失败");
                return;
            }
            note("dex 总类数 " + all.size());
            List<String> dl = new ArrayList<String>();
            List<String> vm = new ArrayList<String>();
            List<String> ad = new ArrayList<String>();
            for (String n : all) {
                if (n.contains("VideoDownloadHelper") && dl.size() < 10) dl.add(n);
                else if (n.endsWith("VideoModel") && vm.size() < 10) vm.add(n);
                else if (n.startsWith("com.sup.android.m_ad") && ad.size() < 20) ad.add(n);
            }
            note("含 VideoDownloadHelper 的类: " + dl);
            note("叫 *VideoModel 的类: " + vm);
            note("m_ad 包下的类(前20): " + ad);
        } catch (Throwable t) {
            note("dex 发现失败 " + t);
        }
    }

    /* ---------------- 1. 下载入口 ---------------- */

    private static void hookDownload(ClassLoader cl) {
        try {
            Class<?> helper = XposedHelpers.findClassIfExists(
                    "com.sup.android.video.VideoDownloadHelper", cl);
            if (helper == null) {
                note("VideoDownloadHelper: 没找到");
                return;
            }
            downloadFound = true;
            note("VideoDownloadHelper: 找到");
            int hit = 0;
            for (Method m : helper.getDeclaredMethods()) {
                String n = m.getName();
                if ("isEnableDownloadGodVideo".equals(n) || "isEnableDownloadWatermark".equals(n)) {
                    XposedBridge.hookMethod(m, retFalse());
                    hit++;
                    continue;
                }
                if (!"doDownload".equals(n)) continue;
                Class<?>[] ps = m.getParameterTypes();
                int vidx = -1, cidx = -1;
                for (int i = 0; i < ps.length; i++) {
                    String pn = ps[i].getName();
                    if (pn.endsWith("VideoModel")) vidx = i;
                    if (pn.endsWith("VideoDownLoadConfig")) cidx = i;
                }
                if (vidx < 0) continue;
                hookDoDownload(m, vidx, cidx);
                hit++;
            }
            note("VideoDownloadHelper: 挂了 " + hit + " 个方法");
        } catch (Throwable t) {
            Log.e("挂下载入口失败", t);
        }
    }

    /** VideoDownloadHelper 外层加载失败时，靠内部类反推外层再挂 doDownload */
    private static void hookDownloadViaDex(final ClassLoader cl) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 100 && !Engine.isEnabled(); i++) Thread.sleep(100);
                    List<String> all = DexClasses.all(cl);
                    for (String n : all) {
                        if (!n.contains("VideoDownloadHelper$")) continue;
                        try {
                            Class<?> inner = Class.forName(n, false, cl);
                            Class<?> outer = inner.getEnclosingClass();
                            if (outer == null) continue;
                            int hit = 0;
                            for (Method m : outer.getDeclaredMethods()) {
                                if (!"doDownload".equals(m.getName())) continue;
                                Class<?>[] ps = m.getParameterTypes();
                                int vidx = -1, cidx = -1;
                                for (int i = 0; i < ps.length; i++) {
                                    String pn = ps[i].getName();
                                    if (pn.endsWith("VideoModel")) vidx = i;
                                    if (pn.endsWith("VideoDownLoadConfig")) cidx = i;
                                }
                                if (vidx < 0) continue;
                                hookDoDownload(m, vidx, cidx);
                                hit++;
                            }
                            downloadFound = true;
                            note("VideoDownloadHelper(外层反推): " + outer.getName() + " 挂 " + hit);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    note("反推下载类失败 " + t);
                }
            }
        }, "pxx-dl");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void hookDoDownload(final Method m, final int vidx, final int cidx) {
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!Config.cleanVideo || !Engine.isEnabled()) return;
                        if (param.args == null || param.args.length <= vidx) return;
                        Object vm = param.args[vidx];
                        if (vm == null) return;
                        String uri = (String) CleanVideo.call(vm, "getUri", new Class<?>[0]);
                        long itemId = 0L;
                        if (cidx >= 0 && param.args.length > cidx && param.args[cidx] != null) {
                            Object o = CleanVideo.call(param.args[cidx], "getItemId", new Class<?>[0]);
                            if (o instanceof Long) itemId = ((Long) o).longValue();
                            else if (o instanceof Integer) itemId = ((Integer) o).longValue();
                        }
                        String clean = CleanVideo.cached(uri);
                        if (clean == null) {
                            // 主线程上绝不联网，丢后台；下载线程才同步解析
                            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                                CleanVideo.resolveAsync(uri, itemId);
                            } else {
                                clean = CleanVideo.resolve(uri, itemId);
                            }
                        }
                        if (clean != null) {
                            Object list = CleanVideo.call(vm, "getUrlList", new Class<?>[0]);
                            CleanVideo.patchList(list, clean);
                            Log.d("下载走无水印 " + uri);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            Log.d("挂 doDownload 失败 " + t);
        }
    }

    /* ---------------- 2. 播放路径 ---------------- */

    private static void hookVideoModel(ClassLoader cl) {
        try {
            Class<?> vm = XposedHelpers.findClassIfExists(
                    "com.sup.android.base.model.VideoModel", cl);
            if (vm == null) {
                note("VideoModel: 没找到");
                return;
            }
            videoModelFound = true;
            note("VideoModel: 找到");
            for (Method m : vm.getDeclaredMethods()) {
                if (!"getUrlList".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 0) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!Config.cleanVideo || !Engine.isEnabled()) return;
                            // 只改这次返回的 List，绝不回头再调 getUrlList（会无限递归）
                            String uri = (String) CleanVideo.call(param.thisObject, "getUri", new Class<?>[0]);
                            String clean = CleanVideo.cached(uri);
                            if (clean == null) {
                                CleanVideo.resolveAsync(uri, 0L);
                                return;
                            }
                            CleanVideo.patchList(param.getResult(), clean);
                        } catch (Throwable ignored) {}
                    }
                });
                note("VideoModel: getUrlList 已挂");
            }
        } catch (Throwable t) {
            Log.d("挂 VideoModel 失败 " + t);
        }
    }

    /* ---------------- 3. 广告 bean ---------------- */

    private static void hookAdBeans(ClassLoader cl) {
        nullOut(cl, "com.sup.android.mi.feed.repo.bean.ad.AdFeedCell", "getAdInfo");
        nullOut(cl, "com.sup.android.base.model.BannerModel", "getBannerData");
        nullOut(cl, "com.sup.android.mi.feed.repo.bean.ad.AdFeedCell", "getPromotionInfo");
    }

    private static void nullOut(ClassLoader cl, String cls, String method) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(cls, cl);
            if (c == null) {
                note(cls + "#" + method + ": 没找到");
                return;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!method.equals(m.getName())) continue;
                if (m.getParameterTypes().length != 0) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!Engine.isEnabled()) return;
                            param.setResult(null);
                        } catch (Throwable ignored) {}
                    }
                });
                note(cls + "#" + method + ": 已置空");
            }
        } catch (Throwable ignored) {}
    }

    /* ---------------- 4. 开屏 ---------------- */

    private static void hookSplash(final ClassLoader cl) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 100 && !Engine.isEnabled(); i++) {
                        Thread.sleep(100);
                    }
                    if (!Config.dexScan) return;
                    hookSplashNow(cl);
                } catch (Throwable t) {
                    Log.d("开屏扫描线程出错 " + t);
                }
            }
        }, "pxx-splash");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void hookSplashNow(ClassLoader cl) {
        try {
            List<String> names = DexClasses.all(cl);
            if (names.isEmpty()) {
                Log.d("dex 列表为空，跳过开屏扫描");
                return;
            }
            int found = 0;
            for (String name : names) {
                if (!name.startsWith("com.sup.android.superb.m_ad.initializer")) continue;
                Class<?> c;
                try { c = Class.forName(name, false, cl); } catch (Throwable t) { continue; }
                boolean hasCowa = false;
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().getName().equals("java.util.concurrent.CopyOnWriteArraySet")) {
                        hasCowa = true;
                        break;
                    }
                }
                if (!hasCowa) continue;
                hookBoolB(c);
                found++;
            }
            splashFound = found;
            note("开屏类: 命中 " + found + " 个（dex 共 " + names.size() + " 类）");
        } catch (Throwable t) {
            Log.d("开屏扫描失败 " + t);
        }
    }

    private static void hookBoolB(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (!"b".equals(m.getName())) continue;
            Class<?> rt = m.getReturnType();
            if (rt != boolean.class && rt != Boolean.class) continue;
            if (Modifier.isAbstract(m.getModifiers())) continue;
            if (Engine.isHooked(Engine.sig(c, m))) continue;
            try {
                XposedBridge.hookMethod(m, retFalse());
                Log.d("开屏 " + c.getName() + "#b 打 false");
            } catch (Throwable ignored) {}
        }
    }

    private static XC_MethodHook retFalse() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!Engine.isEnabled()) return;
                    param.setResult(Boolean.FALSE);
                } catch (Throwable ignored) {}
            }
        };
    }

    /* ---------------- 5. 浮层水印 View ---------------- */

    private static void hookWatermarkViews(ClassLoader cl) {
        try {
            XC_MethodHook cb = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!Config.hideWmView || !Engine.isEnabled()) return;
                        if (param.args == null || param.args.length == 0) return;
                        if (!(param.args[0] instanceof View)) return;
                        final View v = (View) param.args[0];
                        String cn = v.getClass().getName();
                        if (!cn.contains("atermark") && !cn.contains("aterMark")
                                && !cn.contains("Watermark")) return;
                        v.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    v.setVisibility(View.GONE);
                                    Log.d("藏浮层水印 " + v.getClass().getName());
                                } catch (Throwable ignored) {}
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            };
            XposedBridge.hookAllMethods(android.view.ViewGroup.class, "addView", cb);
        } catch (Throwable t) {
            Log.d("挂水印 View 失败 " + t);
        }
    }
}
