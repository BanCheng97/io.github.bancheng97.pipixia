package com.halfcity.pipixia;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 广告层：把「拉广告」这个动作从根上掐掉。
 *
 * 掐的不是 UI 而是请求本身：load/request/preload/show 一进就返回“失败 + 回调 onNoAd”。
 * 好处是这条广告没有下载、没有曝光、没有播放器，也不会留下“广告加载中”的转圈；
 * 坏处是原本放广告的槽位会空一块（空总比广告好，见 README「留白」一节）。
 */
public final class AdHooks {

    /** 皮皮虾用的广告 SDK：穿山甲、融合 SDK、优量汇、快手、百度、Mintegral */
    private static final String[] SDK_PREFIXES = {
            "com.bytedance.sdk.openadsdk",   // 穿山甲
            "com.bytedance.msdk",            // 穿山甲融合/聚合
            "com.pangle",                    // 老包名
            "com.qq.e",                      // 优量汇
            "com.kwad",                      // 快手联盟
            "com.baidu.mobads",              // 百青藤
            "com.mbridge",                   // Mintegral
    };

    private static final String[] FAIL_NAMES = {
            "onNoAd", "onError", "onAdFailed", "onAdFailedToLoad", "onLoadFail", "onLoadError",
            "onFailed", "onAdError", "onRenderFail", "onVideoError", "onRewardFail",
            "onAdFailedToRender", "onSplashAdLoadFail", "onNativeAdLoadFail",
    };

    private AdHooks() {}

    static boolean isAdSdkClass(String name) {
        if (name == null) return false;
        for (String p : SDK_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    /** 扫广告 SDK 的类，挂掉所有“拉广告 / 显示广告”的方法 */
    static void processSdkClass(Class<?> c) {
        if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) return;
        Method[] ms;
        try { ms = c.getDeclaredMethods(); } catch (Throwable t) { return; }
        for (Method m : ms) {
            try {
                int mod = m.getModifiers();
                if (Modifier.isAbstract(mod) || Modifier.isNative(mod) || m.isSynthetic()) continue;
                List<String> tk = Engine.tokens(m.getName());
                boolean loadish = Engine.startsWith(tk, "load") || Engine.startsWith(tk, "request")
                        || Engine.startsWith(tk, "preload") || Engine.startsWith(tk, "fetch")
                        || Engine.startsWith(tk, "start");
                boolean showish = Engine.startsWith(tk, "show") || Engine.startsWith(tk, "play")
                        || Engine.startsWith(tk, "render");
                if (!loadish && !showish) continue;
                if (!hasCallbackParam(m)) continue;
                hookBlock(m);
            } catch (Throwable ignored) {}
        }
    }

    /** 皮皮虾自己的广告包装方法：loadRewardAd(slot, listener) / showSplashAd(cb) 这种长相 */
    static boolean looksLikeAdLoad(Method m) {
        List<String> tk = Engine.tokens(m.getName());
        boolean act = Engine.startsWith(tk, "load") || Engine.startsWith(tk, "show")
                || Engine.startsWith(tk, "request") || Engine.startsWith(tk, "preload")
                || Engine.startsWith(tk, "fetch");
        if (!act) return false;
        boolean ad = Engine.contains(tk, "ad") || Engine.contains(tk, "ads")
                || Engine.contains(tk, "adv") || Engine.contains(tk, "advert")
                || Engine.contains(tk, "advertise") || Engine.contains(tk, "gdt")
                || Engine.contains(tk, "pangle") || Engine.contains(tk, "csj");
        return ad && hasCallbackParam(m);
    }

    static void hookBlock(Method m) {
        final String key = Engine.sig(m.getDeclaringClass(), m);
        if (!Engine.markHooked(key)) return;
        Stats.adBlocked.incrementAndGet();
        final Class<?> rt = m.getReturnType();
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (Config.hookAdNotify) notifyFail(param.args);
                    } catch (Throwable ignored) {}
                    try { param.setResult(defaultOf(rt)); } catch (Throwable ignored) {}
                    Log.d("拦广告 " + key);
                }
            });
        } catch (Throwable t) {
            Log.d("拦广告失败 " + key + " : " + t);
        }
    }

    static boolean hasCallbackParam(Method m) {
        Class<?>[] ps;
        try { ps = m.getParameterTypes(); } catch (Throwable t) { return false; }
        for (Class<?> p : ps) {
            if (p == null) continue;
            if (p.isInterface()) {
                String n = p.getName();
                if (n.contains("Listener") || n.contains("Callback") || n.contains("Observer")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 主动把“无广告”事件回给回调，App 才不会卡在 loading */
    static void notifyFail(Object[] args) {
        if (args == null) return;
        for (Object a : args) {
            if (a == null) continue;
            Class<?> ac = a.getClass();
            String an;
            try { an = ac.getName(); } catch (Throwable t) { continue; }
            boolean looksCb = ac.isInterface() || an.contains("Listener") || an.contains("Callback");
            if (!looksCb) continue;
            invokeFail(a, ac);
        }
    }

    private static void invokeFail(Object target, Class<?> ac) {
        Method[] ms;
        try { ms = ac.getMethods(); } catch (Throwable t) { return; }
        for (Method m : ms) {
            String n = m.getName();
            boolean hit = false;
            for (String f : FAIL_NAMES) {
                if (f.equals(n)) { hit = true; break; }
            }
            if (!hit) continue;
            Class<?>[] pt = m.getParameterTypes();
            try {
                m.setAccessible(true);
                if (pt.length == 2 && pt[0] == int.class && pt[1] == String.class) {
                    m.invoke(target, Integer.valueOf(1004), "no ad");
                } else if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                    m.invoke(target, Integer.valueOf(1004), Integer.valueOf(0));
                } else if (pt.length == 1 && pt[0] == int.class) {
                    m.invoke(target, Integer.valueOf(1004));
                } else if (pt.length == 1 && pt[0] == String.class) {
                    m.invoke(target, "no ad");
                } else if (pt.length == 0) {
                    m.invoke(target);
                } else {
                    continue;
                }
                Log.d("回调失败 " + ac.getName() + "." + n);
            } catch (Throwable ignored) {}
        }
    }

    static Object defaultOf(Class<?> rt) {
        if (rt == null || rt == void.class) return null;
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == int.class) return Integer.valueOf(0);
        if (rt == long.class) return Long.valueOf(0L);
        if (rt == short.class) return Short.valueOf((short) 0);
        if (rt == byte.class) return Byte.valueOf((byte) 0);
        if (rt == float.class) return Float.valueOf(0f);
        if (rt == double.class) return Double.valueOf(0d);
        if (rt == char.class) return Character.valueOf((char) 0);
        return null;
    }
}
