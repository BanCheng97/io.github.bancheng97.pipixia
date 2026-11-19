package com.halfcity.pipixia;

import android.content.Context;
import android.content.SharedPreferences;

/** 模块自己的设置存储（模块进程 + 设置界面共用）。 */
final class PrefsStore {

    static final String NAME = "settings";

    private PrefsStore() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static String get(Context c, String key, String def) {
        try {
            return prefs(c).getString(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static void put(Context c, String key, String value) {
        try {
            prefs(c).edit().putString(key, value).apply();
        } catch (Throwable ignored) {}
    }
}
