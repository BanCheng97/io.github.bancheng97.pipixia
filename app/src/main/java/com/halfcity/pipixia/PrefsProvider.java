package com.halfcity.pipixia;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 设置读取的通道。
 *
 * 问题：皮皮虾跑在自己的进程里（uid 不同），读不到模块 App 的 SharedPreferences；
 * 而模块 App 也写不进皮皮虾的私有目录（Android 11+ scoped storage）。
 * 解法就是 ContentProvider：模块 App 把「键值」暴露出来，皮皮虾进程里的 Config 用
 * ContentResolver 查一下，绕过跨进程/权限限制。
 *
 *   content://com.halfcity.pipixia.prefs/all         → 全部键值（k, v 两列）
 *   content://com.halfcity.pipixia.prefs/get/<key>   → 单个值（v 一列）
 *   插入：values{k, v} 或 Uri 带 k，即可写入
 */
public class PrefsProvider extends ContentProvider {

    public static final String AUTHORITY = "com.halfcity.pipixia.prefs";

    private static final int ALL = 1;
    private static final int GET = 2;

    private final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);

    @Override
    public boolean onCreate() {
        matcher.addURI(AUTHORITY, "all", ALL);
        matcher.addURI(AUTHORITY, "get/*", GET);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        Context ctx = getContext();
        if (ctx == null) return null;
        int match = matcher.match(uri);
        if (match == GET) {
            String key = uri.getLastPathSegment();
            Feat f = Feat.byKey(key);
            String def = f == null ? "" : f.def;
            String v = PrefsStore.get(ctx, key, def);
            MatrixCursor c = new MatrixCursor(new String[]{"v"});
            c.addRow(new Object[]{v});
            return c;
        }
        if (match == ALL) {
            MatrixCursor c = new MatrixCursor(new String[]{"k", "v"});
            for (Feat f : Feat.ALL) {
                c.addRow(new Object[]{f.key, PrefsStore.get(ctx, f.key, f.def)});
            }
            return c;
        }
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Context ctx = getContext();
        if (ctx != null && values != null) {
            String k = values.getAsString("k");
            String v = values.getAsString("v");
            if (k != null) PrefsStore.put(ctx, k, v == null ? "" : v);
        }
        return uri;
    }

    /* ---------------- 日志：写在模块自己的数据目录里 ---------------- */

    static File logDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "logs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static List<String> logNames() {
        List<String> out = new ArrayList<String>();
        out.add("pipixia_status.txt");
        out.add("pipixia_crash.txt");
        out.add("pipixia_hooks.txt");
        out.add("pipixia_urls.txt");
        out.add("pipixia_classes.txt");
        return out;
    }

    private void writeLog(Context ctx, String name, String text) {
        try {
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(new File(logDir(ctx), name)), "UTF-8");
            try { w.write(text == null ? "" : text); } finally { w.close(); }
        } catch (Throwable ignored) {}
    }

    private String readLog(Context ctx, String name) {
        try {
            File f = new File(logDir(ctx), name);
            if (!f.exists()) return "";
            long len = f.length();
            long skip = len > 32768 ? len - 32768 : 0;
            FileInputStream in = new FileInputStream(f);
            try {
                long s = 0;
                while (s < skip) {
                    long n = in.skip(skip - s);
                    if (n <= 0) break;
                    s += n;
                }
                byte[] buf = new byte[(int) Math.min(len - skip, 32768)];
                int off = 0;
                while (off < buf.length) {
                    int r = in.read(buf, off, buf.length - off);
                    if (r < 0) break;
                    off += r;
                }
                return new String(buf, 0, off, "UTF-8");
            } finally { in.close(); }
        } catch (Throwable t) {
            return "";
        }
    }

    /** 导出到共享存储 Downloads/DSH-Go（只有用户点「导出」时才做） */
    private boolean exportLog(Context ctx, String name) {
        try {
            String text = readLog(ctx, name);
            android.content.ContentResolver cr = ctx.getContentResolver();
            android.net.Uri collection = android.provider.MediaStore.Downloads
                    .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues v = new ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/DSH-Go/");
            Uri uri = cr.insert(collection, v);
            if (uri == null) return false;
            java.io.OutputStream os = cr.openOutputStream(uri);
            if (os == null) return false;
            try { os.write(text.getBytes("UTF-8")); } finally { os.close(); }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle logOut = new Bundle();
        Context lctx = getContext();
        if ("log".equals(method) && lctx != null && extras != null) {
            writeLog(lctx, extras.getString("name", "log.txt"), extras.getString("text"));
            return logOut;
        }
        if ("logread".equals(method) && lctx != null && arg != null) {
            logOut.putString("text", readLog(lctx, arg));
            return logOut;
        }
        if ("logdel".equals(method) && lctx != null && arg != null) {
            try { new File(logDir(lctx), arg).delete(); } catch (Throwable ignored) {}
            return logOut;
        }
        if ("logclear".equals(method) && lctx != null) {
            for (String n : logNames()) {
                try { new File(logDir(lctx), n).delete(); } catch (Throwable ignored) {}
            }
            return logOut;
        }
        if ("logexport".equals(method) && lctx != null && arg != null) {
            logOut.putBoolean("ok", exportLog(lctx, arg));
            return logOut;
        }
        if ("logsize".equals(method) && lctx != null) {
            StringBuilder sb = new StringBuilder();
            for (String n : logNames()) {
                File f = new File(logDir(lctx), n);
                sb.append(n).append('=').append(f.exists() ? f.length() : 0).append('\n');
            }
            logOut.putString("list", sb.toString());
            return logOut;
        }
        Bundle out = new Bundle();
        Context ctx = getContext();
        if (ctx == null) return out;
        if ("all".equals(method)) {
            for (Feat f : Feat.ALL) out.putString(f.key, PrefsStore.get(ctx, f.key, f.def));
        } else if ("get".equals(method) && arg != null) {
            Feat f = Feat.byKey(arg);
            out.putString(arg, PrefsStore.get(ctx, arg, f == null ? "" : f.def));
        } else if ("put".equals(method) && arg != null && extras != null) {
            String v = extras.getString("v");
            PrefsStore.put(ctx, arg, v == null ? "" : v);
            out.putBoolean("ok", true);
        }
        return out;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        insert(uri, values);
        return 1;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.halfcity.prefs";
    }
}
