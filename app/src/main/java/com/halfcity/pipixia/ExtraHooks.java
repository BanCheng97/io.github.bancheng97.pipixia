package com.halfcity.pipixia;


import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 扩展功能（3.0）。全部按「真名 + dex 特征」挂钩，不用瞎猜：
 *
 *   1. 去红点：FeedTabFragmentV2 的 tab 红点参数打 0、MainActivity#handleBadgeAndPopup(0)、
 *      我的页头像栏 badge 打 false；
 *   2. 禁更新：UpdateService#checkUpdateByAutomatic 直接返回；
 *   3. 播放倍速：TTVideoEnginePlayer#setPlaySpeed，正常速按配置提速（默认 1.0 不动）；
 *   4. 信息流净化：dex 找 com.sup.android.module.feed.repo.manager 下
 *      参数为 (String, FeedResponse, boolean, int) 的方法，删带货 / 直播 / 官方号 / 屏蔽词；
 *   5. 图片保存原图：NewGalleryActivity#onDownload 把地址换成 pstatp 原图 CDN 再下载。
 */
final class ExtraHooks {

    private ExtraHooks() {}

    static void install(ClassLoader cl) {
        redDot(cl);
        noUpdate(cl);
        playSpeed(cl);
        imageSave(cl);
        feedCleanAsync(cl);
    }

    /* ---------------- 1. 去红点 ---------------- */

