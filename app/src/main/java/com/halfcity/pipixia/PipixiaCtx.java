package com.halfcity.pipixia;

import android.content.Context;

import java.io.File;

import de.robv.android.xposed.XposedHelpers;

/** 极小的上下文持有者，给 UrlFixer 之类没拿到 Context 的地方用（避免到处传参）。 */
final class PipixiaCtx {

    private static volatile Context app;

    private PipixiaCtx() {}

    static void set(Context c) { app = c; }

    static Context get() { return app; }

    static android.content.ContentResolver resolver() {
        Context c = app;
        return c == null ? null : c.getContentResolver();
    }

    static File dir() {
        Context c = app;
        if (c == null) return null;
        try { return c.getExternalFilesDir(null); } catch (Throwable t) { return null; }
    }
}
