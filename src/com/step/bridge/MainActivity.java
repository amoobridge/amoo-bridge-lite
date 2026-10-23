package com.step.bridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private LinearLayout permBox;
    private TextView tvBadge, tvRun, tvPermSummary, tvPair, tvToken, tvTools, tvMetrics, tvLogs, tvHelp;
    private long pairExpire = 0;
    private String pairCode = "";
    private boolean tokenShown = false;
    private boolean toolsOpen = false;
    private boolean helpOpen = false;
    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() {
        public void run() { refresh(); h.postDelayed(this, 2000); }
    };

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        root.setBackgroundColor(0xFF0E1117);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(22);
        title.setTextColor(0xFF7EE0C0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvBadge = new TextView(this);
        tvBadge.setTextSize(13);
        tvBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        head.addView(tvBadge);
        root.addView(head);

        LinearLayout c1 = card("运行状态");
        tvRun = new TextView(this);
        tvRun.setTextSize(14);
        tvRun.setTextColor(0xFFC9D3E1);
        tvRun.setLineSpacing(dp(3), 1f);
        c1.addView(tvRun);
        c1.addView(buttonRow(new String[]{"重启桥", "停止"}, new View.OnClickListener[]{
                new View.OnClickListener() { public void onClick(View v) { startBridge(); refresh(); } },
                new View.OnClickListener() { public void onClick(View v) {
                    HttpBridge.stop();
                    stopService(new Intent(MainActivity.this, BridgeService.class));
                    refresh();
                } }
        }));
        root.addView(c1);

        LinearLayout c2 = card("权限体检");
        tvPermSummary = new TextView(this);
        tvPermSummary.setTextSize(13);
        tvPermSummary.setTextColor(0xFF8B93A7);
        c2.addView(tvPermSummary);
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        c2.addView(permBox);
        root.addView(c2);

        LinearLayout c3 = card("配对与令牌");
        tvPair = new TextView(this);
        tvPair.setTextSize(13);
        tvPair.setTextColor(0xFF8B93A7);
        c3.addView(tvPair);
        tvToken = new TextView(this);
        tvToken.setTextSize(12);
        tvToken.setTextColor(0xFF8B93A7);
        tvToken.setPadding(0, dp(6), 0, dp(6));
        c3.addView(tvToken);
        c3.addView(buttonRow(new String[]{"生成配对码", "显示令牌", "复制令牌"}, new View.OnClickListener[]{
                new View.OnClickListener() { public void onClick(View v) {
                    pairCode = Config.newPairCode();
                    pairExpire = System.currentTimeMillis() + 120000L;
                    refresh();
                } },
                new View.OnClickListener() { public void onClick(View v) { tokenShown = !tokenShown; refresh(); } },
                new View.OnClickListener() { public void onClick(View v) { copyToken(); } }
        }));
        root.addView(c3);

        LinearLayout c4 = card("工具清单");
        tvTools = new TextView(this);
        tvTools.setTextSize(13);
        tvTools.setTextColor(0xFFC9D3E1);
        tvTools.setLineSpacing(dp(3), 1f);
        c4.addView(tvTools);
        c4.addView(buttonRow(new String[]{"展开 / 收起"}, new View.OnClickListener[]{
                new View.OnClickListener() { public void onClick(View v) { toolsOpen = !toolsOpen; refresh(); } }
        }));
        root.addView(c4);

        LinearLayout c5 = card("运行指标");
        tvMetrics = new TextView(this);
        tvMetrics.setTextSize(13);
        tvMetrics.setTextColor(0xFFC9D3E1);
        tvMetrics.setLineSpacing(dp(3), 1f);
        c5.addView(tvMetrics);
        root.addView(c5);

        LinearLayout c6 = card("最近调用");
        tvLogs = new TextView(this);
        tvLogs.setTextSize(11);
        tvLogs.setTextColor(0xFF8B93A7);
        tvLogs.setLineSpacing(dp(2), 1f);
        c6.addView(tvLogs);
        c6.addView(buttonRow(new String[]{"导出诊断包"}, new View.OnClickListener[]{
                new View.OnClickListener() { public void onClick(View v) { exportDiag(); } }
        }));
        root.addView(c6);

        LinearLayout c7 = card("接入方式");
        tvHelp = new TextView(this);
        tvHelp.setTextSize(12);
        tvHelp.setTextColor(0xFF8B93A7);
        tvHelp.setLineSpacing(dp(3), 1f);
        c7.addView(tvHelp);
        c7.addView(buttonRow(new String[]{"展开 / 收起"}, new View.OnClickListener[]{
                new View.OnClickListener() { public void onClick(View v) { helpOpen = !helpOpen; refresh(); } }
        }));
        root.addView(c7);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF0E1117);
        sc.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sc);

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        startBridge();
        refresh();
    }

    private LinearLayout card(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF161A22);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xFF243043);
        box.setBackground(bg);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(12);
        t.setTextColor(0xFF7EE0C0);
        t.setPadding(0, 0, 0, dp(8));
        box.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        box.setLayoutParams(lp);
        return box;
    }

    private Button small(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout buttonRow(String[] labels, View.OnClickListener[] ls) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < labels.length; i++) {
            Button b = small(labels[i], ls[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            row.addView(b, lp);
        }
        return row;
    }

    private View permRow(final String name, final boolean ok, final Runnable jump) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        TextView dot = new TextView(this);
        dot.setText(ok ? "●" : "○");
        dot.setTextSize(15);
        dot.setTextColor(ok ? 0xFF4CD19B : 0xFFFFB454);
        dot.setPadding(0, 0, dp(8), 0);
        row.addView(dot);
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextSize(14);
        tv.setTextColor(0xFFE8ECF4);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = new TextView(this);
        state.setText(ok ? "已完成" : "去开启 ›");
        state.setTextSize(12);
        state.setTextColor(ok ? 0xFF6F778C : 0xFFFFB454);
        row.addView(state);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (jump != null) jump.run(); }
        });
        return row;
    }

    private void refresh() {
        boolean running = HttpBridge.port() > 0;
        tvBadge.setText(running ? "● 运行中" : "○ 已停止");
        tvBadge.setTextColor(running ? 0xFF4CD19B : 0xFF8B93A7);

        long beat = beatAgeMs();
        tvRun.setText("版本 " + versionName() + "   端口 " + (running ? String.valueOf(HttpBridge.port()) : "—")
                + "\n心跳 " + (beat < 0 ? "无" : ((beat / 1000) + " 秒前"))
                + "   目录 " + BridgeService.BRIDGE_DIR);

        boolean allFiles = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        boolean acc = UiService.alive();
        boolean notifyOn = notifyGranted();
        boolean battery = batteryIgnored();
        boolean writeSet = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this);
        int ok = (allFiles ? 1 : 0) + (acc ? 1 : 0) + (notifyOn ? 1 : 0) + (battery ? 1 : 0) + (writeSet ? 1 : 0);
        tvPermSummary.setText("已就绪 " + ok + "/5　点未完成的项可直接跳转");
        permBox.removeAllViews();
        permBox.addView(permRow("所有文件访问", allFiles, new Runnable() { public void run() { openAllFiles(); } }));
        permBox.addView(permRow("无障碍服务", acc, new Runnable() { public void run() { openAccessibility(); } }));
        permBox.addView(permRow("通知使用权", notifyOn, new Runnable() { public void run() { openNotifyAccess(); } }));
        permBox.addView(permRow("后台常驻（电池）", battery, new Runnable() { public void run() { openBattery(); } }));
        permBox.addView(permRow("修改系统设置", writeSet, new Runnable() { public void run() { openWriteSettings(); } }));

        long left = (pairExpire - System.currentTimeMillis()) / 1000;
        if (pairCode.length() > 0 && left >= 0) {
            tvPair.setTextSize(16);
            tvPair.setTextColor(0xFFFFD166);
            tvPair.setText("配对码 " + pairCode + "   剩余 " + left + " 秒");
        } else {
            tvPair.setTextSize(13);
            tvPair.setTextColor(0xFF8B93A7);
            tvPair.setText("未生成。生成后 120 秒内有效，用于给新调用方换取令牌。");
        }

        String token = HttpBridge.ensureToken();
        tvToken.setText(tokenShown ? ("令牌 " + token) : ("令牌 " + mask(token) + "   （点「显示令牌」查看）"));

        tvTools.setText(toolsOpen ? toolsDetail() : toolsSummary());
        tvMetrics.setText(metricsText());
        tvLogs.setText(logTail(5));
        tvHelp.setText(helpOpen ? helpDetail() : "HTTP 调用、文件信箱、签名方式…（点展开）");
    }

    private String mask(String t) {
        if (t.length() <= 10) return t;
        return t.substring(0, 4) + "…" + t.substring(t.length() - 4);
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable t) { return "unknown"; }
    }

    private boolean notifyGranted() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(getPackageName());
        } catch (Throwable t) { return false; }
    }

    private boolean batteryIgnored() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) { return false; }
    }

    private long beatAgeMs() {
        try {
            File f = new File(BridgeService.BRIDGE_DIR, "status.json");
            if (!f.exists()) return -1;
            return System.currentTimeMillis() - f.lastModified();
        } catch (Throwable t) { return -1; }
    }

    private String toolsSummary() {
        return "共 " + Tools.NAMES.length + " 项工具，点展开查看分组明细。";
    }

    private String toolsDetail() {
        StringBuilder sb = new StringBuilder();
        sb.append("文件：exec ls read write mkdir rm mv cp stat\n");
        sb.append("应用：start_app open_url share_text list_apps\n");
        sb.append("界面：ui_dump ui_dump_raw ui_back ui_home ui_recents\n");
        sb.append("通知：notify_status open_notify_access\n");
        sb.append("系统：clipboard toast notify battery volume brightness vibrate perm\n");
        sb.append("配置：config_get config_set audit_tail battery_allow\n\n");
        sb.append("合计 " + Tools.NAMES.length + " 项\n\n");
        if (!java.util.Arrays.asList(Tools.NAMES).contains("ui_tap")) {
            sb.append("本版不提供：界面点按 / 长按 / 滑动 / 截图 / 界面取词（调用返回 NOT_YET）");
        }
        return sb.toString();
    }

    private String metricsText() {
        int msgToday = countToday(new File(BridgeService.BRIDGE_DIR, "notify.jsonl"), true);
        int callToday = countToday(new File(getFilesDir(), "audit.jsonl"), false);
        return "今日消息 " + msgToday + " 条   今日调用 " + callToday + " 次\n最近调用 " + lastTool();
    }

    private int countToday(File f, boolean msgOnly) {
        if (!f.exists()) return 0;
        BufferedReader br = null;
        try {
            String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String l;
            int n = 0;
            while ((l = br.readLine()) != null) {
                if (l.length() == 0) continue;
                if (msgOnly && l.indexOf("title") < 0) continue;
                int i = l.indexOf("ts");
                if (i < 0) continue;
                int c = l.indexOf(':', i);
                if (c < 0) continue;
                int e = c + 1;
                while (e < l.length() && Character.isDigit(l.charAt(e))) e++;
                String num = l.substring(c + 1, e);
                if (num.length() == 0) continue;
                String d = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(Long.parseLong(num)));
                if (today.equals(d)) n++;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    private String lastTool() {
        try {
            String tail = Config.auditTail(1).trim();
            if (tail.length() == 0) return "—";
            int i = tail.indexOf("\"tool\":\"");
            if (i < 0) return "—";
            int a = i + 8;
            int b = tail.indexOf('"', a);
            if (b <= a) return "—";
            return tail.substring(a, b);
        } catch (Throwable t) { return "—"; }
    }

    private String logTail(int n) {
        String s = Config.auditTail(n);
        if (s.trim().length() == 0) return "暂无调用记录。";
        return s.replace("{", "").replace("}", "").replace("\"", "").trim();
    }

    private String helpDetail() {
        return "1) 本机 HTTP\nPOST http://127.0.0.1:<端口>/call\nAuthorization: Bearer <令牌>\nX-Ts: 毫秒时间戳\nX-Sig: HMAC-SHA256(令牌, 时间戳+换行+请求体)\n\n"
                + "2) 文件信箱\n" + BridgeService.BRIDGE_DIR + "（cmd_<id>.json 进，result_<id>.json 出）\n\n"
                + "3) 端口见 status.json，令牌点上面「显示令牌」";
    }

    private void copyToken() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("bridge-token", HttpBridge.ensureToken()));
            Toast.makeText(this, "令牌已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportDiag() {
        ZipOutputStream zos = null;
        try {
            String ts = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
            File out = new File(BridgeService.BRIDGE_DIR, "diag-" + ts + ".zip");
            zos = new ZipOutputStream(new FileOutputStream(out));
            zos.putNextEntry(new ZipEntry("meta.txt"));
            zos.write(("app=" + getPackageName() + "\nversion=" + versionName()
                    + "\nport=" + HttpBridge.port() + "\n").getBytes("UTF-8"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("config.json"));
            zos.write(Config.all().toString(2).getBytes("UTF-8"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("audit-tail.jsonl"));
            zos.write(Config.auditTail(200).getBytes("UTF-8"));
            zos.closeEntry();
            zos.close();
            zos = null;
            Toast.makeText(this, "诊断包已导出到 " + out.getName(), Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败", Toast.LENGTH_LONG).show();
        } finally {
            try { if (zos != null) zos.close(); } catch (Throwable ignored) {}
        }
    }

    private void openAllFiles() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
        } catch (Throwable ignored) {}
        openDetails();
    }

    private void openAccessibility() {
        try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Throwable ignored) {}
    }

    private void openNotifyAccess() {
        try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); } catch (Throwable ignored) {}
    }

    private void openBattery() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
        } catch (Throwable ignored) {}
        openDetails();
    }

    private void openWriteSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
        } catch (Throwable ignored) {}
        openDetails();
    }

    private void openDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {}
    }

    private void startBridge() {
        Intent i = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        WatchdogReceiver.schedule(this);
        BridgeJobService.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        h.removeCallbacks(tick);
        h.postDelayed(tick, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }
}
