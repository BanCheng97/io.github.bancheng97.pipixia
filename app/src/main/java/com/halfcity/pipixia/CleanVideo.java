package com.halfcity.pipixia;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真·无水印：走皮皮虾自己的签名播放接口拿干净地址。
 *
 * 原理（皮皮虾的视频其实是抖音 CDN 上的对象，带不带水印取决于请求哪个接口）：
 *
 *   1. 视频在模型里的唯一标识是 uri，长这样 v0300fg10000cgj0b8rc77u77pv2kabg；
 *   2. 签名接口：  GET https://i.snssdk.com/video/play/1/bds/{ts}/{sign}/mp4/{uri}
 *      sign = md5("ts" + ts + "userbdsversion1video" + uri + "vtypemp4f425df23905d4ee38685e276072faa0c")
 *      这个接口返回的 video_list.video_0..video_5 里，main_url 是 Base64 的**无水印**直链；
 *   3. 模型里自带的那条 url_list 是带水印的（皮皮虾自家 CDN 转码版），所以光改 playwm→play
 *      没用——它压根不是同一套接口。
 *
 * 参考实现：皮皮虾助手（Secack/ppx, GPL-3.0）的 VideoHook，接口和签名串一致。
 * 我们把地址写回 VideoModel.getUrlList() 里每个 UrlItem.setUrl()，
 * 播放和「保存到相册」就都走干净地址。
 */
public final class CleanVideo {

    /** 签名盐，来自皮皮虾客户端自己的播放签名逻辑 */
    private static final String SIGN_KEY = "vtypemp4f425df23905d4ee38685e276072faa0c";

    private static final Map<String, String> CACHE = new ConcurrentHashMap<String, String>();
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> RESOLVING = Collections.synchronizedSet(new HashSet<String>());
    /** 防重入：patchList 里绝不回调被我们 hook 的 getUrlList，否则递归到爆栈 */
    private static final ThreadLocal<Boolean> PATCHING = new ThreadLocal<Boolean>();

    private CleanVideo() {}

    /* ---------------- 对外 ---------------- */

    /** 同步解析（下载线程里调，最多卡 5 秒），拿不到就返回 null，走原地址 */
    static String resolve(String uri, long itemId) {
        if (uri != null) uri = uri.trim();
        if (uri == null || uri.length() == 0) uri = null;

        if (uri != null) {
            String c = CACHE.get(uri);
            if (c != null) return c;
            if (FAILED.contains(uri)) return null;
        }

        String realUri = uri;
        if (realUri == null) {
            if (itemId <= 0) return null;
            realUri = fetchOriginUri(itemId);
            if (realUri == null) return null;
            String cached = CACHE.get(realUri);
            if (cached != null) return cached;
        }

        long ts = System.currentTimeMillis();
        String sign = md5("ts" + ts + "userbdsversion1video" + realUri + SIGN_KEY);
        String api = "https://i.snssdk.com/video/play/1/bds/" + ts + "/" + sign + "/mp4/" + realUri;
        String body = get(api);
        if (body == null) return fail(realUri);

        String enc = pickBest(body);
        if (enc == null) return fail(realUri);

        String clean;
        try {
            clean = new String(Base64.decode(enc, Base64.DEFAULT), "UTF-8").trim();
        } catch (Throwable t) {
            return fail(realUri);
        }
        if (!clean.startsWith("http")) return fail(realUri);

        CACHE.put(realUri, clean);
        Stats.cleanOk.incrementAndGet();
        if (Config.dumpUrls) {
            CrashLog.appendLine("pipixia_urls.txt", realUri + " -> " + clean);
        }
        Log.i("无水印地址 " + realUri + " -> " + hostOf(clean));
        return clean;
    }

