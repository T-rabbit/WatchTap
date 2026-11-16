package dev.watchtap;

import java.util.ArrayList;
import java.util.List;

/**
 * 心率：按需拉取 → 解析 → 存最近一次结果。
 *
 * 协议（实测确认）：
 *   请求 SEND sid=5 cid=12 len=12:
 *       08 <start_ts varint> 10 <end_ts varint>      ← 只要时间范围
 *   返回 RECV sid=5 cid=12:
 *       08 <start_ts> 10 <end_ts> 18 <ts> 22 <重复{f1=相对秒, f2=bpm, f3=3}>
 *
 * 采样：夜间 2 分钟一点；白天活动时会出现 20 秒密集点。
 */
final class HeartRate {

    static final int SID = 5;
    static final int CID = 12;

    /** 最近一次结果 */
    private static volatile long sLastFetchAt;
    private static volatile long sLastDataTs;      // 数据本身的最新时间
    private static volatile int sLastBpm = -1;
    private static volatile int sMinBpm = -1;
    private static volatile int sMaxBpm = -1;
    private static volatile int sCount;
    private static volatile String sMessage = "还没有拉取过";

    private HeartRate() {}

    /**
     * 发起一次拉取。
     *
     * @param fromSec 起始秒（0 表示「从上次数据之后」）
     */
    static synchronized boolean fetch(long fromSec) {
        if (!HealthTx.ready()) {
            sMessage = "通道未就绪，先让 OPPO 健康发一次数据";
            return false;
        }
        long now = System.currentTimeMillis() / 1000L;
        long from = fromSec > 0 ? fromSec : (sLastDataTs > 0 ? sLastDataTs : now - 6 * 3600L);
        if (from >= now) from = now - 60;

        byte[] payload = buildRange(from, now);
        boolean ok = HealthTx.request(SID, CID, payload);
        if (ok) {
            sLastFetchAt = System.currentTimeMillis();
            sMessage = "已请求 " + fmt(from) + " → " + fmt(now) + "，等待手表回传";
            Capture.meta("心率拉取请求 " + fmt(from) + "→" + fmt(now));
        } else {
            sMessage = "请求发送失败";
        }
        return ok;
    }

    /**
     * 构造请求体：08 <start> 10 <end>（两个 varint 时间戳）。
     * 实测每个请求都只有这 12 字节。
     */
    private static byte[] buildRange(long fromSec, long toSec) {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(16);
        bo.write(0x08);
        writeVarint(bo, fromSec);
        bo.write(0x10);
        writeVarint(bo, toSec);
        return bo.toByteArray();
    }

    private static void writeVarint(java.io.ByteArrayOutputStream bo, long v) {
        while (true) {
            long b = v & 0x7f;
            v >>>= 7;
            if (v != 0) bo.write((int) (b | 0x80));
            else { bo.write((int) b); break; }
        }
    }

    /** 收到 cid=12 的回包时调用，解析并更新状态。 */
    static synchronized void onResponse(byte[] data) {
        try {
            List<int[]> pts = parse(data);
            if (pts.isEmpty()) {
                sMessage = "收到回包但没有心率点";
                return;
            }
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, last = -1;
            long lastTs = 0;
            for (int[] p : pts) {
                int bpm = p[1];
                if (bpm <= 0) continue;
                if (bpm < min) min = bpm;
                if (bpm > max) max = bpm;
                last = bpm;
                lastTs = p[2];
            }
            if (last < 0) {
                sMessage = "回包里没有有效心率值";
                return;
            }
            sMinBpm = min;
            sMaxBpm = max;
            sLastBpm = last;
            sCount = pts.size();
            if (lastTs > 0) sLastDataTs = lastTs;
            sMessage = "已更新 " + pts.size() + " 个点";
            Capture.meta("心率到达 " + pts.size() + " 点，最新 " + last + "bpm @ " + fmt(lastTs));
        } catch (Throwable t) {
            sMessage = "解析失败: " + t;
        }
    }

    /** 解析回包 → [index, bpm, absoluteTs] */
    private static List<int[]> parse(byte[] b) {
        List<int[]> out = new ArrayList<>();
        if (b == null || b.length < 4) return out;
        int[] pos = {0};
        long base = 0;
        // 顶层：f2=起始, f3=结束, f4=重复
        while (pos[0] < b.length) {
            long tag = readVarint(b, pos);
            if (tag < 0) break;
            int fn = (int) (tag >>> 3), wt = (int) (tag & 7);
            if (wt == 0) {
                long v = readVarint(b, pos);
                if (fn == 2 && base == 0) base = v;
            } else if (wt == 2) {
                long ln = readVarint(b, pos);
                if (ln < 0 || pos[0] + ln > b.length) break;
                if (fn == 4) collectPoints(b, pos[0], (int) ln, base, out);
                pos[0] += (int) ln;
            } else {
                break;
            }
        }
        return out;
    }

    private static void collectPoints(byte[] b, int off, int len, long base, List<int[]> out) {
        int[] pos = {off};
        int end = off + len;
        int idx = 0, bpm = -1, flag = -1;
        while (pos[0] < end) {
            long tag = readVarint(b, pos);
            if (tag < 0) break;
            int fn = (int) (tag >>> 3), wt = (int) (tag & 7);
            if (wt == 0) {
                long v = readVarint(b, pos);
                if (fn == 1) idx = (int) v;
                else if (fn == 2) bpm = (int) v;
                else if (fn == 3) flag = (int) v;
            } else {
                break;
            }
        }
        if (bpm > 0) out.add(new int[]{idx, bpm, (int) (base + idx)});
    }

    private static long readVarint(byte[] b, int[] pos) {
        long r = 0;
        int s = 0;
        while (pos[0] < b.length) {
            int c = b[pos[0]++] & 0xff;
            r |= (long) (c & 0x7f) << s;
            if ((c & 0x80) == 0) return r;
            s += 7;
            if (s > 63) return -1;
        }
        return -1;
    }

    private static String fmt(long sec) {
        if (sec <= 0) return "-";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(sec * 1000L);
        return String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    // ---- 供 UI 读取 ----

    static String statusLine() {
        if (sLastBpm < 0) return sMessage;
        return sLastBpm + " bpm  ·  " + sCount + " 点";
    }

    static String detailLine() {
        if (sLastBpm < 0) return sMessage;
        return "范围 " + sMinBpm + "~" + sMaxBpm + " bpm";
    }

    /** 「上次更新」文案 */
    static String updatedLine() {
        if (sLastDataTs <= 0) {
            return sLastFetchAt > 0 ? "已请求，等待回传" : "还没有数据";
        }
        long ago = System.currentTimeMillis() / 1000L - sLastDataTs;
        String when;
        if (ago < 60) when = "刚刚";
        else if (ago < 3600) when = (ago / 60) + " 分钟前";
        else if (ago < 86400) when = (ago / 3600) + " 小时前";
        else when = (ago / 86400) + " 天前";
        return "更新于 " + when + "（" + fmt(sLastDataTs) + "）";
    }

    /** 数据是否新鲜（5 分钟内） */
    static boolean fresh() {
        if (sLastDataTs <= 0) return false;
        return System.currentTimeMillis() / 1000L - sLastDataTs < 300;
    }
}
