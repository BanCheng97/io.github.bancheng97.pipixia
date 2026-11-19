package com.halfcity.pipixia;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 皮皮虾进程里读设置界面写下的开关。
 * 走 ContentProvider 的 call()（比 cursor 轻），拿不到就静默返回空 Map，回落到配置文件默认值。
 */
final class RemotePrefs {

    private static final Uri URI = Uri.parse("content://io.github.bancheng97.pipixia.prefs");

    private RemotePrefs() {}

    static Map<String, String> read(Context ctx) {
        Map<String, String> out = new HashMap<String, String>();
        if (ctx == null) return out;
        try {
            Bundle b = ctx.getContentResolver().call(URI, "all", null, null);
            if (b != null) {
                Set<String> keys = b.keySet();
                for (String k : keys) {
                    try {
                        String v = b.getString(k);
                        if (v != null) out.put(k, v);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
