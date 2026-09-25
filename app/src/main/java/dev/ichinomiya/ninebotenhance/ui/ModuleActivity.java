package dev.ichinomiya.ninebotenhance.ui;

import dev.ichinomiya.ninebotenhance.core.PictureSource;

import dev.ichinomiya.ninebotenhance.platform.BlePermissions;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.drawable.RippleDrawable;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.core.PrivilegeMode;
import dev.ichinomiya.ninebotenhance.core.TouchPanel;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.notification.NotificationPreferences;
import dev.ichinomiya.ninebotenhance.privilege.PrivilegeManager;
import dev.ichinomiya.ninebotenhance.privilege.RootAuthorization;
import dev.ichinomiya.ninebotenhance.service.FrameBridgeService;
import dev.ichinomiya.ninebotenhance.service.TouchPanelUsb;
import java.util.concurrent.TimeUnit;

/**
 * The launcher entry. It exists so the system lists the module in its autostart manager and treats it as an application the
 * user has opened: several ROMs refuse to let Ninebot bind the module service until then. The page is a permission check:
 * one row per grant the module relies on, with its state and what it is used for; a row that is not granted opens the request
 * or the system page when tapped. Below the list is the one action, force-stopping Ninebot so LSPosed injects the module on
 * its next start. Everything else stays inside the Ninebot vehicle page.
 */
