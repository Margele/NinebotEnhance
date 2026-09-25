package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.BmsSettings;
import dev.ichinomiya.ninebotenhance.core.WidgetSettings;

/**
 * Where the voltage and power come from and how often everything is read. With the dashboard as the source its two read
 * intervals are shown; with the BMS the board's poll interval takes their place and the vehicle registers are left alone.
 * The intervals live in the host's widget settings, the source choice and the poll interval in the module's BMS settings.
 */
public final class ReadSettingsDialog {
    private static final int STEP = WidgetSettings.READ_STEP_MS, MIN_STEPS = WidgetSettings.MIN_READ_MS / STEP, MAX_STEPS = WidgetSettings.MAX_READ_MS / STEP;
    private static final int BMS_STEP = BmsSettings.POLL_STEP_MS;
    private static final WidgetOptionsDialog.Describe SECONDS = v -> v + " 秒", HALF_SECONDS = v -> (v % 2 == 0 ? String.valueOf(v / 2) : v / 2 + ".5") + " 秒";
    public static void show(Activity activity, FrameClient frames, View reference) {
        MirrorUi theme = new MirrorUi(activity, reference); WidgetSettings s = frames.widgetSettings(); Bundle bms = frames.cachedBmsConfig();
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        int pad = MirrorUi.dp(activity, 20), gap = MirrorUi.dp(activity, 8); content.setPadding(pad, gap, pad, gap);
        content.addView(WidgetOptionsDialog.caption(activity, theme, "电压 / 功率来源"));
        RadioGroup sources = new RadioGroup(activity); sources.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton dashboard = radio(activity, theme, sources, "仪表"), board = radio(activity, theme, sources, "BMS");
        (s.enabled(WidgetSettings.VOLTAGE_FROM_BMS) ? board : dashboard).setChecked(true);
        content.addView(sources, new LinearLayout.LayoutParams(-1, -2));
        SeekBar tyres = WidgetOptionsDialog.slider(activity, theme, content, "胎压读取间隔", WidgetSettings.MIN_TYRE_SECONDS, WidgetSettings.MAX_TYRE_SECONDS, s.tyreIntervalSeconds(), SECONDS);
        SeekBar speed = WidgetOptionsDialog.slider(activity, theme, content, "速度读取间隔", MIN_STEPS, MAX_STEPS, s.speedIntervalMs() / STEP, HALF_SECONDS);
        LinearLayout fromDashboard = new LinearLayout(activity); fromDashboard.setOrientation(LinearLayout.VERTICAL); content.addView(fromDashboard, new LinearLayout.LayoutParams(-1, -2));
        SeekBar voltage = WidgetOptionsDialog.slider(activity, theme, fromDashboard, "电压读取间隔", MIN_STEPS, MAX_STEPS, s.voltageIntervalMs() / STEP, HALF_SECONDS);
        SeekBar power = WidgetOptionsDialog.slider(activity, theme, fromDashboard, "功率读取间隔", MIN_STEPS, MAX_STEPS, s.powerIntervalMs() / STEP, HALF_SECONDS);
        LinearLayout fromBoard = new LinearLayout(activity); fromBoard.setOrientation(LinearLayout.VERTICAL); content.addView(fromBoard, new LinearLayout.LayoutParams(-1, -2));
        SeekBar poll = WidgetOptionsDialog.slider(activity, theme, fromBoard, "BMS 轮询间隔", BmsSettings.MIN_POLL_MS / BMS_STEP, BmsSettings.MAX_POLL_MS / BMS_STEP,
                bms.getInt("bms_poll_ms", BmsSettings.DEFAULT_POLL_MS) / BMS_STEP, HALF_SECONDS);
        Runnable showSource = () -> { boolean fromBms = board.isChecked(); fromDashboard.setVisibility(fromBms ? View.GONE : View.VISIBLE); fromBoard.setVisibility(fromBms ? View.VISIBLE : View.GONE); };
        sources.setOnCheckedChangeListener((g, id) -> showSource.run()); showSource.run();
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        TextView title = new TextView(activity); title.setText("数据读取设置"); title.setTextSize(20); title.setTextColor(theme.text); title.setPadding(pad, pad, pad, pad / 2);
        AlertDialog dialog = new AlertDialog.Builder(activity).setCustomTitle(title).setView(scroll).setNegativeButton("关闭", null).setPositiveButton("保存", null).create();
        dialog.show(); dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 22, false));
        dialog.getButton(-1).setTextColor(theme.accent); dialog.getButton(-2).setTextColor(theme.accent);
        // The module's saved poll interval may be newer than the cached one; the slider follows once it answers.
        frames.bmsConfig(new Bundle(), result -> { if (dialog.isShowing() && result.containsKey("bms_poll_ms")) poll.setProgress(result.getInt("bms_poll_ms") / BMS_STEP); }, error -> {});
        dialog.getButton(-1).setOnClickListener(v -> {
            boolean fromBms = board.isChecked();
            WidgetSettings next = frames.widgetSettings().readIntervals(tyres.getProgress(), voltage.getProgress() * STEP, speed.getProgress() * STEP, power.getProgress() * STEP)
                    .with(WidgetSettings.VOLTAGE_FROM_BMS, fromBms).with(WidgetSettings.POWER_FROM_BMS, fromBms);
            frames.saveWidgetSettings(next);
            Bundle args = new Bundle(); args.putBoolean("save", true); args.putBoolean("bms_prefer", fromBms); args.putInt("bms_poll_ms", poll.getProgress() * BMS_STEP);
            frames.bmsConfig(args, result -> {}, error -> { if (!activity.isDestroyed()) Toast.makeText(activity, error, Toast.LENGTH_LONG).show(); });
            dialog.dismiss();
        });
    }
    private static RadioButton radio(Activity activity, MirrorUi theme, RadioGroup parent, String label) {
        RadioButton button = new RadioButton(activity); button.setId(View.generateViewId()); button.setText(label); button.setTextColor(theme.text); button.setTextSize(15);
        button.setButtonTintList(ColorStateList.valueOf(theme.accent)); button.setPadding(0, MirrorUi.dp(activity, 6), MirrorUi.dp(activity, 20), MirrorUi.dp(activity, 6));
        parent.addView(button, new RadioGroup.LayoutParams(-2, -2)); return button;
    }
    private ReadSettingsDialog() {}
}
