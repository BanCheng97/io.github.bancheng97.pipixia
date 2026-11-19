package com.halfcity.pipixia;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 信息流广告过滤。
 *
 * 皮皮虾的信息流广告不是“一块广告位”，是列表里的一个 item（穿山甲原生/优量汇原生混进
 * aweme 列表，带 is_ad / ad_info / ad_type 这类字段。抖音系都是这套）。
 * 所以不能挨个删 UI，只能在数据源头把这一条从 List 里摘掉——摘掉之后上拉分页、
 * 点赞号都不会错位，因为数量是数据层就变了的。
 *
 * 判广告只看「模型自己声明的标记」，不猜文案：
 *   1) 类名里带 ad / advert / ads 的模型直接算广告（广告数据模型不可能是正经内容）
 *   2) 无参方法 isAd()/hasAd()/getAdInfo() 的返回值
 *   3) 字段名里带 ad 的 boolean=true / 对象非空 / int>0
 * 三条中一条命中就摘。
 */
public final class FeedFilter {

    private static final String[] AD_METHODS = {
            "isAd", "isAds", "hasAd", "isAdvertisement", "isAdItem", "isAdCard", "isAdVideo",
    };
    private static final String[] AD_INFO_METHODS = {
            "getAdInfo", "getAdInfoObj", "getAdConfig", "getAdModel",
    };

    private FeedFilter() {}

    /* ---------------- 列表过滤 ---------------- */

    /**
     * 就地摘广告。返回摘掉的条数；返回 0 表示这条列表没广告，调用方什么都不用做。
     * 拿到的 List 是 Collections.unmodifiableList 之类不可改的时候退化成“重建一份”。
     */
    static int stripAds(List list) {
        if (list == null || list.isEmpty()) return 0;
        int n = 0;
        try {
            for (int i = list.size() - 1; i >= 0; i--) {
                Object o;
                try { o = list.get(i); } catch (Throwable t) { continue; }
                if (isAdItem(o)) {
                    list.remove(i);
                    n++;
                }
            }
        } catch (Throwable t) {
            // 不可修改的列表：重建
            try {
                List<Object> keep = new ArrayList<Object>(list.size());
                for (int i = 0, s = list.size(); i < s; i++) {
                    Object o = list.get(i);
                    if (!isAdItem(o)) keep.add(o);
                }
                if (keep.size() != list.size()) {
                    list.clear();
                    list.addAll(keep);
                    n = keep.size();
                }
            } catch (Throwable ignored) {}
        }
        return n;
    }

    /** 判断一个列表元素是不是广告 */
    static boolean isAdItem(Object o) {
        if (o == null) return false;
        if (o instanceof CharSequence) return false;
        Class<?> c = o.getClass();
        String cn;
        try { cn = c.getName(); } catch (Throwable t) { return false; }
        if (cn.startsWith("java.") || cn.startsWith("android.")) return false;

        // 1) 类名就是广告模型
        List<String> ct = Engine.tokens(Engine.simpleName(cn));
        if (Engine.contains(ct, "ad") || Engine.contains(ct, "advert") || Engine.contains(ct, "ads")) {
            return true;
        }

        // 2) 无参方法
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                for (String name : AD_METHODS) {
                    Method m = findNoArg(k, name);
                    if (m == null) continue;
                    if (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class) {
                        m.setAccessible(true);
                        Object v = m.invoke(o);
                        if (Boolean.TRUE.equals(v)) return true;
                    }
                }
                for (String name : AD_INFO_METHODS) {
                    Method m = findNoArg(k, name);
                    if (m == null) continue;
                    m.setAccessible(true);
                    if (m.invoke(o) != null) return true;
                }
            } catch (Throwable ignored) {}
        }

        // 3) 字段
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fs;
            try { fs = k.getDeclaredFields(); } catch (Throwable t) { continue; }
            for (Field f : fs) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                List<String> tk = Engine.tokens(f.getName());
                boolean adName = Engine.contains(tk, "ad") || Engine.contains(tk, "advert")
                        || Engine.contains(tk, "ads") || Engine.contains(tk, "adv")
                        || Engine.contains(tk, "commerce");
                if (!adName) continue;
                try {
                    f.setAccessible(true);
                    Class<?> t = f.getType();
                    if (t == boolean.class || t == Boolean.class) {
                        if (Boolean.TRUE.equals(f.get(o))) return true;
                    } else if (t == int.class || t == Integer.class) {
                        Object v = f.get(o);
                        if (v instanceof Integer && ((Integer) v).intValue() > 0) return true;
                    } else if (t == long.class || t == Long.class) {
                        Object v = f.get(o);
                        if (v instanceof Long && ((Long) v).longValue() > 0) return true;
                    } else if (t != String.class) {
                        if (f.get(o) != null) return true;   // ad_info 之类对象字段非空
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static Method findNoArg(Class<?> k, String name) {
        try {
            Method m = k.getDeclaredMethod(name);
            if (m.getParameterTypes().length == 0) return m;
        } catch (Throwable ignored) {}
        return null;
    }

    /* ---------------- 广告模型字段补丁 ---------------- */

    /** 把广告数据模型里的标记字段刷成“无广告”，用于那些不看 List 只看 flag 的页面 */
    static void patchAdModel(Object obj) {
        if (obj == null) return;
        Class<?> c = obj.getClass();
        for (; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fs;
            try { fs = c.getDeclaredFields(); } catch (Throwable t) { continue; }
            for (Field f : fs) {
                try {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType() == String.class) continue;
                    List<String> tk = Engine.tokens(f.getName());
                    boolean adName = Engine.contains(tk, "ad") || Engine.contains(tk, "advert")
                            || Engine.contains(tk, "ads") || Engine.contains(tk, "adv");
                    if (!adName) continue;
                    f.setAccessible(true);
                    Class<?> t = f.getType();
                    if (t == boolean.class || t == Boolean.class) {
                        f.setBoolean(obj, false);
                    } else if (t == int.class || t == Integer.class) {
                        f.setInt(obj, 0);
                    } else if (t == long.class || t == Long.class) {
                        f.setLong(obj, 0L);
                    } else {
                        f.set(obj, null);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }
}
