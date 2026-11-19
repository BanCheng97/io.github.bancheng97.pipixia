package com.halfcity.pipixia;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 类名侦察（3.5）。只做事、不做决定：
 * 把「我们想 hook 的那些方法」在 dex 里到底长什么样，写进 pipixia_hooks.txt。
 *
 * 手段：
 *   1. 通过内部类反推外层类（`VideoDownloadHelper$xxx` 能反查出外层真名，即使外层加载失败）；
 *   2. 按类名收窄（video / download / comment / splash / banner），最多加载 600 个类，
 *      `Class.forName(..., false, cl)` 不跑静态初始化，只读方法表；
 *   3. 找这些方法名：doDownload / download / getUrlList / getSendGodStatus / getAdInfo /
 *      getBannerData / setPlaySpeed / getPromotionInfo / handleBadgeAndPopup。
 *
 * 输出直接给下一次改代码用，不用再猜。
 */
final class Discover {

    private static final String[] WANT = {
            "doDownload", "download", "downloadImage",
            "getUrlList", "getSendGodStatus", "getAdInfo", "getBannerData",
            "setPlaySpeed", "getPromotionInfo", "handleBadgeAndPopup", "diggGodComment",
    };

    private static final int MAX_CLASSES = 600;

    static volatile int lastTotal;

    private Discover() {}

    static String run(ClassLoader cl) {
        StringBuilder out = new StringBuilder();
        try {
            List<String> all = DexClasses.all(cl);
            lastTotal = all.size();
            out.append("dex 总类数 ").append(all.size()).append('\n');
            if (all.isEmpty()) return out.toString();

            // 1) 内部类反推外层
            for (String n : all) {
                if (!n.contains("VideoDownloadHelper")) continue;
                out.append("同族类 ").append(n).append('\n');
                try {
                    Class<?> c = Class.forName(n, false, cl);
                    Class<?> enc = c.getEnclosingClass();
                    if (enc != null) {
                        out.append("  外层类 ").append(enc.getName()).append('\n');
                        for (Method m : enc.getDeclaredMethods()) {
                            if (m.getName().toLowerCase().contains("download")) {
                                out.append("  外层方法 ").append(sig(m)).append('\n');
                            }
                        }
                    }
                } catch (Throwable t) {
                    out.append("  加载失败 ").append(t).append('\n');
                }
            }

            // 2) 收窄候选类
            List<String> cand = new ArrayList<String>();
            for (String n : all) {
                if (!n.startsWith("com.sup.") && !n.startsWith("com.ss.ttvideoengine")) continue;
                String low = n.toLowerCase();
                if (low.contains("video") || low.contains("download") || low.contains("splash")
                        || low.contains("banner") || low.contains("god") || low.contains("comment")
                        || low.contains("adfeed")) {
                    cand.add(n);
                    if (cand.size() >= MAX_CLASSES) break;
                }
            }
            out.append("候选类 ").append(cand.size()).append('\n');

            // 3) 方法扫描
            int loaded = 0;
            for (String n : cand) {
                Class<?> c;
                try {
                    c = Class.forName(n, false, cl);
                } catch (Throwable t) {
                    continue;
                }
                loaded++;
                Method[] ms;
                try { ms = c.getDeclaredMethods(); } catch (Throwable t) { continue; }
                for (Method m : ms) {
                    String mn = m.getName();
                    boolean hit = false;
                    for (String w : WANT) {
                        if (w.equals(mn)) { hit = true; break; }
                    }
                    if (!hit) continue;
                    out.append("命中 ").append(c.getName()).append('#').append(sig(m)).append('\n');
                    if (out.length() > 11000) {
                        out.append("…（已截断）\n");
                        return out.toString();
                    }
                }
            }
            out.append("实际加载 ").append(loaded).append(" 个类\n");

            // 4) 名字里带 splash 的类（开屏广告）
            int i = 0;
            for (String n : all) {
                if (i >= 15) break;
                String low = n.toLowerCase();
                if (low.contains("splash") && low.startsWith("com.sup")) {
                    out.append("splash 类 ").append(n).append('\n');
                    i++;
                }
            }
        } catch (Throwable t) {
            out.append("侦察失败 ").append(t).append('\n');
        }
        return out.toString();
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            String n = ps[i].getName();
            int dot = n.lastIndexOf('.');
            sb.append(dot > 0 ? n.substring(dot + 1) : n);
        }
        sb.append(") -> ").append(m.getReturnType().getName());
        return sb.toString();
    }
}
