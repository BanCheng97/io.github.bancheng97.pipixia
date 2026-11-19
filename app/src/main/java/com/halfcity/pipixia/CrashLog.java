package com.halfcity.pipixia;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * 日志落盘（5.5 起改到**模块自己的数据目录**，不再往下载目录丢文件）。
 *
 * 为什么绕一圈：日志是皮皮虾进程产生的，但文件要放在模块 App 的
 * `/data/data/io.github.bancheng97.pipixia/files/logs/`，两个 uid 不能直接写。
 * 所以还是走 PrefsProvider：皮皮虾进程 call("log", name, {text})，
 * provider 在模块进程里把文件写下去。**卸载模块 = 日志一起消失。**
 *
 * 兜底：provider 不可用时写皮皮虾自己的私有目录（卸载皮虾也会清）。
 *
 * 总开关：Config.logs = false 时一个字节都不写。
 */
final class CrashLog {

    static final String CRASH_FILE = "pipixia_crash.txt";
    static final String STATUS_FILE = "pipixia_status.txt";

    private static final String AUTHORITY = "io.github.bancheng97.pipixia.prefs";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private static final int MAX_CHARS = 12000;

    private static final StringBuilder CRASH_BUF = new StringBuilder();
    private static final java.util.Map<String, StringBuilder> BUFFERS =
            new java.util.HashMap<String, StringBuilder>();

    private CrashLog() {}

    static synchronized void record(String tag, String body) {
        if (!Config.logs || !Config.crashFile) return;
        String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date());
        CRASH_BUF.append('[').append(ts).append("] ").append(tag).append('\n');
        if (body != null && body.length() > 0) CRASH_BUF.append(body).append('\n');
        if (CRASH_BUF.length() > MAX_CHARS) CRASH_BUF.delete(0, CRASH_BUF.length() - MAX_CHARS);
        write(CRASH_FILE, CRASH_BUF.toString());
    }

    static synchronized void appendLine(String name, String line) {
        if (!Config.logs) return;
        StringBuilder sb = BUFFERS.get(name);
        if (sb == null) {
            sb = new StringBuilder();
            BUFFERS.put(name, sb);
        }
        sb.append(line).append('\n');
        if (sb.length() > 8000) sb.delete(0, sb.length() - 8000);
        write(name, sb.toString());
    }

    static void write(String name, String text) {
        if (text == null || !Config.logs) return;
        Context ctx = PipixiaCtx.get();
        if (ctx == null) return;

        // 1) 模块数据目录（卸载即清）
        try {
            Bundle in = new Bundle();
            in.putString("name", name);
            in.putString("text", text);
            ctx.getContentResolver().call(URI, "log", null, in);
            return;
        } catch (Throwable ignored) {}

        // 2) 兜底：皮皮虾私有目录（卸载皮虾也会清）
        try {
            File d = new File(ctx.getFilesDir(), "logs");
            if (!d.exists()) d.mkdirs();
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(new File(d, name)), "UTF-8");
            try { w.write(text); } finally { w.close(); }
        } catch (Throwable ignored) {}
    }

    static void delete(String name) {
        Context ctx = PipixiaCtx.get();
        if (ctx == null) return;
        try {
            ctx.getContentResolver().call(URI, "logdel", name, null);
        } catch (Throwable ignored) {}
        try {
            File d = new File(ctx.getFilesDir(), "logs");
            new File(d, name).delete();
        } catch (Throwable ignored) {}
    }
}
