package com.halfcity.pipixia;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** 兜底入口：右上角一个橙色胶囊「净化设置」（列表注入失败时才用）。 */
final class Pill {

    private static final String TAG = "pxx_entry";

    private Pill() {}

    static void add(final Activity activity, final String schema) {
        try {
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            FrameLayout root = (FrameLayout) content;
            if (root.findViewWithTag(TAG) != null) return;

            TextView tv = new TextView(activity);
            tv.setText("净化设置");
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTextColor(Color.WHITE);
            tv.setPadding(dp(activity, 12), dp(activity, 7), dp(activity, 12), dp(activity, 7));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFFF6B4A);
            bg.setCornerRadius(dp(activity, 18));
            tv.setBackground(bg);
            tv.setElevation(dp(activity, 4));
            tv.setTag(TAG);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            lp.topMargin = dp(activity, 56);
            lp.rightMargin = dp(activity, 14);
            tv.setLayoutParams(lp);

            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent i = new Intent();
                        i.setClassName(MainHook.SETTINGS_PKG, MainHook.SETTINGS_CLASS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        activity.startActivity(i);
                    } catch (Throwable ignored) {}
                }
            });
            root.addView(tv);
            Log.i("胶囊入口已加 " + activity.getClass().getName());
        } catch (Throwable ignored) {}
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density);
    }
}
