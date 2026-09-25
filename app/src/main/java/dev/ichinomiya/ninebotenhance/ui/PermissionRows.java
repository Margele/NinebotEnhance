package dev.ichinomiya.ninebotenhance.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.RippleDrawable;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.ichinomiya.ninebotenhance.core.PictureSource;
import dev.ichinomiya.ninebotenhance.core.PrivilegeMode;
import dev.ichinomiya.ninebotenhance.core.TouchPanel;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.notification.NotificationPreferences;
import dev.ichinomiya.ninebotenhance.platform.BlePermissions;
import dev.ichinomiya.ninebotenhance.privilege.PrivilegeManager;
import dev.ichinomiya.ninebotenhance.privilege.RootAuthorization;
import dev.ichinomiya.ninebotenhance.service.FrameBridgeService;
import dev.ichinomiya.ninebotenhance.service.TouchPanelUsb;

/**
 * The permission check: one row per grant the module relies on, its state, one line on what it is used for, and the request
 * or system page a tap on a missing one opens. Shared by the entry page and the first-run guide; both run in the module process.
 */
final class PermissionRows {
    static final int REQUEST_BLUETOOTH = 901, REQUEST_PHONE = 902;
    /** The one colour outside the palette: a grant that is missing. */
    private static final int MISSING = 0xffe06c5c;
    enum State { OK, MISSING, NEUTRAL }
    private final Activity activity; private final MirrorUi theme; private final LinearLayout list; private final Runnable refresh;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int gap;
    PermissionRows(Activity activity, MirrorUi theme, LinearLayout list, Runnable refresh) {
        this.activity = activity; this.theme = theme; this.list = list; this.refresh = refresh; gap = MirrorUi.dp(activity, 12);
    }
    /** Rebuilds every row; {@code all} adds the rows that only state a fact (screen capture and the install-time grants). */
    void fill(boolean all) {
        list.removeAllViews();
        main.removeCallbacksAndMessages(null);
        moduleRow();
        boolean notifications = new NotificationPreferences(activity).granted();
        row("通知使用权", notifications ? "已授权" : "未授权", notifications ? State.OK : State.MISSING,
                "读取手机通知，在仪表上显示通知卡片和音乐控件", notifications ? null : () -> AutostartPages.openNotificationAccess(activity));
        boolean bluetooth = BlePermissions.granted(activity);
        row("附近的设备", bluetooth ? "已授权" : "未授权", bluetooth ? State.OK : State.MISSING,
                "模块自己连接大灯控制器和 BMS 保护板，不用于车辆", bluetooth ? null : () -> activity.requestPermissions(BlePermissions.request(), REQUEST_BLUETOOTH));
        boolean phone = granted(Manifest.permission.READ_PHONE_STATE);
        row("电话状态", phone ? "已授权" : "未授权", phone ? State.OK : State.MISSING,
                "手机状态卡片的信号格数和网络制式", phone ? null : () -> activity.requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQUEST_PHONE));
        privilegeRow();
        row("自启动", "需手动确认", State.NEUTRAL, "九号进程要能拉起模块服务，HyperOS / ColorOS 等系统需允许自启动", () -> AutostartPages.open(activity, null));
        touchRow();
        if (!all) return;
        row("屏幕录制", "每次投屏时授权", State.NEUTRAL, "画面提供方式为「投屏」时用系统录屏采集画面", null);
        row("网络", "安装时授予", State.NEUTRAL, "向 GitHub 检查新版本，判断手机在 Wi-Fi 还是流量", null);
        row("查看全部应用", "安装时授予", State.NEUTRAL, "列出可投屏的应用和通知白名单", null);
        row("前台服务", "安装时授予", State.NEUTRAL, "系统录屏期间保持采集服务", null);
        row("修改音频设置", "安装时授予", State.NEUTRAL, "音量键调大灯后把媒体音量放回去", null);
    }
    void stop() { main.removeCallbacksAndMessages(null); }
    private void moduleRow() {
        long boundAt = 0;
        try { boundAt = activity.getSharedPreferences(FrameBridgeService.STATUS_PREFERENCES, Context.MODE_PRIVATE).getLong(FrameBridgeService.BOUND_AT, 0); } catch (RuntimeException ignored) {}
        boolean installed = activity.getPackageManager().getLaunchIntentForPackage(Protocol.TARGET) != null;
        String status = !installed ? "未安装九号出行" : boundAt > 0 ? "九号已连接，" + ago(boundAt) : "九号尚未连接";
        row("LSPosed 模块", status, boundAt > 0 ? State.OK : State.MISSING,
                "在 LSPosed 启用模块并勾选九号出行，九号启动时连接模块", installed && boundAt == 0 ? this::openNinebot : null);
    }
    private void privilegeRow() {
        PrivilegeMode mode = PrivilegeManager.mode(activity);
        PictureSource source = PrivilegeManager.source(activity);
        if (!source.virtual()) { row("Root / Shizuku", "画面提供方式为「" + source.label() + "」，不需要", State.NEUTRAL, "启动辅助进程建虚拟屏、注入触摸；投屏和绘制不需要", null); return; }
        Bundle status = PrivilegeManager.status(activity);
        boolean shizuku = status.getBoolean("privilege_granted"), root = status.getBoolean("root_ready"), pending = status.getBoolean("root_pending") || status.getBoolean("privilege_pending");
        String text = shizuku ? (status.getString("privilege_status", "").startsWith("Sui") ? "Sui 已授权" : "Shizuku 已授权") : root ? "Root 可用" : pending ? "检查中" : "未授权";
        row("Root / Shizuku", text, shizuku || root ? State.OK : pending ? State.NEUTRAL : State.MISSING,
                "启动辅助进程建虚拟屏、注入触摸；投屏和绘制不需要", shizuku || root ? null : () -> requestPrivilege(mode));
        if (pending) main.postDelayed(refresh, 1500);
    }
    private void requestPrivilege(PrivilegeMode mode) {
        try {
            if (mode == PrivilegeMode.SHIZUKU) PrivilegeManager.requestPermission();
            else RootAuthorization.request(activity);
        } catch (RuntimeException e) { ErrorDialog.show(activity, null, "申请授权失败", String.valueOf(e.getMessage())); }
        main.postDelayed(refresh, 1500);
    }
    private void touchRow() {
        TouchPanel panel = TouchSettingsActivity.read(activity);
        String purpose = "模块持有绑定的 USB 触摸屏，投屏期间面板不休眠";
        if (!panel.bound()) { row("USB 触摸屏", "未绑定", State.NEUTRAL, purpose, null); return; }
        UsbDevice device = TouchPanelUsb.find(activity, panel);
        if (device == null) { row("USB 触摸屏", "未接入", State.NEUTRAL, purpose, null); return; }
        boolean permitted = TouchPanelUsb.permitted(activity, device);
        row("USB 触摸屏", permitted ? "已授权" : "未授权", permitted ? State.OK : State.MISSING, purpose, permitted ? null : () -> TouchPanelUsb.request(activity, device));
    }
    private void row(String name, String status, State state, String purpose, Runnable fix) {
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(MirrorUi.dp(activity, 16), MirrorUi.dp(activity, 12), MirrorUi.dp(activity, 16), MirrorUi.dp(activity, 12));
        card.setBackground(fix == null ? theme.background(activity, theme.input, 14, false)
                : new RippleDrawable(android.content.res.ColorStateList.valueOf(0x22888899), theme.background(activity, theme.input, 14, false), null));
        LinearLayout head = new LinearLayout(activity); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(activity); label.setText(name); label.setTextSize(15); label.setTextColor(theme.text);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = new TextView(activity); value.setText(status); value.setTextSize(13);
        value.setTextColor(state == State.OK ? theme.accent : state == State.MISSING ? MISSING : theme.secondary);
        head.addView(value, new LinearLayout.LayoutParams(-2, -2));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = new TextView(activity); detail.setText(purpose); detail.setTextSize(13); detail.setTextColor(theme.secondary);
        detail.setPadding(0, MirrorUi.dp(activity, 4), 0, 0); card.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        if (fix != null) card.setOnClickListener(v -> fix.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = gap / 2;
        list.addView(card, params);
    }
    private boolean granted(String permission) { return activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60000);
        return minutes < 1 ? "刚刚" : minutes < 60 ? minutes + " 分钟前" : minutes < 1440 ? minutes / 60 + " 小时前" : minutes / 1440 + " 天前";
    }
    private void openNinebot() {
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(Protocol.TARGET);
        if (launch != null) { try { activity.startActivity(launch); } catch (RuntimeException ignored) {} }
    }
}
