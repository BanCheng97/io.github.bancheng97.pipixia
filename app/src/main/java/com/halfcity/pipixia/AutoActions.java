package com.halfcity.pipixia;

import android.view.View;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 自动送神评。
 *
 * 皮皮虾的机制：评论右边的「大拇指」，文字是「送神评」→ 点完变「已送神」，
 * 当天送过的显示「已送满」。所以自动化不用碰接口，只要按钮一出现就替用户点一下。
 *
 * 做法：挂 TextView.setText（按钮文字每次切换都会走这里）和 View.setContentDescription
 * （有些版本按钮是纯图标，文字藏在 description 里）。命中「送神」就 postDelayed 后
 * performClick——延迟是必须的，setText 那一刻监听器可能还没绑上。
 *
 * 三条保险，防止失控乱点：
 *   1. 同一个 View 只点一次（按 identityHashCode 记账）；
 *   2. 两次点击之间至少隔 auto_shen_interval 毫秒（默认 1500）；
 *   3. 单次进程最多点 auto_shen_max 次（默认 30），到上限就闭嘴。
 */
public final class AutoActions {

    private static final Set<Integer> DONE = Collections.synchronizedSet(new HashSet<Integer>());

    private static volatile long lastClickAt;
    private static volatile int clicked;

    private AutoActions() {}

    public static void install() {
        try {
            XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof TextView)) return;
                        TextView tv = (TextView) param.thisObject;
                        inspect(tv, tv.getText());
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.hookAllMethods(View.class, "setContentDescription", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object a = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        if (a instanceof CharSequence && param.thisObject instanceof View) {
                            inspect((View) param.thisObject, (CharSequence) a);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            Log.d("自动送神评钩子已挂");
        } catch (Throwable t) {
            Log.e("挂自动送神评失败", t);
        }
    }

    private static void inspect(final View v, CharSequence text) {
        if (!Config.autoShen || !Engine.isEnabled()) return;
        if (v == null || text == null) return;
        int len = text.length();
        if (len < 2 || len > 6) return;                 // 快速过滤，绝大多数文本在这一步就滚
        String s = text.toString();
        if (!s.contains("送神")) return;                 // 送神评 / 送神评… 都命中
        if (s.contains("已送")) return;                  // 已送神 / 已送满，跳过

        final int id = System.identityHashCode(v);
        if (!DONE.add(id)) return;
        if (clicked >= Config.autoShenMax) return;
        long now = System.currentTimeMillis();
        if (now - lastClickAt < Config.autoShenInterval) return;
        lastClickAt = now;
        clicked++;

        final View target = clickableFor(v);
        target.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!target.isShown()) return;
                    boolean ok = target.performClick();
                    if (ok) Stats.shenClicks.incrementAndGet();
                    Log.d("送神评 " + (ok ? "已点" : "点了没响应") + " " + target.getClass().getName());
                } catch (Throwable t) {
                    Log.d("送神评点击失败 " + t);
                }
            }
        }, 400);
    }

    /** 文字 View 自己不一定可点，往上找一层真正带点击的父容器 */
    private static View clickableFor(View v) {
        View cur = v;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            if (!(cur.getParent() instanceof View)) break;
            cur = (View) cur.getParent();
        }
        return v;
    }

    static int clickedCount() { return clicked; }
}
