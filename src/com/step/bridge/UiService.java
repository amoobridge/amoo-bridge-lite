package com.step.bridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UiService extends AccessibilityService {
    public static final int GLOBAL_BACK = GLOBAL_ACTION_BACK;
    public static final int GLOBAL_HOME = GLOBAL_ACTION_HOME;
    public static final int GLOBAL_RECENTS = GLOBAL_ACTION_RECENTS;

    private static volatile UiService inst;

    public static boolean alive() { return inst != null; }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    private static JSONObject err(String m) {
        JSONObject r = new JSONObject();
        try { r.put("ok", false).put("error", m); } catch (Throwable ignored) {}
        return r;
    }

    // ---------- 读界面 ----------

    public static JSONObject dump() {
        UiService s = inst;
        if (s == null) return err("无障碍服务未开启");
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return err("当前窗口取不到节点");
            JSONArray arr = new JSONArray();
            walk(root, arr, 0);
            JSONObject r = new JSONObject();
            r.put("ok", true).put("count", arr.length()).put("nodes", arr);
            r.put("package", root.getPackageName() == null ? "" : root.getPackageName().toString());
            return r;
        } catch (Throwable t) {
            return err(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void walk(AccessibilityNodeInfo n, JSONArray out, int depth) {
        if (n == null || out.length() >= 400) return;
        try {
            String text = n.getText() == null ? "" : n.getText().toString();
            String desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
            if (text.length() > 0 || desc.length() > 0 || n.isClickable()) {
                Rect b = new Rect();
                n.getBoundsInScreen(b);
                JSONObject o = new JSONObject();
                o.put("text", text).put("desc", desc)
                 .put("cls", n.getClassName() == null ? "" : n.getClassName().toString())
                 .put("clickable", n.isClickable())
                 .put("bounds", new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
                 .put("depth", depth);
                out.put(o);
            }
            for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, depth + 1);
        } catch (Throwable ignored) {}
    }

    // ---------- 手势与截图（对外分发版不提供实现） ----------
    //
    // 历史实现：tap / longPress / swipe / clickText / grab / screenshot / shotBase64。
    // 移除原因：界面点按依赖坐标推断，缺少系统级稳定接口；截图回传与视觉兜底同理。
    // 策略：暂不开源，等安卓底层跟进（系统级屏幕理解、AppFunctions 等正式接口）后再评估。
    // 保留调用方接口约定：Tools 中对应工具名为空实现，返回 code=NOT_YET。

    // ---------- 全量诊断 dump（不过滤，判断到底是剪枝还是自绘） ----------

    private static String pkgOf(AccessibilityNodeInfo n) {
        try { return n.getPackageName() == null ? "" : n.getPackageName().toString(); }
        catch (Throwable t) { return ""; }
    }

    private static void walkRaw(AccessibilityNodeInfo n, JSONArray out, int depth, int[] budget) {
        if (n == null || budget[0] <= 0) return;
        budget[0]--;
        try {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            JSONObject o = new JSONObject();
            o.put("d", depth);
            o.put("cls", n.getClassName() == null ? "" : n.getClassName().toString());
            o.put("pkg", pkgOf(n));
            o.put("text", n.getText() == null ? "" : n.getText().toString());
            o.put("desc", n.getContentDescription() == null ? "" : n.getContentDescription().toString());
            o.put("click", n.isClickable());
            o.put("vis", n.isVisibleToUser());
            o.put("cc", n.getChildCount());
            o.put("b", new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom));
            out.put(o);
            for (int i = 0; i < n.getChildCount(); i++) walkRaw(n.getChild(i), out, depth + 1, budget);
        } catch (Throwable ignored) {}
    }

    public static JSONObject dumpRaw() {
        UiService s = inst;
        if (s == null) return err("无障碍服务未开启");
        try {
            JSONObject root = new JSONObject();
            JSONArray winsMeta = new JSONArray();
            JSONArray allNodes = new JSONArray();
            int[] budget = new int[]{6000};
            int textNodes = 0, clickNodes = 0, maxDepth = 0;

            java.util.List<android.view.accessibility.AccessibilityWindowInfo> list = s.getWindows();
            if (list != null) {
                for (int wi = 0; wi < list.size(); wi++) {
                    android.view.accessibility.AccessibilityWindowInfo w = list.get(wi);
                    JSONObject wm = new JSONObject();
                    AccessibilityNodeInfo wr = w.getRoot();
                    wm.put("id", w.getId());
                    wm.put("type", w.getType());
                    wm.put("active", w.isActive());
                    wm.put("focused", w.isFocused());
                    wm.put("rootNull", wr == null);
                    wm.put("pkg", wr == null ? "" : pkgOf(wr));
                    JSONArray nodes = new JSONArray();
                    if (wr != null) walkRaw(wr, nodes, 0, budget);
                    wm.put("count", nodes.length());
                    winsMeta.put(wm);
                    for (int k = 0; k < nodes.length(); k++) {
                        JSONObject o = nodes.getJSONObject(k);
                        allNodes.put(o);
                        if (o.optString("text").length() > 0 || o.optString("desc").length() > 0) textNodes++;
                        if (o.optBoolean("click")) clickNodes++;
                        if (o.optInt("d") > maxDepth) maxDepth = o.optInt("d");
                    }
                }
            }

            AccessibilityNodeInfo act = s.getRootInActiveWindow();
            root.put("activePkg", act == null ? "" : pkgOf(act));
            root.put("activeNull", act == null);
            root.put("total", allNodes.length());
            root.put("textNodes", textNodes);
            root.put("clickNodes", clickNodes);
            root.put("maxDepth", maxDepth);
            root.put("windows", winsMeta);
            root.put("nodes", allNodes);

            String path = BridgeService.BRIDGE_DIR + "/ui_dump_raw.json";
            FileOutputStream fo = new FileOutputStream(path, false);
            fo.write(root.toString().getBytes("UTF-8"));
            fo.close();

            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("file", path);
            r.put("total", allNodes.length());
            r.put("textNodes", textNodes);
            r.put("clickNodes", clickNodes);
            r.put("maxDepth", maxDepth);
            r.put("activePkg", act == null ? "" : pkgOf(act));
            r.put("windows", winsMeta);
            return r;
        } catch (Throwable t) {
            return err(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // 系统级导航（返回 / 桌面 / 多任务）：不属于界面点按，保留
    public static JSONObject global(int what) {
        UiService s = inst;
        if (s == null) return err("无障碍服务未开启");
        try {
            return new JSONObject().put("ok", s.performGlobalAction(what));
        } catch (Throwable t) {
            return err(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
