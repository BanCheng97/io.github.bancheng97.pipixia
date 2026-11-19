package com.halfcity.pipixia;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 皮皮虾自研广告（m_ad）的处理。
 *
 * 侦察结果（v3.5 实测）：皮皮虾 6.6.1 的广告不在穿山甲公开 SDK 里，全在自家包下：
 *   com.sup.android.superb.m_ad.bean.InStreamAdResponse#setInStreamAdList(List)   ← 信息流广告
 *   com.sup.android.superb.m_ad.bean.CommentAdResponseData#setCommentAdList(List) ← 评论区广告
 *   com.sup.android.superb.m_ad.multi_splash_ads.*                                ← 开屏（多策略）
 *   com.sup.android.mi.feed.repo.bean.ad.*                                        ← 广告卡 bean
 *
 * 打法：
 *   1. 两个 set 直接塞空 ArrayList —— 服务端下发的广告列表在进内存那一刻就没了；
 *   2. m_ad 包里带 Listener 的 load/show/request 一律判失败并回调 onNoAd；
 *   3. 广告 bean 的 getAdInfo/getBannerData 置空、isXxxAd 打 false；
 *   4. 开屏策略类（multi_splash_ads）所有 boolean 判断打 false。
 */
final class SelfAds {

    private static final String[] PKG_PREFIXES = {
            "com.sup.android.superb.m_ad",
            "com.sup.android.mi.feed.repo.bean.ad",
            "com.sup.android.base.ad",
    };

    static volatile int scanned;
    static volatile int hooked;

    private SelfAds() {}

    static void install(ClassLoader cl) {
        if (!Config.hookSelfAds) {
            Log.d("自研广告拦截已按配置关闭");
            return;
        }
        emptyList(cl, "com.sup.android.superb.m_ad.bean.InStreamAdResponse", "setInStreamAdList");
        emptyList(cl, "com.sup.android.superb.m_ad.bean.CommentAdResponseData", "setCommentAdList");
        emptyList(cl, "com.sup.android.superb.m_ad.bean.CommentAdResponseData", "setCommentAdListV2");
        scanAsync(cl);
    }

    /** 把 setXxxAdList(List) 的参数换成空列表 */
    private static void emptyList(ClassLoader cl, String cls, String method) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(cls, cl);
            if (c == null) {
                Log.d("自研广告: 没找到 " + cls);
                return;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!method.equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || !java.util.List.class.isAssignableFrom(ps[0])) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!Engine.isEnabled()) return;
                            if (param.args != null && param.args.length == 1) {
                                param.args[0] = new ArrayList<Object>();
                                Stats.adBlocked.incrementAndGet();
                                Log.d("清空广告列表 " + method);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                Log.i("自研广告: 已清空 " + cls + "#" + method);
            }
        } catch (Throwable t) {
            Log.d("清空广告列表失败 " + t);
        }
    }

    /** dex 扫 m_ad / bean.ad 包，按方法特征挂 */
    private static void scanAsync(final ClassLoader cl) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 150 && !Engine.isEnabled(); i++) Thread.sleep(100);
                    List<String> all = DexClasses.all(cl);
                    int cls = 0, hook = 0;
                    for (String name : all) {
                        boolean in = false;
                        for (String p : PKG_PREFIXES) {
                            if (name.startsWith(p)) { in = true; break; }
                        }
                        if (!in) continue;
                        if (name.contains("$$Lambda") || name.contains("$1")) continue;
                        Class<?> c;
                        try { c = Class.forName(name, false, cl); } catch (Throwable e) { continue; }
                        cls++;
                        Method[] ms;
                        try { ms = c.getDeclaredMethods(); } catch (Throwable e) { continue; }
                        for (Method m : ms) {
                            try {
                                if (Modifier.isAbstract(m.getModifiers()) || m.isSynthetic()) continue;
                                if (handle(c, m)) hook++;
                            } catch (Throwable ignored) {}
                        }
                    }
                    scanned = cls;
                    hooked = hook;
                    Log.i("自研广告扫描: " + cls + " 个类, 挂 " + hook + " 个方法");
                } catch (Throwable t) {
                    Log.d("自研广告扫描失败 " + t);
                }
            }
        }, "pxx-selfads");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static boolean handle(Class<?> c, Method m) {
        String key = Engine.sig(c, m);
        if (Engine.isHooked(key)) return false;
        List<String> tk = Engine.tokens(m.getName());
        Class<?> rt = m.getReturnType();

        // 1) 拉广告（带 Listener）
        if ((Engine.startsWith(tk, "load") || Engine.startsWith(tk, "request")
                || Engine.startsWith(tk, "preload") || Engine.startsWith(tk, "show")
                || Engine.startsWith(tk, "fetch")) && AdHooks.hasCallbackParam(m)) {
            AdHooks.hookBlock(m);
            return true;
        }

        // 2) 广告数据 getter 置空
        if (m.getParameterTypes().length == 0
                && ("getAdInfo".equals(m.getName()) || "getBannerData".equals(m.getName())
                    || "getAdCell".equals(m.getName()) || "getAdData".equals(m.getName()))) {
            if (Engine.markHooked(key)) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (Engine.isEnabled()) param.setResult(null);
                        } catch (Throwable ignored) {}
                    }
                });
                Stats.adBlocked.incrementAndGet();
                return true;
            }
        }

        // 3) 广告开关布尔
        if ((rt == boolean.class || rt == Boolean.class)
                && (Engine.startsWith(tk, "is") || Engine.startsWith(tk, "has")
                    || Engine.startsWith(tk, "can") || Engine.startsWith(tk, "need"))) {
            boolean adName = Engine.contains(tk, "ad") || Engine.contains(tk, "ads")
                    || Engine.contains(tk, "splash") || Engine.contains(tk, "interstitial");
            if (adName && Engine.markHooked(key)) {
                final boolean value = false;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (Engine.isEnabled()) param.setResult(Boolean.valueOf(value));
                        } catch (Throwable ignored) {}
                    }
                });
                Stats.adBlocked.incrementAndGet();
                return true;
            }
        }
        return false;
    }
}
