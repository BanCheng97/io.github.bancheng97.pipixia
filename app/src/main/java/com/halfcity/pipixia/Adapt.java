package com.halfcity.pipixia;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;

/**
 * 版本适配验证：把「模块当前版本对皮皮虾这个版本的适配情况」推给设置界面。
 *
 * 跨进程还是靠 ContentProvider：皮皮虾进程往模块 App 的 provider 里 call("put", key, {v})，
 * 设置界面直接读 SharedPreferences 显示。
 */
final class Adapt {

    private static final Uri URI = Uri.parse("content://io.github.bancheng97.pipixia.prefs");

    private Adapt() {}

    static void report(final Context ctx) {
        if (ctx == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    push(ctx, "adapt_pkg", pkgVersion(ctx));
                    push(ctx, "adapt_dexCount", String.valueOf(Discover.lastTotal));
                    push(ctx, "adapt_videoModel", TargetHooks.videoModelFound ? "已找到" : "没找到");
                    push(ctx, "adapt_download", TargetHooks.downloadFound ? "已找到" : "没找到");
                    push(ctx, "adapt_selfads", SelfAds.scanned + " 类 / 挂 " + SelfAds.hooked);
                    push(ctx, "adapt_splash", TargetHooks.splashFound + " 个");
                    push(ctx, "adapt_stats", Stats.dump());
                    push(ctx, "adapt_time", new java.text.SimpleDateFormat(
                            "MM-dd HH:mm", java.util.Locale.ROOT).format(new java.util.Date()));
                    Log.d("适配信息已上报");
                } catch (Throwable t) {
                    Log.d("上报适配信息失败 " + t);
                }
            }
        }, "pxx-adapt").start();
    }

    private static String pkgVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            String vn = pi.versionName == null ? "?" : pi.versionName;
            return vn + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    static void push(Context ctx, String key, String value) {
        if (ctx == null || key == null) return;
        try {
            Bundle in = new Bundle();
            in.putString("v", value == null ? "" : value);
            ctx.getContentResolver().call(URI, "put", key, in);
        } catch (Throwable ignored) {}
    }
}