public final class ModuleActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 901, REQUEST_PHONE = 902;
    /** The one colour outside the palette: a grant that is missing. */
    private static final int MISSING = 0xffe06c5c;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MirrorUi theme;
    private LinearLayout list;
    private Button stop;
    private int pad, gap;
    @Override protected void onCreate(Bundle saved) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        PrivilegeManager.initialize(this);
        theme = new MirrorUi(dark);
        pad = MirrorUi.dp(this, 20); gap = MirrorUi.dp(this, 12);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.surface); root.setForceDarkAllowed(false); root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); return insets; });
        getWindow().setDecorFitsSystemWindows(false);
        TextView title = new TextView(this); title.setText("Ninebot Enhance"); title.setTextSize(22); title.setTextColor(theme.text);
        title.setPadding(0, MirrorUi.dp(this, 6), 0, MirrorUi.dp(this, 6)); root.addView(title);
        TextView version = new TextView(this); version.setText("版本 " + Protocol.VERSION); version.setTextSize(14); version.setTextColor(theme.secondary);
        version.setPadding(0, 0, 0, gap); root.addView(version);
        TextView heading = new TextView(this); heading.setText("权限检测"); heading.setTextSize(16); heading.setTextColor(theme.text);
        heading.setPadding(0, gap / 2, 0, gap / 2); root.addView(heading);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); root.addView(list, new LinearLayout.LayoutParams(-1, -2));
        stop = new Button(this); stop.setText("强制退出九号出行"); theme.button(stop, null); stop.setTextSize(14); stop.setMaxLines(1);
        stop.setMinimumHeight(MirrorUi.dp(this, 52)); stop.setOnClickListener(v -> forceStop());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1, -2); stopParams.topMargin = gap * 2;
        root.addView(stop, stopParams);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); scroll.setBackgroundColor(theme.surface);
        setContentView(scroll);
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override public void onRequestPermissionsResult(int code, String[] names, int[] grants) { super.onRequestPermissionsResult(code, names, grants); refresh(); }
    // ---------------------------------------------------------------- the rows
    private void refresh() {
        list.removeAllViews();
        moduleRow();
        boolean notifications = new NotificationPreferences(this).granted();
        row("通知使用权", notifications ? "已授权" : "未授权", notifications ? State.OK : State.MISSING,
                "读取手机通知，在仪表上显示通知卡片和音乐控件", notifications ? null : () -> AutostartPages.openNotificationAccess(this));
        boolean bluetooth = BlePermissions.granted(this);
        row("附近的设备", bluetooth ? "已授权" : "未授权", bluetooth ? State.OK : State.MISSING,
                "模块自己连接大灯控制器和 BMS 保护板，不用于车辆", bluetooth ? null
                : () -> requestPermissions(BlePermissions.request(), REQUEST_BLUETOOTH));
        boolean phone = granted(Manifest.permission.READ_PHONE_STATE);
        row("电话状态", phone ? "已授权" : "未授权", phone ? State.OK : State.MISSING,
                "手机状态卡片的信号格数和网络制式", phone ? null : () -> requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQUEST_PHONE));
        privilegeRow();
        row("自启动", "需手动确认", State.NEUTRAL, "九号进程要能拉起模块服务，HyperOS / ColorOS 等系统需允许自启动", () -> AutostartPages.open(this, null));
        touchRow();
        row("屏幕录制", "每次投屏时授权", State.NEUTRAL, "画面提供方式为「投屏」时用系统录屏采集画面", null);
        row("网络", "安装时授予", State.NEUTRAL, "向 GitHub 检查新版本，判断手机在 Wi-Fi 还是流量", null);
        row("查看全部应用", "安装时授予", State.NEUTRAL, "列出可投屏的应用和通知白名单", null);
        row("前台服务", "安装时授予", State.NEUTRAL, "系统录屏期间保持采集服务", null);
        row("修改音频设置", "安装时授予", State.NEUTRAL, "音量键调大灯后把媒体音量放回去", null);
    }
    private void moduleRow() {
        long boundAt = 0;
        try { boundAt = getSharedPreferences(FrameBridgeService.STATUS_PREFERENCES, MODE_PRIVATE).getLong(FrameBridgeService.BOUND_AT, 0); } catch (RuntimeException ignored) {}
        boolean installed = getPackageManager().getLaunchIntentForPackage(Protocol.TARGET) != null;
        String status = !installed ? "未安装九号出行" : boundAt > 0 ? "九号已连接，" + ago(boundAt) : "九号尚未连接";
        row("LSPosed 模块", status, boundAt > 0 ? State.OK : State.MISSING,
                "在 LSPosed 启用模块并勾选九号出行，九号启动时连接模块", installed && boundAt == 0 ? this::openNinebot : null);
    }
    private void privilegeRow() {
        PrivilegeMode mode = PrivilegeManager.mode(this);
        PictureSource source = PrivilegeManager.source(this);
        if (!source.virtual()) { row("Root / Shizuku", "画面提供方式为「" + source.label() + "」，不需要", State.NEUTRAL, "启动辅助进程建虚拟屏、注入触摸；投屏和绘制不需要", null); return; }
        Bundle status = PrivilegeManager.status(this);
        boolean shizuku = status.getBoolean("privilege_granted"), root = status.getBoolean("root_ready"), pending = status.getBoolean("root_pending") || status.getBoolean("privilege_pending");
        String text = shizuku ? (status.getString("privilege_status", "").startsWith("Sui") ? "Sui 已授权" : "Shizuku 已授权") : root ? "Root 可用" : pending ? "检查中" : "未授权";
        row("Root / Shizuku", text, shizuku || root ? State.OK : pending ? State.NEUTRAL : State.MISSING,
                "启动辅助进程建虚拟屏、注入触摸；投屏和绘制不需要", shizuku || root ? null : () -> requestPrivilege(mode, status));
        if (pending) main.postDelayed(this::refresh, 1500);
    }
    private void requestPrivilege(PrivilegeMode mode, Bundle status) {
        try {
            if (mode == PrivilegeMode.SHIZUKU) PrivilegeManager.requestPermission();
            else RootAuthorization.request(this);
        } catch (RuntimeException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
        main.postDelayed(this::refresh, 1500);
    }
    private void touchRow() {
        TouchPanel panel = TouchSettingsActivity.read(this);
        String purpose = "模块持有绑定的 USB 触摸屏，投屏期间面板不休眠";
        if (!panel.bound()) { row("USB 触摸屏", "未绑定", State.NEUTRAL, purpose, null); return; }
        UsbDevice device = TouchPanelUsb.find(this, panel);
        if (device == null) { row("USB 触摸屏", "未接入", State.NEUTRAL, purpose, null); return; }
        boolean permitted = TouchPanelUsb.permitted(this, device);
        row("USB 触摸屏", permitted ? "已授权" : "未授权", permitted ? State.OK : State.MISSING, purpose, permitted ? null : () -> TouchPanelUsb.request(this, device));
    }
    private enum State { OK, MISSING, NEUTRAL }
    private void row(String name, String status, State state, String purpose, Runnable fix) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(MirrorUi.dp(this, 16), MirrorUi.dp(this, 12), MirrorUi.dp(this, 16), MirrorUi.dp(this, 12));
        card.setBackground(fix == null ? theme.background(this, theme.input, 14, false)
                : new RippleDrawable(android.content.res.ColorStateList.valueOf(0x22888899), theme.background(this, theme.input, 14, false), null));
        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this); label.setText(name); label.setTextSize(15); label.setTextColor(theme.text);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = new TextView(this); value.setText(status); value.setTextSize(13);
        value.setTextColor(state == State.OK ? theme.accent : state == State.MISSING ? MISSING : theme.secondary);
        head.addView(value, new LinearLayout.LayoutParams(-2, -2));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = new TextView(this); detail.setText(purpose); detail.setTextSize(13); detail.setTextColor(theme.secondary);
        detail.setPadding(0, MirrorUi.dp(this, 4), 0, 0); card.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        if (fix != null) card.setOnClickListener(v -> fix.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = gap / 2;
        list.addView(card, params);
    }
    private boolean granted(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60000);
        return minutes < 1 ? "刚刚" : minutes < 60 ? minutes + " 分钟前" : minutes < 1440 ? minutes / 60 + " 小时前" : minutes / 1440 + " 天前";
    }
    private void openNinebot() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(Protocol.TARGET);
        if (launch != null) { try { startActivity(launch); } catch (RuntimeException ignored) {} }
    }
    // ---------------------------------------------------------------- force stop
    /** Shizuku / Sui first, then su; without either the system's own page for Ninebot is the place to stop it. */
    private void forceStop() {
        if (getPackageManager().getLaunchIntentForPackage(Protocol.TARGET) == null) { Toast.makeText(this, "未安装九号出行", Toast.LENGTH_SHORT).show(); return; }
        stop.setEnabled(false);
        new Thread(() -> {
            boolean done = false;
            try { if (PrivilegeManager.status(this).getBoolean("privilege_granted")) done = PrivilegeManager.forceStopTarget(this) == 0; } catch (Exception ignored) {}
            if (!done) done = stopWithSu();
            boolean stopped = done;
            main.post(() -> {
                stop.setEnabled(true);
                if (stopped) { Toast.makeText(this, "已强制退出九号出行", Toast.LENGTH_SHORT).show(); return; }
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + Protocol.TARGET)));
                    Toast.makeText(this, "请在页面里点「强制停止」", Toast.LENGTH_LONG).show();
                } catch (RuntimeException e) { Toast.makeText(this, "无法强制退出九号出行", Toast.LENGTH_SHORT).show(); }
            });
        }, "Enhance-ForceStop").start();
    }
    private static boolean stopWithSu() {
        try {
            Process process = new ProcessBuilder("su", "-c", "am force-stop " + Protocol.TARGET).redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroy(); return false; }
            return process.exitValue() == 0;
        } catch (Exception e) { return false; }
    }
}
