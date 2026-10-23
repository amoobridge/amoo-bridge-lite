package com.step.bridge;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/** 本地 HTTP 接口：比文件信箱快，端口只开在内网，必须带令牌 */
public class HttpBridge {
    public static final int DEFAULT_PORT = 8897;
    private static volatile int port = 0;
    private static volatile boolean running = false;
    private static volatile String token = "";
    private static ServerSocket server;

    public static int port() { return port; }

    public static java.io.File privateTokenFile() {
        try { return new java.io.File(Tools.ctxRef().getFilesDir(), "bridge-token"); } catch (Throwable t) { return null; }
    }

    public static String tokenFile() { return BridgeService.BRIDGE_DIR + "/token.txt"; }

    /** 每次启动轮换令牌；主副本在应用私有目录，按配置镜像到外部存储供本机脚本读取 */
    public static synchronized void rotateToken() {
        token = "";
        try {
            SecureRandom r = new SecureRandom();
            byte[] raw = new byte[32];
            r.nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte x : raw) sb.append(String.format("%02x", x));
            String t = sb.toString();
            java.io.File pf = privateTokenFile();
            if (pf != null) {
                pf.getParentFile().mkdirs();
                FileOutputStream fo = new FileOutputStream(pf);
                fo.write(t.getBytes("UTF-8"));
                fo.close();
            }
            if (Config.exportToken()) {
                File f2 = new File(tokenFile());
                File parent = f2.getParentFile();
                if (parent != null) parent.mkdirs();
                FileOutputStream fo2 = new FileOutputStream(f2);
                fo2.write(t.getBytes("UTF-8"));
                fo2.close();
            }
            token = t;
        } catch (Throwable ignored) {}
    }

    public static synchronized String ensureToken() {
        if (token.length() > 0) return token;
        try {
            java.io.File pf = privateTokenFile();
            if (pf != null && pf.exists()) {
                byte[] b = new byte[(int) pf.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(pf);
                in.read(b);
                in.close();
                token = new String(b, "UTF-8").trim();
            }
        } catch (Throwable ignored) {}
        if (token.length() < 8) rotateToken();
        return token;
    }

    public static synchronized void start(final int wantPort) {
        if (running) return;
        ensureToken();
        running = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    server = new ServerSocket(wantPort);
                    port = server.getLocalPort();
                    while (running) {
                        final Socket s = server.accept();
                        new Thread(new Runnable() {
                            public void run() { handle(s); }
                        }).start();
                    }
                } catch (Throwable t) {
                    running = false;
                    port = 0;
                }
            }
        }, "http-bridge").start();
    }

    public static synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) {}
        server = null;
        port = 0;
    }

    private static void handle(Socket s) {
        try {
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String reqLine = readLine(in);
            if (reqLine == null) { s.close(); return; }
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }
            int len = 0;
            try { len = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0"); } catch (Throwable ignored) {}
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int remain = len;
            while (remain > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, remain));
                if (n <= 0) break;
                bo.write(buf, 0, n);
                remain -= n;
            }
            String body = new String(bo.toByteArray(), "UTF-8");

            JSONObject resp = new JSONObject();
            int code = 200;
            String auth = headers.containsKey("authorization") ? headers.get("authorization") : "";
            if (path.startsWith("/pair")) {
                String pc = "";
                try {
                    JSONObject pr = new JSONObject(body.length() == 0 ? "{}" : body);
                    pc = pr.optString("code", "");
                } catch (Throwable ignored) {}
                if (Config.checkPairCode(pc)) {
                    resp.put("ok", true).put("token", ensureToken()).put("port", port);
                } else {
                    resp.put("ok", false).put("error", "pairing code invalid or expired");
                    code = 401;
                }
            } else if (!("Bearer " + ensureToken()).equals(auth)) {
                resp.put("ok", false).put("error", "unauthorized");
                code = 401;
            } else if (path.startsWith("/ping")) {
                resp.put("ok", true).put("pong", System.currentTimeMillis());
            } else if (path.startsWith("/status")) {
                resp.put("ok", true).put("up", running).put("port", port).put("ui", UiService.alive())
                    .put("tools", Tools.NAMES.length);
            } else if (Config.requireSign() && !sigValid(headers, body)) {
                resp.put("ok", false).put("error", "signature required: send X-Ts and X-Sig");
                code = 401;
            } else if (path.startsWith("/call")) {
                JSONObject req = new JSONObject(body.length() == 0 ? "{}" : body);
                String tool = req.optString("tool", "");
                JSONObject args = req.optJSONObject("args");
                if (args == null) args = new JSONObject();
                resp = Tools.dispatch(tool, args);
                resp.put("tool", tool);
                Config.audit(tool, resp.optBoolean("ok", false));
                if (headers.containsKey("x-sig")) resp.put("sig_ok", sigValid(headers, body));
            } else {
                resp.put("ok", false).put("error", "not found");
                code = 404;
            }

            byte[] outB = resp.toString().getBytes("UTF-8");
            String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERR") + "\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + outB.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.write(outB);
            out.flush();
            s.close();
        } catch (Throwable t) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static String readLine(InputStream in) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') bo.write(c);
                if (bo.size() > 8192) break;
            }
            if (bo.size() == 0 && c == -1) return null;
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- 请求签名（X-Ts + HMAC-SHA256(token, ts + \n + body)） ----------
    private static boolean sigValid(Map<String, String> h, String body) {
        try {
            String ts = h.get("x-ts");
            String sig = h.get("x-sig");
            if (ts == null || sig == null) return false;
            long t = Long.parseLong(ts.trim());
            if (Math.abs(System.currentTimeMillis() - t) > 60000L) return false;
            return hmac(ts.trim() + "\n" + body).equalsIgnoreCase(sig.trim());
        } catch (Throwable e) { return false; }
    }

    private static String hmac(String msg) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(ensureToken().getBytes("UTF-8"), "HmacSHA256"));
            byte[] out = mac.doFinal(msg.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }
}
