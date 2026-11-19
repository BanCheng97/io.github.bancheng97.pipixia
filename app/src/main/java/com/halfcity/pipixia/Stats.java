package com.halfcity.pipixia;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 干活计数。用来回答一个很实际的问题：「到底有没有效果？」
 *
 * 每 10 秒把计数写进 /sdcard/Download/DSH-Go/pipixia_status.txt：
 * 刷几条视频，广告拦截 / 摘广告 / 无水印解析 的数字往上跳，就是真的在起作用；
 * 数字全是 0，说明规则根本没命中，得换挂钩点。
 */
final class Stats {

    /** 广告加载被拦下的次数（SDK + 业务层） */
    static final AtomicInteger adBlocked = new AtomicInteger();
    /** 信息流里摘掉的广告条目数 */
    static final AtomicInteger adsRemoved = new AtomicInteger();
    /** 自动送神评点击次数 */
    static final AtomicInteger shenClicks = new AtomicInteger();
    /** 无水印地址解析成功 / 失败 */
    static final AtomicInteger cleanOk = new AtomicInteger();
    static final AtomicInteger cleanFail = new AtomicInteger();
    /** 回写到模型里的干净地址次数 */
    static final AtomicInteger cleanPatched = new AtomicInteger();
    /** 信息流净化（带货/直播/官方/屏蔽词）删掉的条数 */
    static final AtomicInteger feedCleaned = new AtomicInteger();
    /** hook 安装计数 */
    static final AtomicInteger hooksInstalled = new AtomicInteger();

    private Stats() {}

    static String dump() {
        return "拦截广告=" + adBlocked.get()
                + " 摘广告=" + adsRemoved.get()
                + " 送神评=" + shenClicks.get()
                + " 无水印成功=" + cleanOk.get()
                + " 无水印失败=" + cleanFail.get()
                + " 回写地址=" + cleanPatched.get()
                + " 净化条数=" + feedCleaned.get()
                + " 已挂钩子=" + hooksInstalled.get();
    }
}
