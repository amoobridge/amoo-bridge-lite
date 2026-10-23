package com.step.bridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 运行配置。存放在应用私有目录，默认值按“最小可用权限”设定：
 * 随机端口、命令执行关闭、通知采集关闭。用户显式开启后才生效。
 */
public class Config {
    private static JSONObject j;
    private static File file;
    private static File auditFile;

    public static synchronized void init(Context c) {
        try {
            file = new File(c.getFilesDir(), "config.json");
            auditFile = new File(c.getFilesDir(), "audit.jsonl");
            if (file.exists()) {
                FileInputStream in = new FileInputStream(file);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                j = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            }
        } catch (Throwable ignored) {}
        if (j == null) {
            j = defaults();
            save();
        } else {
            // 升级后补齐新增配置项，保留用户已有设置
            boolean changed = false;
            JSONObject d = defaults();
            java.util.Iterator<String> it = d.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!j.has(k)) {
                    try { j.put(k, d.get(k)); changed = true; } catch (Throwable ignored) {}
                }
            }
            if (changed) save();
        }
    }

    private static JSONObject defaults() {
        JSONObject o = new JSONObject();
        try {
            o.put("port", 0);                        // 0 = 系统分配随机端口
            o.put("allowExec", false);               // 任意命令执行，默认关闭
            o.put("execWhitelist", new JSONArray().put("ls").put("cat").put("df").put("wc").put("date").put("id").put("stat"));
            o.put("notifyCollect", false);           // 通知采集，默认关闭
            o.put("exportToken", false);              // 令牌镜像到外部存储，供本机脚本读取（配对流程上线后改 false）
            o.put("audit", true);
            o.put("requireSign", false);
            o.put("fullApps", new JSONArray().put("com.tencent.mm"));                    // 审计日志
        } catch (Throwable ignored) {}
        return o;
    }

    private static synchronized void save() {
        try {
            if (file == null) return;
            FileOutputStream fo = new FileOutputStream(file, false);
            fo.write(j.toString(2).getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) {}
    }

    public static synchronized int port() {
        return j == null ? 0 : j.optInt("port", 0);
    }

    public static synchronized boolean allowExec() {
        return j != null && j.optBoolean("allowExec", false);
    }

    public static synchronized boolean execAllowed(String cmd) {
        if (!allowExec()) return false;
        JSONArray wl = j.optJSONArray("execWhitelist");
        if (wl == null || wl.length() == 0) return true;
        String first = cmd == null ? "" : cmd.trim().split("\\s+")[0];
        for (int i = 0; i < wl.length(); i++) {
            if (first.equals(wl.optString(i))) return true;
        }
        return false;
    }

    public static synchronized boolean notifyCollect() {
        return j != null && j.optBoolean("notifyCollect", false);
    }

    public static synchronized boolean exportToken() {
        return j != null && j.optBoolean("exportToken", false);
    }

    public static synchronized boolean auditOn() {
        return j != null && j.optBoolean("audit", true);
    }

    public static synchronized JSONObject all() {
        return j == null ? defaults() : j;
    }

    public static synchronized JSONObject set(String key, Object value) {
        try {
            if (j == null) j = defaults();
            j.put(key, value);
            save();
        } catch (Throwable ignored) {}
        return all();
    }

    /** 记一条审计：谁在何时调了什么工具、成败 */
    public static void audit(String tool, boolean ok) {
        if (!auditOn() || auditFile == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("tool", tool);
            o.put("ok", ok);
            FileOutputStream fo = new FileOutputStream(auditFile, true);
            fo.write((o.toString() + "\n").getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) {}
    }

    /** 哪些应用做全字段采集，其余只记一行探针 */
    public static synchronized boolean isFullApp(String pkg) {
        JSONArray a = (j == null) ? null : j.optJSONArray("fullApps");
        if (a == null) {
            return "com.tencent.mm".equals(pkg);   // 老配置没这个键时的默认行为
        }
        for (int i = 0; i < a.length(); i++) if (pkg != null && pkg.equals(a.optString(i))) return true;
        return false;
    }

    /** 读审计日志末尾 n 行 */
    public static synchronized String auditTail(int n) {
        StringBuilder sb = new StringBuilder();
        if (auditFile == null || !auditFile.exists()) return sb.toString();
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(auditFile), "UTF-8"));
            java.util.ArrayList<String> all = new java.util.ArrayList<String>();
            String l;
            while ((l = br.readLine()) != null) if (l.trim().length() > 0) all.add(l);
            br.close();
            int from = Math.max(0, all.size() - Math.max(1, n));
            for (int i = from; i < all.size(); i++) sb.append(all.get(i)).append("\n");
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    // ---------- 配对 ----------
    private static String pairCode = "";
    private static long pairExp = 0;

    /** 生成一次性配对码，有效期 120 秒 */
    public static synchronized String newPairCode() {
        try {
            java.security.SecureRandom r = new java.security.SecureRandom();
            pairCode = String.format("%06d", r.nextInt(1000000));
            pairExp = System.currentTimeMillis() + 120000L;
            return pairCode;
        } catch (Throwable t) { return ""; }
    }

    public static synchronized boolean checkPairCode(String c) {
        return c != null && pairCode.length() > 0 && pairCode.equals(c.trim())
                && System.currentTimeMillis() < pairExp;
    }

    public static synchronized boolean requireSign() {
        return j != null && j.optBoolean("requireSign", false);
    }
}