    /** 后台解析，给播放路径用（主线程不能联网） */
    static void resolveAsync(final String uri, final long itemId) {
        if (uri == null || uri.length() == 0) return;
        if (CACHE.containsKey(uri) || FAILED.contains(uri)) return;
        if (RESOLVING.size() > 4) return;            // 并发上限，别把线程数刷爆
        if (!RESOLVING.add(uri)) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    resolve(uri, itemId);
                } catch (Throwable ignored) {
                } finally {
                    RESOLVING.remove(uri);
                }
            }
        }, "pxx-clean");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    static String cached(String uri) {
        if (uri == null) return null;
        return CACHE.get(uri.trim());
    }

    /**
     * 直接改写 hook 拿到的那个 List（**不**再回头调 getUrlList）。
     * getUrlList 本身被我们 hook 着，如果在 after 里再调一次 getUrlList，
     * 就会 A→hook→A→hook 无限递归，主线程栈溢出 = 滑一下就卡死。
     */
    @SuppressWarnings("rawtypes")
    static void patchList(Object listObj, String clean) {
        if (clean == null || !(listObj instanceof List)) return;
        if (PATCHING.get() != null) return;
        PATCHING.set(Boolean.TRUE);
        try {
            List<?> l = (List<?>) listObj;
            int n = 0;
            for (Object item : l) {
                if (item == null) continue;
                try {
                    Method setUrl = findSetter(item.getClass());
                    if (setUrl == null) continue;
                    setUrl.setAccessible(true);
                    setUrl.invoke(item, clean);
                    n++;
                } catch (Throwable ignored) {}
            }
            if (n > 0) {
                Stats.cleanPatched.addAndGet(n);
                Log.d("回写无水印 " + n + " 条");
            }
        } finally {
            PATCHING.remove();
        }
    }

    /** 兜底入口：模型里已经有缓存地址就改，没有就后台解析 */
    static void patchModel(Object videoModel) {
        if (videoModel == null) return;
        try {
            String uri = str(call(videoModel, "getUri", new Class<?>[0]));
            if (uri == null || uri.length() == 0) return;
            String clean = CACHE.get(uri);
            if (clean == null) {
                resolveAsync(uri, 0L);
                return;
            }
            Object list = call(videoModel, "getUrlList", new Class<?>[0]);
            patchList(list, clean);
            Log.d("回写无水印 " + uri);
        } catch (Throwable ignored) {}
    }

    static boolean isCached(String uri) {
        return uri != null && CACHE.containsKey(uri);
    }

    /* ---------------- 内部 ---------------- */

    /** 模型里没 uri 时，用 item_id 换 origin_video_id */
    private static String fetchOriginUri(long itemId) {
        String body = get("https://h5.pipix.com/bds/webapi/item/detail/?item_id=" + itemId);
        if (body == null) return null;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject item = root.getJSONObject("data").getJSONObject("item");
            String v = item.optString("origin_video_id", null);
            if (v != null && v.length() > 0) return v;
        } catch (Throwable ignored) {}
        return null;
    }

    /** video_5 最好，逐级往下退 */
    private static String pickBest(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject list = root.getJSONObject("video_info")
                    .getJSONObject("data").getJSONObject("video_list");
            for (int i = 5; i >= 0; i--) {
                JSONObject v = list.optJSONObject("video_" + i);
                if (v == null) continue;
                String url = v.optString("main_url", null);
                if (url != null && url.length() > 0) return url;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String fail(String uri) {
        FAILED.add(uri);
        Stats.cleanFail.incrementAndGet();
        Log.d("无水印解析失败 " + uri);
        return null;
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                int v = b & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            c.setRequestProperty("User-Agent", "okhttp/3.12.0");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private static Method findSetter(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                for (Method m : k.getDeclaredMethods()) {
                    if (!m.getName().equals("setUrl")) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 1 && ps[0] == String.class) return m;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String hostOf(String s) {
        try {
            int i = s.indexOf("://");
            int slash = s.indexOf('/', i + 3);
            return slash > i ? s.substring(i + 3, slash) : s.substring(i + 3);
        } catch (Throwable t) {
            return "?";
        }
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) return null;
        try {
            Method m = null;
            if (types != null && types.length > 0) {
                try { m = target.getClass().getMethod(name, types); } catch (Throwable ignored) {}
            }
            if (m == null) {
                for (Method cand : target.getClass().getMethods()) {
                    if (cand.getName().equals(name)
                            && (args == null || cand.getParameterTypes().length == args.length)) {
                        m = cand;
                        break;
                    }
                }
            }
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    static List<String> cachedUris() {
        return new ArrayList<String>(CACHE.keySet());
    }

    static String normalized(String uri) {
        return uri == null ? "" : uri.trim().toLowerCase(Locale.ROOT);
    }
}
