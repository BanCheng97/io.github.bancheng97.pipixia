package com.halfcity.pipixia;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 不加载类就能列出 APK 里所有类名。
 *
 * 思路：BaseDexClassLoader → pathList → dexElements[] → dexFile → entries()。
 * 这是 ART 自己维护的索引，只读不加载，不会有 hook ClassLoader 带来的启动期死锁，
 * 拿到的就是「这个 APK 里到底有哪些类」，用来按前缀找混淆类（比如开屏广告类）。
 */
final class DexClasses {

    private DexClasses() {}

    @SuppressWarnings("unchecked")
    static List<String> all(ClassLoader cl) {
        Set<String> out = new LinkedHashSet<String>();
        try {
            ClassLoader cur = cl;
            Object pathList = null;
            for (int i = 0; i < 6 && cur != null; i++) {
                pathList = field(cur, "pathList");
                if (pathList != null) break;
                cur = cur.getParent();
            }
            if (pathList == null) return new ArrayList<String>();

            Object arr = field(pathList, "dexElements");
            if (!(arr instanceof Object[])) return new ArrayList<String>();
            for (Object el : (Object[]) arr) {
                if (el == null) continue;
                Object dexFile = field(el, "dexFile");
                if (dexFile == null) continue;
                Object entries;
                try {
                    entries = dexFile.getClass().getMethod("entries").invoke(dexFile);
                } catch (Throwable t) {
                    continue;
                }
                if (!(entries instanceof Enumeration)) continue;
                Enumeration<String> e = (Enumeration<String>) entries;
                while (e.hasMoreElements()) {
                    String n = e.nextElement();
                    if (n != null) out.add(n);
                }
            }
        } catch (Throwable t) {
            Log.d("枚举 dex 失败 " + t);
        }
        return new ArrayList<String>(out);
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
