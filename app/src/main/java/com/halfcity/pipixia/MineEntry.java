package com.halfcity.pipixia;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 「我的 → 更多功能」里的入口，学皮皮虾助手（Secack/ppx, GPL-3.0）的做法：
 *
 * 它不是往界面里 addView，而是**改数据列表**——
 *   1. dex 里找 `com.sup.android.m_mine.utils` 下、有 Context 和 String 字段、
 *      无参且返回 ArrayList 的方法 = 「更多功能」的 tab 列表；
 *   2. hook 住它，往返回的列表里塞一个复制品 MyTabItem：
 *      tabName=净化设置 / tabSchema=sdk://halfcity.settings / type=4 / schemaNeedLogin=false；
 *   3. 再 hook 点击：`<tabView>$1.doClick(View)` 里按 schema 判断，命中就拉起模块设置。
 *
 * 为什么这样稳：列表项是皮皮虾自己的 bean，渲染/点击都走它原有逻辑，我们只换数据；
 * 界面注入那种做法（4.4/4.5）一旦页面结构变就白干，这次实测也确实没出来。
 *
 * 拿不到列表就退回 4.4 的右上角胶囊，保证永远有一个能点。
 */
final class MineEntry {

    /** 我们自己的 schema，点击时按它认领 */
    private static final String SCHEMA = "sdk://halfcity.settings";

    private static ClassLoader cl;
    private static volatile boolean listInjected;

    private MineEntry() {}

