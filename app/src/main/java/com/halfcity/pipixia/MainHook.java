package com.halfcity.pipixia;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 入口。只进皮皮虾（com.sup.android.superb）。
 *
 * 纪律：主线程这儿不干重活，只挂 ClassLoader 钩子和 Application.attach，
 * 扫描 / 读配置 / 写报告全丢后台线程（见 Engine.bootstrap）。
 * 模块自己出任何错都只记日志，绝不把异常抛回 App。
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "[PXXHook] ";
    public static final String AUTHOR = "半城";
    public static final String VERSION = "6.0";

    /** 皮皮虾包名（红魔 7S Pro 上实测 6.6.1 是这个） */
    public static final String[] TARGET_PACKAGES = {"com.sup.android.superb"};

    /** 设置 Activity 在模块自己的包里，跨 App 拉起 */
    public static final String SETTINGS_PKG = "com.halfcity.pipixia";
    public static final String SETTINGS_CLASS = "com.halfcity.pipixia.SettingsActivity";

    public static volatile String pkg;
    /** 当前进程名（皮皮虾有 4 个进程，状态文件只让主进程写） */
    public static volatile String processName;
    public static volatile Context appContext;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (!isTarget(lpparam.packageName)) return;
            pkg = lpparam.packageName;
            processName = lpparam.processName;
            // 紧急开关：这个文件存在就完全不动手（不用去 LSPosed 里关模块）
            if (isKilled()) {
                Log.i("发现 pipixia_off，模块本次不干活");
                return;
            }
            Log.i("皮皮虾净化 v" + VERSION + " by " + AUTHOR + " 已注入");
            Log.i("进程 " + lpparam.processName + " pid=" + android.os.Process.myPid());
            // 启动期只挂 SDK 公开入口 + UI 信号，绝不碰 ClassLoader
            Engine.installEarlyHooks(lpparam.classLoader);
            hookApplicationAttach();
        } catch (Throwable t) {
            Log.e("handleLoadPackage 出错", t);
        }
    }

    /** /sdcard/Android/data/com.sup.android.superb/files/pipixia_off 存在 = 紧急关闭 */
    private static boolean isKilled() {
        try {
            return new java.io.File(
                    "/sdcard/Android/data/com.sup.android.superb/files/pipixia_off").exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 主进程（进程名 == 包名）才负责写状态/启动记录，避免多进程互相覆盖 */
    static boolean isMainProcess() {
        String pn = processName;
        return pn == null || pn.equals(pkg);
    }

    private static boolean isTarget(String name) {
        if (name == null) return false;
        for (String t : TARGET_PACKAGES) if (t.equals(name)) return true;
        return false;
    }

    private void hookApplicationAttach() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.args[0];
                                if (ctx == null) return;
                                appContext = ctx;
                                Engine.bootstrap(ctx);   // 只起线程
                            } catch (Throwable t) {
                                Log.e("attach 后启动失败", t);
                            }
                        }
                    });
            Log.d("Application.attach 已挂钩");
        } catch (Throwable t) {
            Log.e("hook Application.attach 失败", t);
        }
    }
}
