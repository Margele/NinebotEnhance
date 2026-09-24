package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.PrivilegeMode;

/** Authorization UI stays responsive and observes module-process permission callbacks via status reads. */
public final class PrivilegeDialog {
    public static void show(Activity activity, FrameClient frames, View reference) {
        show(activity, frames, reference, value -> {});
    }
    public static void show(Activity activity, FrameClient frames, View reference, java.util.function.Consumer<PrivilegeMode> saved) {
        MirrorUi theme = new MirrorUi(activity, reference);
        int pad = MirrorUi.dp(activity, 20), gap = MirrorUi.dp(activity, 12);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, gap, pad, pad);
        content.setBackgroundColor(theme.surface);

        TextView explanation = new TextView(activity);
        explanation.setText("自动模式优先使用已授权的 Shizuku / Sui，其次检查 Root。已经授予 Root 权限时，开始投屏会自动连接，无需每次手动验证。");
        explanation.setTextColor(theme.secondary); explanation.setTextSize(14);
        explanation.setLineSpacing(MirrorUi.dp(activity, 3), 1);
        LinearLayout.LayoutParams explanationParams = new LinearLayout.LayoutParams(-1, -2);
        explanationParams.bottomMargin = gap;
        content.addView(explanation, explanationParams);

        Spinner mode = new ChoiceSpinner(activity, theme, "选择授权方式");
        mode.setAdapter(new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"自动", "Root", "Shizuku / Sui", "无（投屏）"}) {
            private View row(int position, View convertView) {
                TextView text = convertView instanceof TextView ? (TextView) convertView : new TextView(activity);
                text.setText(getItem(position)); text.setTextColor(theme.text); text.setTextSize(15);
                text.setGravity(android.view.Gravity.CENTER_VERTICAL);
                text.setPadding(gap, gap, gap, gap); text.setMinHeight(MirrorUi.dp(activity, 48));
                text.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                return text;
            }
            @Override public View getView(int position, View view, ViewGroup parent) { return row(position, view); }
            @Override public View getDropDownView(int position, View view, ViewGroup parent) { return row(position, view); }
        });
        mode.setBackground(theme.background(activity, theme.input, 14, false));
        mode.setClipToOutline(true); mode.setEnabled(false);
        content.addView(mode, new LinearLayout.LayoutParams(-1, -2));
        CheckBox keepRoot = new CheckBox(activity); keepRoot.setText("不降权"); keepRoot.setTextColor(theme.text); keepRoot.setTextSize(15);
        keepRoot.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent)); keepRoot.setEnabled(false);
        keepRoot.setPadding(0, MirrorUi.dp(activity, 8), 0, MirrorUi.dp(activity, 8));
        LinearLayout.LayoutParams keepParams = new LinearLayout.LayoutParams(-1, -2); keepParams.topMargin = gap / 2;
        content.addView(keepRoot, keepParams);

        TextView status = new TextView(activity);
        status.setText("正在连接模块并读取授权状态…");
        status.setTextColor(theme.secondary); status.setTextSize(13);
        status.setLineSpacing(MirrorUi.dp(activity, 3), 1);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = gap; statusParams.bottomMargin = gap;
        content.addView(status, statusParams);

        Button grant = new Button(activity); grant.setText("授权 Shizuku / Sui");
        theme.button(grant, null); grant.setMinHeight(MirrorUi.dp(activity, 48)); grant.setEnabled(false);
        content.addView(grant, new LinearLayout.LayoutParams(-1, -2));
        Button rootGrant = new Button(activity); rootGrant.setText("申请 / 验证 Root 权限");
        theme.button(rootGrant, null); rootGrant.setMinHeight(MirrorUi.dp(activity, 48)); rootGrant.setEnabled(false);
        LinearLayout.LayoutParams rootParams = new LinearLayout.LayoutParams(-1, -2); rootParams.topMargin = gap;
        content.addView(rootGrant, rootParams);
        Button refresh = new Button(activity); refresh.setText("刷新服务状态");
        theme.button(refresh, null); refresh.setMinHeight(MirrorUi.dp(activity, 48));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-1, -2);
        refreshParams.topMargin = gap;
        content.addView(refresh, refreshParams);

        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        TextView title = new TextView(activity); title.setText("授权方式");
        title.setTextColor(theme.text); title.setTextSize(20); title.setPadding(pad, pad, pad, gap / 2);
        AlertDialog dialog = new AlertDialog.Builder(activity).setCustomTitle(title).setView(scroll)
                .setPositiveButton("保存", null).setNegativeButton("关闭", null).create();
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 24, false));
        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        save.setTextColor(theme.accent); save.setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(theme.accent);
        String[] names = {"AUTO", "ROOT", "SHIZUKU", "NONE"};
        Handler main = new Handler(Looper.getMainLooper());
        String[] serviceStatus = {status.getText().toString()};
        Runnable showMode = () -> {
            boolean recording = mode.getSelectedItemPosition() == 3;
            keepRoot.setVisibility(recording ? View.GONE : View.VISIBLE);
            grant.setVisibility(recording ? View.GONE : View.VISIBLE);
            rootGrant.setVisibility(recording ? View.GONE : View.VISIBLE);
            refresh.setVisibility(recording ? View.GONE : View.VISIBLE);
            explanation.setText(recording ? "使用系统录屏投屏，无需 Root 或 Shizuku / Sui。每次开始时选择单个应用或整个屏幕。"
                    : "自动模式优先使用已授权的 Shizuku / Sui，其次检查 Root。已经授予 Root 权限时，开始投屏会自动连接，无需每次手动验证。");
            status.setText(recording ? "请在手机上操作录制的应用。锁屏或从系统结束录屏会停止投屏。LSPosed 模块仍需启用。" : serviceStatus[0]);
        };
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { showMode.run(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        class Requests implements Runnable {
            boolean busy, selected;
            Bundle queued;
            boolean queuedClose;
            @Override public void run() { query(new Bundle(), false); }
            void next() {
                if (queued == null) { main.postDelayed(this, 1500); return; }
                Bundle args = queued; boolean close = queuedClose; queued = null;
                query(args, close);
            }
            void query(Bundle args, boolean closeAfter) {
                if (!dialog.isShowing()) return;
                boolean mutation = args.getBoolean("save") || args.getBoolean("request_permission") || args.getBoolean("request_root");
                if (busy) {
                    // A tap during an automatic read must not be silently dropped or sent twice.
                    if (mutation && queued == null) {
                        queued = new Bundle(args); queuedClose = closeAfter;
                        mode.setEnabled(false); keepRoot.setEnabled(false); save.setEnabled(false); grant.setEnabled(false); rootGrant.setEnabled(false);
                    }
                    return;
                }
                busy = true; main.removeCallbacks(this);
                // Automatic status reads preserve both focus and the user's unsaved mode selection.
                if (mutation || !selected) { mode.setEnabled(false); keepRoot.setEnabled(false); save.setEnabled(false); grant.setEnabled(false); rootGrant.setEnabled(false); }
                refresh.setEnabled(false);
                frames.privilege(args, result -> {
                    busy = false;
                    if (closeAfter) {
                        if (dialog.isShowing()) dialog.dismiss();
                        saved.accept(PrivilegeMode.parse(result.getString("privilege_mode", "AUTO"))); return;
                    }
                    if (!dialog.isShowing()) return;
                    if (!selected) {
                        String selectedMode = result.getString("privilege_mode", "AUTO");
                        for (int i = 0; i < names.length; i++) if (names[i].equals(selectedMode)) mode.setSelection(i);
                        keepRoot.setChecked(result.getBoolean("keep_root"));
                        selected = true;
                    }
                    boolean active = result.getBoolean("active"), pending = result.getBoolean("privilege_pending");
                    serviceStatus[0] = result.getString("privilege_status", "授权服务状态不可用") + "\n\n" + result.getString("root_status", "Root 状态不可用")
                            + (active ? "\n请先结束投屏再修改授权方式。" : "");
                    mode.setEnabled(!active); keepRoot.setEnabled(!active); save.setEnabled(!active);
                    grant.setText(result.getBoolean("privilege_granted") ? "已授权" : pending ? "等待系统授权…" : "授权 Shizuku / Sui");
                    grant.setEnabled(result.getBoolean("privilege_can_request")); refresh.setEnabled(true);
                    rootGrant.setText(result.getBoolean("root_ready") ? "Root 权限可用" : result.getBoolean("root_pending") ? "正在检查 Root 权限…" : "申请 / 验证 Root 权限");
                    rootGrant.setEnabled(!active && !result.getBoolean("root_ready") && !result.getBoolean("root_pending"));
                    showMode.run();
                    next();
                }, message -> {
                    busy = false; if (!dialog.isShowing()) return;
                    status.setText(message); mode.setEnabled(false); keepRoot.setEnabled(false); save.setEnabled(false); grant.setEnabled(false); rootGrant.setEnabled(false); refresh.setEnabled(true);
                    next();
                });
            }
        }
        Requests requests = new Requests();
        refresh.setOnClickListener(v -> requests.run());
        grant.setOnClickListener(v -> {
            Bundle args = new Bundle(); args.putBoolean("request_permission", true); requests.query(args, false);
        });
        rootGrant.setOnClickListener(v -> {
            Bundle args = new Bundle(); args.putBoolean("request_root", true); requests.query(args, false);
        });
        save.setOnClickListener(v -> {
            int index = mode.getSelectedItemPosition(); if (index < 0 || index >= names.length) return;
            Bundle args = new Bundle(); args.putBoolean("save", true); args.putString("privilege_mode", names[index]); args.putBoolean("keep_root", keepRoot.isChecked());
            requests.query(args, true);
        });
        dialog.setOnDismissListener(v -> main.removeCallbacks(requests));
        requests.run();
    }
    private PrivilegeDialog() {}
}