    static void install(final ClassLoader loader) {
        cl = loader;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 150 && !Engine.isEnabled(); i++) Thread.sleep(100);
                    hookTabList();
                    hookPerformClick();
                    if (!listInjected) {
                        Log.i("列表注入失败，退回胶囊入口");
                        hookPill();
                    }
                } catch (Throwable t) {
                    Log.d("我的页入口失败 " + t);
                    hookPill();
                }
            }
        }, "pxx-mine");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /* ---------------- 一、改 tab 列表 ---------------- */

    private static void hookTabList() {
        try {
            List<String> names = DexClasses.all(cl);
            Method target = null;
            for (String n : names) {
                if (!n.startsWith("com.sup.android.m_mine.utils")) continue;
                Class<?> c;
                try { c = Class.forName(n, false, cl); } catch (Throwable e) { continue; }
                boolean hasCtx = false, hasStr = false;
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == Context.class) hasCtx = true;
                    if (f.getType() == String.class) hasStr = true;
                }
                if (!hasCtx || !hasStr) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length != 0) continue;
                    if (m.getReturnType() != ArrayList.class) continue;
                    target = m;
                    break;
                }
                if (target != null) break;
            }
            if (target == null) {
                Log.i("没找到 myTabList（更多功能列表）");
                CrashLog.record("我的页入口", "没找到 myTabList");
                return;
            }
            final Method method = target;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!Config.entryMine || !Engine.isEnabled()) return;
                        mutate((List<?>) param.getResult());
                    } catch (Throwable ignored) {}
                }
            });
            listInjected = true;
            Log.i("更多功能列表已挂 " + method.getDeclaringClass().getName() + "#" + method.getName());
            CrashLog.record("我的页入口", "列表已挂 " + method.getDeclaringClass().getName() + "#" + method.getName());
            hookClick();
        } catch (Throwable t) {
            Log.d("挂 tab 列表失败 " + t);
        }
    }

    @SuppressWarnings("rawtypes")
    private static void mutate(List list) {
        if (list == null || list.isEmpty()) return;
        Object template = list.size() > 1 ? list.get(1) : list.get(0);
        if (template == null) return;

        Object item = cloneBean(template);
        if (item == null) {
            // 克隆不出来就学 ppx：直接改掉一个现成的格子
            item = template;
            Log.d("克隆失败，改为覆盖现有格子");
        }
        call(item, "setTabName", new Class<?>[]{String.class}, "净化设置");
        call(item, "setTabSchema", new Class<?>[]{String.class}, SCHEMA);
        call(item, "setType", new Class<?>[]{int.class}, 4);
        call(item, "setExtra", new Class<?>[]{String.class},
                "{\"icon_list\":null,\"alert\":false}");
        call(item, "setSchemaNeedLogin", new Class<?>[]{boolean.class}, false);

        if (item != template) {
            try {
                list.add(item);
                Log.i("更多功能里加了一格「净化设置」");
            } catch (Throwable t) {
                Log.d("加格子失败 " + t);
            }
        }
    }

    /** 反射造一个同类型 bean 再拷字段；没有无参构造就返回 null */
    private static Object cloneBean(Object src) {
        try {
            Object dst = src.getClass().getDeclaredConstructor().newInstance();
            for (Class<?> k = src.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        f.set(dst, f.get(src));
                    } catch (Throwable ignored) {}
                }
            }
            return dst;
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------------- 二、点击认领 ---------------- */

    private static void hookClick() {
        try {
            List<String> names = DexClasses.all(cl);
            Class<?> tabViewClass = null;
            Method getter = null;
            for (String n : names) {
                if (!n.startsWith("com.sup.android.m_mine.view.subview")) continue;
                Class<?> c;
                try { c = Class.forName(n, false, cl); } catch (Throwable e) { continue; }
                boolean hasLinear = false;
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().getName().equals("android.widget.LinearLayout")) hasLinear = true;
                }
                if (!hasLinear) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length != 1) continue;
                    if (m.getParameterTypes()[0] != c) continue;
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (!m.getReturnType().getName().endsWith("MyTabItem")) continue;
                    tabViewClass = c;
                    getter = m;
                    break;
                }
                if (tabViewClass != null) break;
            }
            if (tabViewClass == null) {
                Log.i("没找到 tabView 类");
                CrashLog.record("我的页入口", "没找到 tabView 类");
                return;
            }
            final Class<?> tvc = tabViewClass;
            final Method g = getter;
            String inner = tabViewClass.getName() + "$1";
            Class<?> innerCls;
            try { innerCls = Class.forName(inner, false, cl); } catch (Throwable t) {
                Log.i("没找到 " + inner);
                return;
            }
            for (Method m : innerCls.getDeclaredMethods()) {
                if (!"doClick".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || !View.class.isAssignableFrom(ps[0])) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!Config.entryMine || !Engine.isEnabled()) return;
                            Object listener = param.thisObject;
                            Object tabView = fieldOfType(listener, tvc);
                            if (tabView == null) tabView = fieldOfType(listener, View.class);
                            if (tabView == null) return;
                            Object item = g.invoke(null, tabView);
                            if (item == null) return;
                            Object schema = call(item, "getTabSchema", new Class<?>[0]);
                            if (!SCHEMA.equals(schema)) return;
                            View v = (View) tabView;
                            open(v.getContext());
                            param.setResult(null);   // 吞掉这次点击
                            Log.i("从「更多功能」打开设置");
                        } catch (Throwable ignored) {}
                    }
                });
                Log.i("tab 点击已挂 " + innerCls.getName() + "#" + m.getName());
                CrashLog.record("我的页入口", "点击已挂 " + innerCls.getName() + "#" + m.getName());
                break;
            }
        } catch (Throwable t) {
            Log.d("挂点击失败 " + t);
        }
    }

    private static Object fieldOfType(Object target, Class<?> type) {
        if (target == null) return null;
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!type.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    return f.get(target);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static void open(Context ctx) {
        try {
            Intent i = new Intent();
            i.setClassName(MainHook.SETTINGS_PKG, MainHook.SETTINGS_CLASS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.d("打开设置失败 " + t);
        }
    }

    /* ---------------- 二点五、按文字认领点击（不依赖类名） ---------------- */

    private static boolean performClickHooked;

    private static void hookPerformClick() {
        if (performClickHooked) return;
        performClickHooked = true;
        try {
            XposedBridge.hookAllMethods(View.class, "performClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!Config.entryMine || !Engine.isEnabled()) return;
                        if (!(param.thisObject instanceof View)) return;
                        View v = (View) param.thisObject;
                        if (!isOurEntry(v)) return;
                        open(v.getContext());
                        param.setResult(Boolean.TRUE);   // 吞掉点击
                        CrashLog.record("我的页入口", "performClick 命中并打开设置");
                        Log.i("performClick 命中「净化设置」");
                    } catch (Throwable ignored) {}
                }
            });
            Log.d("performClick 兜底已挂");
        } catch (Throwable t) {
            Log.d("挂 performClick 失败 " + t);
        }
    }

    /** 点击的 View 本身或它的直接子 View 里有文字「净化设置」就算命中 */
    private static boolean isOurEntry(View v) {
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && "净化设置".contentEquals(t)) return true;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = Math.min(g.getChildCount(), 10);
            for (int i = 0; i < n; i++) {
                View c = g.getChildAt(i);
                if (c instanceof android.widget.TextView) {
                    CharSequence t = ((android.widget.TextView) c).getText();
                    if (t != null && "净化设置".contentEquals(t)) return true;
                }
            }
        }
        return false;
    }

    /* ---------------- 三、兜底：右上角胶囊 ---------------- */

    private static void hookPill() {
        try {
            Class<?> frag = null;
            try {
                frag = Class.forName("androidx.fragment.app.Fragment", false, cl);
            } catch (Throwable ignored) {}
            if (frag == null) {
                try { frag = Class.forName("android.app.Fragment", false, cl); } catch (Throwable ignored) {}
            }
            if (frag == null) return;
            XposedBridge.hookAllMethods(frag, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!Config.entryMine || !Engine.isEnabled()) return;
                        Object f = param.thisObject;
                        String cn = f.getClass().getName().toLowerCase();
                        if (!cn.contains("mine") && !cn.contains("profile")) return;
                        final Activity a = (Activity) call(f, "getActivity", new Class<?>[0]);
                        if (a == null) return;
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                try { Pill.add(a, SCHEMA); } catch (Throwable ignored) {}
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            });
            CrashLog.record("我的页入口", "退回胶囊方案");
            Log.d("胶囊兜底已挂");
        } catch (Throwable t) {
            Log.d("挂胶囊兜底失败 " + t);
        }
    }

    /* ---------------- 反射小工具 ---------------- */

    static Object call(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) return null;
        try {
            Method m = null;
            if (types != null && types.length > 0) {
                for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    try { m = k.getDeclaredMethod(name, types); break; } catch (Throwable ignored) {}
                }
            }
            if (m == null) {
                for (Method cand : target.getClass().getMethods()) {
                    if (cand.getName().equals(name)
                            && (args == null || cand.getParameterTypes().length == args.length)) {
                        m = cand;
                        break;
                    }
                }
            }
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }
}
