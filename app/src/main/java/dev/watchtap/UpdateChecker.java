package dev.watchtap;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 从 GitHub Releases 检查更新。
 *
 * ==== 为什么不用第三方库 ====
 * 模块只有 40KB，引 OkHttp 会变成几 MB。用原生 HttpURLConnection 足够。
 *
 * ==== 接口 ====
 *   GET https://api.github.com/repos/T-rabbit/WatchTap/releases/latest
 *
 * 返回 JSON，取这几个字段：
 *   tag_name      版本标签，如 "v2.19.0"
 *   body          更新说明
 *   assets[].name       文件名，如 "WatchTap-v2.19.0.apk"
 *   assets[].browser_download_url  下载地址
 *
 * ==== 版本比较 ====
 * 用 versionCode 比，而不是字符串。
 * 从 tag 里解析出像 2.19.0 这样的数字，转成 21900 比较；
 * 本地用 BuildConfig 里的 versionCode（在 build_apk.sh 里写入）。
 *
 * 但如果 tag 命名不规范，退回字符串比较，避免误判。
 */
final class UpdateChecker {

    private static final String API =
            "https://api.github.com/repos/T-rabbit/WatchTap/releases/latest";

    private UpdateChecker() {}

    /** 检查结果。 */
    static final class Result {
        boolean ok;              // 请求是否成功
        boolean hasUpdate;       // 是否有新版本
        String latestTag = "";   // 最新版本标签
        String currentTag = "";  // 当前版本
        String notes = "";       // 更新说明
        String apkUrl = "";      // APK 下载地址
        String error = "";       // 失败原因

        String describe() {
            StringBuilder sb = new StringBuilder();
            if (!ok) {
                sb.append("检查失败：").append(error);
                return sb.toString();
            }
            sb.append("当前版本：").append(currentTag).append('\n');
            sb.append("最新版本：").append(latestTag).append('\n');
            sb.append(hasUpdate ? "有更新可用" : "已是最新").append('\n');
            if (hasUpdate && !notes.isEmpty()) {
                sb.append('\n').append(notes);
            }
            return sb.toString();
        }
    }

    /** 回调接口（在主线程触发）。 */
    interface Callback {
        void onResult(Result r);
    }

    private static final AtomicBoolean sRunning = new AtomicBoolean(false);

    /**
     * 异步检查更新。
     *
     * @param cb 结果回调，在主线程执行
     */
    static void check(Context ctx, Callback cb) {
        if (ctx == null) {
            if (cb != null) {
                Result r = new Result();
                r.error = "无上下文";
                post(cb, r);
            }
            return;
        }

        // 防止重复点击
        if (!sRunning.compareAndSet(false, true)) {
            Result r = new Result();
            r.error = "正在检查中";
            post(cb, r);
            return;
        }

        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Result r;
            try {
                r = doCheck(app);
            } catch (Throwable t) {
                r = new Result();
                r.error = Logs.brief(t);
            } finally {
                sRunning.set(false);
            }
            post(cb, r);
        }, "watchtap-update").start();
    }

    private static void post(Callback cb, Result r) {
        if (cb == null) return;
        new Handler(Looper.getMainLooper()).post(() -> cb.onResult(r));
    }

    // ---------------------------------------------------------------- 实现

    private static Result doCheck(Context ctx) {
        Result r = new Result();
        r.currentTag = currentVersionName(ctx);

        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(API);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            // GitHub API 要求 User-Agent
            conn.setRequestProperty("User-Agent", "WatchTap-Android");
            conn.setRequestProperty("Accept", "application/vnd.github+json");

            int code = conn.getResponseCode();
            if (code == 404) {
                r.error = "仓库或 Release 不存在";
                return r;
            }
            if (code != 200) {
                r.error = "HTTP " + code;
                return r;
            }

            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }

            JSONObject json = new JSONObject(sb.toString());
            r.latestTag = json.optString("tag_name", "");
            r.notes = json.optString("body", "");

            // 找 APK 附件
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.toLowerCase().endsWith(".apk")) {
                        r.apkUrl = a.optString("browser_download_url", "");
                        break;
                    }
                }
            }

            r.ok = true;
            r.hasUpdate = isNewer(r.currentTag, r.latestTag);
            return r;
        } catch (Throwable t) {
            r.error = Logs.brief(t);
            return r;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 当前版本名，从 packageInfo 读。 */
    static String currentVersionName(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 当前 versionCode。 */
    static int currentVersionCode(Context ctx) {
        try {
            return (int) ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 判断 remote 是否比 current 新。
     *
     * 优先按「数字段比较」（2.10.0 > 2.9.0，字符串比较会错），
     * 解析失败时退回字符串不等判断。
     */
    static boolean isNewer(String current, String remote) {
        int[] c = parse(current);
        int[] r = parse(remote);

        if (c == null || r == null) {
            // 解析不了：只要不同就认为有更新（保守）
            return remote != null && !remote.equals(current);
        }

        int n = Math.max(c.length, r.length);
        for (int i = 0; i < n; i++) {
            int cv = i < c.length ? c[i] : 0;
            int rv = i < r.length ? r[i] : 0;
            if (rv > cv) return true;
            if (rv < cv) return false;
        }
        return false;
    }

    /** 从 "v2.19.0" / "2.19" / "release-2.19.0" 里抓数字段。 */
    private static int[] parse(String version) {
        if (version == null || version.isEmpty()) return null;
        try {
            StringBuilder cur = new StringBuilder();
            java.util.List<Integer> parts = new java.util.ArrayList<>();

            for (int i = 0; i < version.length(); i++) {
                char ch = version.charAt(i);
                if (ch >= '0' && ch <= '9') {
                    cur.append(ch);
                } else if (cur.length() > 0) {
                    parts.add(Integer.parseInt(cur.toString()));
                    cur.setLength(0);
                    // 只取前 4 段，避免 "20261031" 这种日期干扰
                    if (parts.size() >= 4) break;
                }
            }
            if (cur.length() > 0 && parts.size() < 4) {
                parts.add(Integer.parseInt(cur.toString()));
            }

            if (parts.isEmpty()) return null;
            int[] out = new int[parts.size()];
            for (int i = 0; i < parts.size(); i++) out[i] = parts.get(i);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 跳转

    /** 打开浏览器下载。 */
    static void openDownload(Context ctx, String apkUrl) {
        if (ctx == null || apkUrl == null || apkUrl.isEmpty()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Logs.w("打开下载页失败", t);
        }
    }

    /** 打开 Release 页面（没有 APK 附件时用）。 */
    static void openReleases(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/T-rabbit/WatchTap/releases"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Logs.w("打开 Release 页失败", t);
        }
    }
}
