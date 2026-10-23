package com.step.bridge;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * 微信通知监听 + 息屏保活落盘。
 * 关键点：先入内存队列，立即 flush（带短 wakelock），另有 2 分钟心跳与息屏/亮屏事件，
 * 用心跳缺失来量化“服务被冻结”的时间段。
 */
public class WxNotify extends NotificationListenerService {
    static final String DIR = BridgeService.BRIDGE_DIR;
    static final String FILE = DIR + "/notify.jsonl";

    private static final Set<String> SEEN = new HashSet<String>();
    private static final ArrayList<String> QUEUE = new ArrayList<String>();
    public static volatile boolean ALIVE = false;
    public static volatile long LAST_TS = 0;
    public static volatile long LAST_BEAT = 0;
    public static volatile int COUNT = 0;

    private Handler hb;
    private BroadcastReceiver screenRx;
    private static PowerManager.WakeLock wake;

    private final Runnable beat = new Runnable() {
        public void run() {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                boolean on = pm != null && pm.isInteractive();
                LAST_BEAT = System.currentTimeMillis();
                enqueue("{\"ev\":\"beat\",\"ts\":" + LAST_BEAT + ",\"screen\":" + (on ? "true" : "false")
                        + ",\"q\":" + queueSize() + "}");
            } catch (Throwable ignored) {}
            if (hb != null) hb.postDelayed(this, 120000L);
        }
    };

    public static synchronized int queueSize() { return QUEUE.size(); }

    private static void wake() {
        try {
            if (wake == null) return;
            if (!wake.isHeld()) wake.acquire(3000);
        } catch (Throwable ignored) {}
    }

    public static synchronized void flush() {
        if (QUEUE.isEmpty()) return;
        FileOutputStream fos = null;
        OutputStreamWriter w = null;
        boolean ok = false;
        try {
            wake();
            File dir = new File(DIR);
            if (!dir.exists()) dir.mkdirs();
            fos = new FileOutputStream(FILE, true);
            w = new OutputStreamWriter(fos, "UTF-8");
            for (int i = 0; i < QUEUE.size(); i++) {
                w.write(QUEUE.get(i));
                w.write("\n");
            }
            w.flush();
            ok = true;
        } catch (Throwable ignored) {
        } finally {
            try { if (w != null) w.close(); } catch (Throwable ignored) {}
            try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
            if (ok) {
                QUEUE.clear();
                rotateIfNewDay();
                rotateIfBig();
            }
        }
    }

    private static void enqueue(String json) {
        synchronized (QUEUE) {
            QUEUE.add(json);
            if (QUEUE.size() > 300) QUEUE.remove(0);
        }
        flush();
    }

    private static String s(CharSequence c) {
        return c == null ? "" : c.toString().replace("\n", " ").trim();
    }

    private static String join(CharSequence[] arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(s(arr[i]));
        }
        return sb.toString();
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
                .replace("\r", " ").replace("\t", " ");
    }

    private static String msgsOf(Bundle ex, String key) {
        try {
            android.os.Parcelable[] arr = ex.getParcelableArray(key);
            if (arr == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length; i++) {
                if (!(arr[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) arr[i];
                Object snd = m.get("sender");
                String who = snd == null ? "" : String.valueOf(snd);
                String txt = s(m.getCharSequence("text"));
                long ts = m.getLong("time");
                if (sb.length() > 0) sb.append(" || ");
                sb.append(ts).append(" ").append(who).append(": ").append(txt);
            }
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    private static String ekeysOf(Bundle ex) {
        try {
            StringBuilder sb = new StringBuilder();
            Set<String> ks = ex.keySet();
            for (String k : ks) {
                Object v = ex.get(k);
                String t = v == null ? "null" : v.getClass().getSimpleName();
                if (v instanceof CharSequence) t = t + ":" + s((CharSequence) v).length();
                if (sb.length() > 0) sb.append(",");
                sb.append(k).append("=").append(t);
            }
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ALIVE = true;
        Config.init(this);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stepx:wxnotify");
                wake.setReferenceCounted(false);
            }
        } catch (Throwable ignored) {}

        hb = new Handler(Looper.getMainLooper());
        hb.postDelayed(beat, 4000L);

        try {
            screenRx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent it) {
                    boolean on = Intent.ACTION_SCREEN_ON.equals(it.getAction());
                    enqueue("{\"ev\":\"screen\",\"on\":" + (on ? "true" : "false")
                            + ",\"ts\":" + System.currentTimeMillis() + "}");
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenRx, f);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onListenerConnected() {
        ALIVE = true;
        enqueue("{\"ev\":\"connected\",\"ts\":" + System.currentTimeMillis() + "}");
    }

    @Override
    public void onListenerDisconnected() {
        enqueue("{\"ev\":\"disconnected\",\"ts\":" + System.currentTimeMillis() + "}");
    }

    @Override
    public void onDestroy() {
        ALIVE = false;
        try { if (screenRx != null) unregisterReceiver(screenRx); } catch (Throwable ignored) {}
        try { if (hb != null) hb.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
        hb = null;
        enqueue("{\"ev\":\"destroyed\",\"ts\":" + System.currentTimeMillis() + "}");
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null) return;
            if (!Config.notifyCollect()) return;
            String pkg = sbn.getPackageName();
            boolean isWx = Config.isFullApp(pkg);
            Notification n = sbn.getNotification();
            if (n == null || n.extras == null) return;
            Bundle ex = n.extras;

            String title = s(ex.getCharSequence(Notification.EXTRA_TITLE));
            String conv = s(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
            String text = s(ex.getCharSequence(Notification.EXTRA_TEXT));
            String big = s(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            String sub = s(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
            String lines = join(ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES));
            String body = big.length() > 0 ? big : text;

            if (!isWx) {
                COUNT++;
                LAST_TS = System.currentTimeMillis();
                enqueue("{\"ev\":\"any\",\"app\":\"" + esc(pkg) + "\",\"hasText\":"
                        + (body.length() > 0 ? "true" : "false") + ",\"ts\":" + LAST_TS + "}");
                return;
            }

            String fp = title + "|" + conv + "|" + body + "|" + sub + "|" + lines;
            String key = sbn.getKey() + "#" + Integer.toHexString(fp.hashCode());
            synchronized (SEEN) {
                if (!SEEN.add(key)) return;
                if (SEEN.size() > 500) SEEN.clear();
            }

            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("app", pkg);
            o.put("nkey", sbn.getKey());
            o.put("title", title);
            o.put("conv", conv);
            o.put("text", body);
            o.put("sub", sub);
            o.put("lines", lines);
            o.put("when", n.when);
            o.put("cat", n.category == null ? "" : n.category);
            o.put("msgs", msgsOf(ex, Notification.EXTRA_MESSAGES));
            o.put("hist", msgsOf(ex, "android.historicMessages"));
            COUNT++;
            LAST_TS = System.currentTimeMillis();
            enqueue(o.toString());
        } catch (Throwable e) {
            enqueue("{\"ev\":\"error\",\"msg\":\"" + esc(String.valueOf(e)) + "\",\"ts\":"
                    + System.currentTimeMillis() + "}");
        }
    }

    // 文件超过 4MB 就改名归档，避免无限增长
    private static void rotateIfBig() {
        try {
            File f = new File(FILE);
            if (f.length() < 4L * 1024 * 1024) return;
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                    .format(new java.util.Date());
            f.renameTo(new File(DIR + "/notify-" + ts + ".jsonl"));
        } catch (Throwable ignored) {}
    }

    private static String curDay = null;

    // 按天归档：跨天首次写入时把旧文件改名为 notify-YYYYMMDD.jsonl
    private static void rotateIfNewDay() {
        try {
            File f = new File(FILE);
            if (!f.exists()) return;
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
            String today = fmt.format(new java.util.Date());
            if (curDay == null) {
                String fd = firstLineDay();
                curDay = fd != null ? fd : fmt.format(new java.util.Date(f.lastModified()));
            }
            if (!today.equals(curDay)) {
                f.renameTo(new File(DIR + "/notify-" + curDay + ".jsonl"));
                curDay = today;
            }
        } catch (Throwable ignored) {}
    }

    // 用文件首行的时间戳判断这份文件属于哪一天（比 lastModified 可靠）
    private static String firstLineDay() {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(FILE), "UTF-8"));
            String l = br.readLine();
            br.close();
            if (l == null) return null;
            int i = l.indexOf("\"ts\":");
            if (i < 0) return null;
            String num = l.substring(i + 5).replaceAll("[^0-9].*$", "");
            long ts = Long.parseLong(num);
            return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date(ts));
        } catch (Throwable t) { return null; }
    }
}