    private static void redDot(ClassLoader cl) {
        try {
            Class<?> tab = XposedHelpers.findClassIfExists(
                    "com.sup.superb.feedui.view.tabv2.FeedTabFragmentV2", cl);
            if (tab != null) {
                for (Method m : tab.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length != 3) continue;
                    if (ps[0] != boolean.class || ps[1] != int.class || ps[2] != int.class) continue;
                    final int a0 = 0, a1 = 1;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Config.removeRedDot || !Engine.isEnabled()) return;
                                param.args[a0] = Boolean.FALSE;
                                param.args[a1] = Integer.valueOf(0);
                            } catch (Throwable ignored) {}
                        }
                    });
                    Log.d("去红点 tab " + m.getName());
                }
            }
            Class<?> main = XposedHelpers.findClassIfExists("com.sup.android.base.MainActivity", cl);
            if (main != null) {
                for (Method m : main.getDeclaredMethods()) {
                    if (!"handleBadgeAndPopup".equals(m.getName())) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 0) continue;
                    // 必须按真实参数类型塞值：塞错类型（比如给 int 槽塞 Long）会让 App 当场崩
                    final boolean p0Long = (ps[0] == long.class || ps[0] == Long.class);
                    final boolean p0Int = (ps[0] == int.class || ps[0] == Integer.class);
                    if (!p0Long && !p0Int) continue;
                    final Object zero = p0Long ? (Object) Long.valueOf(0L) : (Object) Integer.valueOf(0);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Config.removeRedDot || !Engine.isEnabled()) return;
                                if (param.args != null && param.args.length > 0) param.args[0] = zero;
                            } catch (Throwable ignored) {}
                        }
                    });
                    Log.d("去红点 MainActivity#" + m.getName() + "(" + ps[0].getName() + ")");
                }
            }
            Class<?> head = XposedHelpers.findClassIfExists(
                    "com.sup.android.m_mine.view.subview.MyProfileHeaderLayout", cl);
            if (head != null) {
                for (Method m : head.getDeclaredMethods()) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (!"a".equals(m.getName()) || ps.length != 1 || ps[0] != boolean.class) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Config.removeRedDot || !Engine.isEnabled()) return;
                                param.args[0] = Boolean.FALSE;
                            } catch (Throwable ignored) {}
                        }
                    });
                    Log.d("去红点 我的页");
                }
            }
        } catch (Throwable t) {
            Log.d("去红点失败 " + t);
        }
    }

    /* ---------------- 2. 禁更新 ---------------- */

    private static void noUpdate(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(
                    "com.sup.android.m_update.UpdateService", cl);
            if (c == null) return;
            for (Method m : c.getDeclaredMethods()) {
                if (!"checkUpdateByAutomatic".equals(m.getName())) continue;
                final Class<?> rt = m.getReturnType();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!Config.disableUpdate || !Engine.isEnabled()) return;
                            param.setResult(AdHooks.defaultOf(rt));
                        } catch (Throwable ignored) {}
                    }
                });
                Log.d("禁更新已挂 " + m.getName() + " rt=" + rt.getName());
            }
        } catch (Throwable t) {
            Log.d("禁更新失败 " + t);
        }
    }

    /* ---------------- 3. 播放倍速 ---------------- */

    private static void playSpeed(ClassLoader cl) {
        try {
            if (Config.playSpeed <= 1.0f) return;
            Class<?> c = XposedHelpers.findClassIfExists(
                    "com.sup.android.ttvideoplayer.TTVideoEnginePlayer", cl);
            if (c == null) return;
            for (Method m : c.getDeclaredMethods()) {
                if (!"setPlaySpeed".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || ps[0] != float.class) continue;
                final float speed = Config.playSpeed;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!Engine.isEnabled()) return;
                            if (param.args[0] instanceof Float
                                    && ((Float) param.args[0]).floatValue() == 1.0f) {
                                param.args[0] = Float.valueOf(speed);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                Log.d("倍速 " + speed + "X");
            }
        } catch (Throwable t) {
            Log.d("倍速失败 " + t);
        }
    }

    /* ---------------- 4. 信息流净化 ---------------- */

    private static void feedCleanAsync(final ClassLoader cl) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 100 && !Engine.isEnabled(); i++) Thread.sleep(100);
                    if (!Config.cleanFeed) return;
                    int n = 0;
                    for (String name : DexClasses.all(cl)) {
                        if (!name.startsWith("com.sup.android.module.feed.repo.manager")) continue;
                        Class<?> c;
                        try { c = Class.forName(name, false, cl); } catch (Throwable e) { continue; }
                        for (Method m : c.getDeclaredMethods()) {
                            Class<?>[] ps = m.getParameterTypes();
                            if (ps.length != 4) continue;
                            if (ps[0] != String.class || ps[2] != boolean.class || ps[3] != int.class) continue;
                            if (!ps[1].getName().endsWith("FeedResponse")) continue;
                            hookFeedResponse(m);
                            n++;
                        }
                    }
                    Log.d("信息流净化挂在 " + n + " 个方法上");
                } catch (Throwable t) {
                    Log.d("信息流净化失败 " + t);
                }
            }
        }, "pxx-feed");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void hookFeedResponse(Method m) {
        if (Engine.isHooked(Engine.sig(m.getDeclaringClass(), m))) return;
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!Config.cleanFeed || !Engine.isEnabled()) return;
                    if (param.args == null || param.args.length < 2) return;
                    Object feeds = CleanVideo.call(param.args[1], "getData", new Class<?>[0]);
                    if (!(feeds instanceof List)) return;
                    List<?> list = (List<?>) feeds;
                    int removed = 0;
                    for (int i = list.size() - 1; i >= 0; i--) {
                        Object cell = list.get(i);
                        if (cell == null) continue;
                        if (shouldRemove(cell)) {
                            try {
                                ((List) list).remove(i);
                                removed++;
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (removed > 0) {
                        Stats.feedCleaned.addAndGet(removed);
                        Log.d("净化信息流 " + removed + " 条");
                    }
                } catch (Throwable ignored) {}
            }
        });
        Log.d("净化 " + m.getDeclaringClass().getName() + "#" + m.getName());
    }

    private static boolean shouldRemove(Object cell) {
        String cn = cell.getClass().getName();
        // 直播卡
        if (Config.removeLive && cn.contains("LiveSaasFeedCell")) return true;

        Object item = CleanVideo.call(cell, "getFeedItem", new Class<?>[0]);
        if (item == null) item = cell;

        // 带货
        if (Config.removePromo) {
            if (CleanVideo.call(item, "getPromotionInfo", new Class<?>[0]) != null) return true;
            if (CleanVideo.call(cell, "getPromotionInfo", new Class<?>[0]) != null) return true;
        }

        // 官方号
        if (Config.removeOfficial) {
            Object user = CleanVideo.call(item, "getAuthor", new Class<?>[0]);
            if (user == null) user = CleanVideo.call(cell, "getAuthor", new Class<?>[0]);
            if (user != null) {
                Object cert = CleanVideo.call(user, "getCertifyInfo", new Class<?>[0]);
                Object desc = cert == null ? null : CleanVideo.call(cert, "getDescription", new Class<?>[0]);
                if (desc instanceof String) {
                    String d = (String) desc;
                    if (d.contains("官方账号") || d.contains("视频号") || d.contains("新媒体")) return true;
                }
            }
        }

        // 屏蔽词
        String[] kws = Config.blockKeywords;
        if (kws.length > 0) {
            Object content = CleanVideo.call(item, "getContent", new Class<?>[0]);
            if (content instanceof String) {
                String s = (String) content;
                for (String k : kws) {
                    if (k.length() > 0 && s.contains(k)) return true;
                }
            }
        }
        return false;
    }

    /* ---------------- 5. 图片保存原图 ---------------- */

    private static void imageSave(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(
                    "com.sup.android.m_gallery.NewGalleryActivity", cl);
            if (c == null) return;
            for (Method m : c.getDeclaredMethods()) {
                if (!"onDownload".equals(m.getName())) continue;
                final Class<?> rt = m.getReturnType();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!Config.saveImage || !Engine.isEnabled()) return;
                            Object act = param.thisObject;
                            Object vp = CleanVideo.call(act, "getVpGallery", new Class<?>[0]);
                            if (vp == null) return;
                            Object idxO = CleanVideo.call(vp, "getCurrentItem", new Class<?>[0]);
                            int idx = idxO instanceof Integer ? (Integer) idxO : 0;
                            Object images = CleanVideo.call(act, "getImages", new Class<?>[0]);
                            if (!(images instanceof List)) return;
                            List<?> imgs = (List<?>) images;
                            if (idx < 0 || idx >= imgs.size()) return;
                            Object cur = imgs.get(idx);
                            String uri = (String) CleanVideo.call(cur, "getUri", new Class<?>[0]);
                            Object urlList = CleanVideo.call(cur, "getUrlList", new Class<?>[0]);
                            if (urlList instanceof List && ((List) urlList).size() > 0 && uri != null) {
                                String orig = "https://sf1-ttcdn-tos.pstatp.com/obj/" + uri;
                                Method setUrl = null;
                                for (Method sm : ((List<?>) urlList).get(0).getClass().getMethods()) {
                                    if (sm.getName().equals("setUrl")
                                            && sm.getParameterTypes().length == 1
                                            && sm.getParameterTypes()[0] == String.class) {
                                        setUrl = sm;
                                        break;
                                    }
                                }
                                if (setUrl != null) {
                                    setUrl.invoke(((List<?>) urlList).get(0), orig);
                                    Log.d("图片走原图 " + uri);
                                }
                            }
                            Object isGif = CleanVideo.call(cur, "isGif", new Class<?>[0]);
                            CleanVideo.call(act, "downloadImage",
                                    new Class<?>[]{List.class, boolean.class}, urlList, isGif);
                            param.setResult(AdHooks.defaultOf(rt));
                        } catch (Throwable ignored) {}
                    }
                });
                Log.d("图片原图已挂");
            }
        } catch (Throwable t) {
            Log.d("图片原图失败 " + t);
        }
    }
}
