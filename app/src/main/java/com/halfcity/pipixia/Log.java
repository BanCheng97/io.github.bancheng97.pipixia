package com.halfcity.pipixia;

import de.robv.android.xposed.XposedBridge;

/** 统一日志，LSPosed 日志 / logcat 里搜 PXXHook 就能看到全部输出。 */
public final class Log {
    private Log() {}

    public static void i(String s) {
        XposedBridge.log(MainHook.TAG + s);
    }

    public static void d(String s) {
        if (Config.debug) XposedBridge.log(MainHook.TAG + "D " + s);
    }

    public static void e(String s, Throwable t) {
        XposedBridge.log(MainHook.TAG + "E " + s + " -> " + t);
        if (t != null) XposedBridge.log(t);
    }
}
